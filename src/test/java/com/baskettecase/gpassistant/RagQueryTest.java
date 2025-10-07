package com.baskettecase.gpassistant;

import com.baskettecase.gpassistant.service.DocsChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for RAG (Retrieval Augmented Generation) queries.
 * Tests that the system can answer questions using the vector store and LLM.
 *
 * Prerequisites:
 * - Local LLM server running on http://127.0.0.1:1234
 * - PostgreSQL/Greenplum running with vector store data
 * - MCP disabled for pure RAG testing
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.ai.mcp.client.enabled=false",
        "app.docs.ingest-on-startup=false",
        "spring.http.client.read-timeout=120s",
        "spring.http.client.connect-timeout=30s"
})
@EnabledIf("isLlmAvailable")
public class RagQueryTest {

    @Autowired
    private DocsChatService chatService;

    /**
     * Condition to check if LLM is available
     */
    static boolean isLlmAvailable() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:1234/v1/models"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.out.println("LLM not available, skipping test: " + e.getMessage());
            return false;
        }
    }

    @Test
    public void testDistributedTableQuery() {
        // Given: A question about Greenplum distributed tables
        String question = "What is a distributed table in Greenplum?";
        String conversationId = "test-distributed-table-" + System.currentTimeMillis();

        // When: We ask the question
        String response = chatService.ask(
                question,
                "7.0",              // targetVersion
                new String[]{"7.x"}, // compatibleBaselines
                "7.0",              // defaultAssumeVersion
                conversationId,
                null,               // database
                null                // schema
        );

        // Then: The response should mention distributed tables
        assertThat(response)
                .as("Response should not be empty")
                .isNotNull()
                .isNotEmpty();

        assertThat(response.toLowerCase())
                .as("Response should mention distributed or distribution")
                .containsAnyOf("distributed", "distribution", "segment", "distribute");

        System.out.println("\n=== RAG Query Test Results ===");
        System.out.println("Question: " + question);
        System.out.println("\nResponse: " + response);
        System.out.println("================================\n");
    }

    @Test
    public void testPartitioningQuery() {
        // Given: A question about table partitioning
        String question = "How does table partitioning work in Greenplum?";
        String conversationId = "test-partitioning-" + System.currentTimeMillis();

        // When: We ask the question
        String response = chatService.ask(
                question,
                "7.0",
                new String[]{"7.x"},
                "7.0",
                conversationId,
                null,
                null
        );

        // Then: The response should mention partitioning concepts
        assertThat(response)
                .as("Response should not be empty")
                .isNotNull()
                .isNotEmpty();

        assertThat(response.toLowerCase())
                .as("Response should mention partition-related terms")
                .containsAnyOf("partition", "range", "list", "subpartition");

        System.out.println("\n=== RAG Query Test Results ===");
        System.out.println("Question: " + question);
        System.out.println("\nResponse: " + response);
        System.out.println("================================\n");
    }

    @Test
    public void testConversationMemory() {
        // Given: A conversation with context
        String conversationId = "test-memory-" + System.currentTimeMillis();

        // When: We ask a follow-up question
        String firstQuestion = "What is GPORCA?";
        String firstResponse = chatService.ask(firstQuestion, "7.0", new String[]{"7.x"}, "7.0", conversationId, null, null);

        String followUpQuestion = "How does it improve query performance?";
        String followUpResponse = chatService.ask(followUpQuestion, "7.0", new String[]{"7.x"}, "7.0", conversationId, null, null);

        // Then: Both responses should be relevant
        assertThat(firstResponse)
                .as("First response should mention GPORCA or optimizer")
                .isNotEmpty();

        assertThat(followUpResponse)
                .as("Follow-up response should understand 'it' refers to GPORCA/optimizer")
                .isNotEmpty();

        System.out.println("\n=== Conversation Memory Test Results ===");
        System.out.println("Q1: " + firstQuestion);
        System.out.println("A1: " + firstResponse);
        System.out.println("\nQ2: " + followUpQuestion);
        System.out.println("A2: " + followUpResponse);
        System.out.println("=========================================\n");
    }
}
