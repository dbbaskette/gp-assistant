package com.baskettecase.gpassistant.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Value("${app.rag.top-k:5}")
    private int ragTopK;

    @Value("${app.rag.similarity-threshold:0.7}")
    private double ragSimilarityThreshold;

    @Value("${app.vectorstore.dimensions:1536}")
    private int vectorStoreDimensions;

    @Bean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        log.info("Configuring PgVectorStore with table: gp_docs (dimensions={})", vectorStoreDimensions);

        var builder = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("public")
                .vectorTableName("gp_docs")
                .initializeSchema(true);

        if (vectorStoreDimensions > 0) {
            builder.dimensions(vectorStoreDimensions);
        }

        return builder.build();
    }

    @Bean
    public QuestionAnswerAdvisor qaAdvisor(VectorStore vectorStore) {
        log.info("Configuring QuestionAnswerAdvisor with topK={}, similarityThreshold={}", 
                ragTopK, ragSimilarityThreshold);
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(ragTopK)
                        .similarityThreshold(ragSimilarityThreshold)
                        .build())
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                org.springframework.ai.tool.ToolCallbackProvider toolCallbackProvider) {
        log.info("Configuring ChatClient");

        if (toolCallbackProvider != null) {
            log.info("✅ MCP tools available - integrating into ChatClient");
            return builder.defaultToolCallbacks(toolCallbackProvider).build();
        } else {
            log.info("⚠️  No MCP tools available - ChatClient configured without tools");
            return builder.build();
        }
    }

    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        log.info("Configuring InMemoryChatMemoryRepository");
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        log.info("Configuring MessageWindowChatMemory for conversation history");
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }
}
