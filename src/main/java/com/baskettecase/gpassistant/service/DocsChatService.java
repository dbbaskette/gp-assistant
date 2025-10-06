package com.baskettecase.gpassistant.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baskettecase.gpassistant.PromptLoader;

import jakarta.annotation.PostConstruct;

@Service
public class DocsChatService {

    private static final Logger log = LoggerFactory.getLogger(DocsChatService.class);

    private final ChatClient chatClient;
    private final QuestionAnswerAdvisor qaAdvisor;
    private final ChatMemory chatMemory;
    private final GreenplumVersionService versionService;
    private final MeterRegistry meterRegistry;
    private final org.springframework.ai.tool.ToolCallbackProvider toolCallbackProvider;

    public DocsChatService(ChatClient chatClient, QuestionAnswerAdvisor qaAdvisor,
                          ChatMemory chatMemory, GreenplumVersionService versionService,
                          MeterRegistry meterRegistry,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          org.springframework.ai.tool.ToolCallbackProvider toolCallbackProvider) {
        this.chatClient = chatClient;
        this.qaAdvisor = qaAdvisor;
        this.chatMemory = chatMemory;
        this.versionService = versionService;
        this.meterRegistry = meterRegistry;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    private Counter queryCounter;
    private Timer queryTimer;

    @PostConstruct
    public void initializeMetrics() {
        queryCounter = Counter.builder("gp_assistant.chat.queries")
                .description("Number of chat queries processed")
                .register(meterRegistry);
        
        queryTimer = Timer.builder("gp_assistant.chat.duration")
                .description("Time taken to process chat queries")
                .register(meterRegistry);
    }

    /**
     * Process a user question with conversation history support.
     *
     * @param userQuestion The user's question
     * @param targetVersion Target Greenplum version for the response
     * @param compatibleBaselines Compatible version baselines
     * @param defaultAssume Default version to assume if not specified
     * @param conversationId Unique conversation ID (for history tracking)
     * @param databaseName Current database context (optional)
     * @param schemaName Current schema context (optional)
     * @return The assistant's response
     */
    public String ask(String userQuestion, String targetVersion, String[] compatibleBaselines,
                     String defaultAssume, String conversationId, String databaseName, String schemaName) {
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            log.debug("Processing question for conversation {}: {}", conversationId, userQuestion);
            
            // Get connected database version
            String connectedVersion = versionService.getFullVersion();
            boolean isGreenplum = versionService.isGreenplum();
            
            log.info("Connected to: {} | Target version: {} | Conversation: {}", 
                    connectedVersion, targetVersion, conversationId);
            
            // Load and customize system prompt
            String system = PromptLoader.load("prompts/gp_system.txt")
                    .replace("{{TARGET_GP_VERSION}}", targetVersion)
                    .replace("{{COMPATIBLE_BASELINES}}", java.util.Arrays.toString(compatibleBaselines))
                    .replace("{{DEFAULT_ASSUME_VERSION}}", defaultAssume)
                    .replace("{{CONNECTED_VERSION}}", connectedVersion)
                    .replace("{{IS_GREENPLUM}}", String.valueOf(isGreenplum))
                    .replace("{{DATABASE_NAME}}", databaseName != null && !databaseName.isEmpty() ? databaseName : "not specified")
                    .replace("{{SCHEMA_NAME}}", schemaName != null && !schemaName.isEmpty() ? schemaName : "not specified");

            // Load and customize user prompt
            String user = PromptLoader.load("prompts/gp_user.txt")
                    .replace("{{USER_QUESTION}}", userQuestion)
                    .replace("{{TARGET_GP_VERSION}}", targetVersion)
                    .replace("{{COMPATIBLE_BASELINES}}", java.util.Arrays.toString(compatibleBaselines))
                    .replace("{{DEFAULT_ASSUME_VERSION}}", defaultAssume)
                    .replace("{{CONNECTED_VERSION}}", connectedVersion)
                    .replace("{{DATABASE_NAME}}", databaseName != null && !databaseName.isEmpty() ? databaseName : "not specified")
                    .replace("{{SCHEMA_NAME}}", schemaName != null && !schemaName.isEmpty() ? schemaName : "not specified")
                    .replace("{{RESOURCES}}", "<resources will be stitched by the application>");

            // Build the prompt with conversation history
            var promptBuilder = chatClient.prompt()
                    .system(system)
                    .advisors(qaAdvisor)
                    .user(user);

            // Add conversation history
            if (conversationId != null && !conversationId.isEmpty()) {
                chatMemory.get(conversationId).forEach(message -> {
                    log.debug("Adding history message to conversation {}: {}", 
                            conversationId, message.getClass().getSimpleName());
                });
                promptBuilder.advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, conversationId));
            }

            // Add MCP tools explicitly to the prompt request
            // Note: defaultToolCallbacks() on ChatClient.Builder doesn't seem to work with local models
            // so we explicitly add tools per-request
            log.debug("toolCallbackProvider is null: {}", toolCallbackProvider == null);
            if (toolCallbackProvider != null) {
                org.springframework.ai.tool.ToolCallback[] tools = toolCallbackProvider.getToolCallbacks();
                log.debug("Tool callbacks retrieved: {} tools", tools != null ? tools.length : "null");
                if (tools != null && tools.length > 0) {
                    log.info("✅ Adding {} MCP tools to chat request", tools.length);
                    promptBuilder.toolCallbacks(tools);
                } else {
                    log.warn("⚠️  No MCP tools available - toolCallbackProvider returned empty/null");
                }
            } else {
                log.warn("⚠️  ToolCallbackProvider is null - no MCP tools available");
            }

            // Execute the chat
            // Use .chatResponse() instead of .content() to properly handle tool calls
            // When tools are called, Spring AI needs to execute them and send results back to LLM
            org.springframework.ai.chat.model.ChatResponse chatResponse = promptBuilder.call().chatResponse();
            String response = chatResponse.getResult().getOutput().getText();
            
            // Store conversation history
            if (conversationId != null && !conversationId.isEmpty()) {
                chatMemory.add(conversationId, new UserMessage(userQuestion));
                chatMemory.add(conversationId, new AssistantMessage(response));
                log.debug("Stored conversation history for {}", conversationId);
            }
            
            queryCounter.increment();
            sample.stop(queryTimer);
            
            log.debug("Successfully processed question for conversation {}", conversationId);
            return response;
            
        } catch (Exception e) {
            log.error("Failed to process question for conversation {}: {}", conversationId, userQuestion, e);
            sample.stop(queryTimer);
            throw new RuntimeException("Failed to process question", e);
        }
    }

    /**
     * Convenience method without conversation ID (stateless interaction).
     */
    public String ask(String userQuestion, String targetVersion, String[] compatibleBaselines,
                     String defaultAssume) {
        return ask(userQuestion, targetVersion, compatibleBaselines, defaultAssume, null, null, null);
    }
}