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

    public DocsChatService(ChatClient chatClient, QuestionAnswerAdvisor qaAdvisor,
                          ChatMemory chatMemory, GreenplumVersionService versionService,
                          MeterRegistry meterRegistry) {
        this.chatClient = chatClient;
        this.qaAdvisor = qaAdvisor;
        this.chatMemory = chatMemory;
        this.versionService = versionService;
        this.meterRegistry = meterRegistry;
    }
    
    // MCP Tool Provider - will be null if MCP is not enabled
    // TODO: Uncomment and use when MCP servers are configured
    // @Autowired(required = false)
    // private SyncMcpToolCallbackProvider toolCallbackProvider;

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
     * @return The assistant's response
     */
    public String ask(String userQuestion, String targetVersion, String[] compatibleBaselines, 
                     String defaultAssume, String conversationId) {
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
                    .replace("{{IS_GREENPLUM}}", String.valueOf(isGreenplum));

            // Load and customize user prompt
            String user = PromptLoader.load("prompts/gp_user.txt")
                    .replace("{{USER_QUESTION}}", userQuestion)
                    .replace("{{TARGET_GP_VERSION}}", targetVersion)
                    .replace("{{COMPATIBLE_BASELINES}}", java.util.Arrays.toString(compatibleBaselines))
                    .replace("{{DEFAULT_ASSUME_VERSION}}", defaultAssume)
                    .replace("{{CONNECTED_VERSION}}", connectedVersion)
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
            
            // Add MCP tools if available
            // TODO: Uncomment when MCP servers are configured
            // if (toolCallbackProvider != null) {
            //     ToolCallback[] mcpTools = toolCallbackProvider.getToolCallbacks();
            //     if (mcpTools != null && mcpTools.length > 0) {
            //         log.debug("Adding {} MCP tools to prompt", mcpTools.length);
            //         promptBuilder.toolCallbacks(mcpTools);
            //     }
            // }

            // Execute the chat
            String response = promptBuilder.call().content();
            
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
        return ask(userQuestion, targetVersion, compatibleBaselines, defaultAssume, null);
    }
}