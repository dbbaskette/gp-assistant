package com.baskettecase.gpassistant.service;

import com.baskettecase.gpassistant.domain.McpServerEntity;
import com.baskettecase.gpassistant.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Service for managing MCP server configurations.
 * Handles CRUD operations, connection testing, and activation.
 */
@Service
public class McpServerConfigService {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfigService.class);

    private final McpServerRepository repository;
    private final EncryptionService encryptionService;
    private final McpClientCommonProperties commonProperties;
    private final DynamicMcpClientManager clientManager;

    public McpServerConfigService(
            McpServerRepository repository,
            EncryptionService encryptionService,
            McpClientCommonProperties commonProperties,
            Optional<DynamicMcpClientManager> clientManager) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.commonProperties = commonProperties;
        this.clientManager = clientManager.orElse(null);
    }

    /**
     * Get all MCP servers.
     */
    public List<McpServerEntity> getAllServers() {
        return repository.findAll();
    }

    /**
     * Get MCP server by ID.
     */
    public Optional<McpServerEntity> getServer(UUID id) {
        return repository.findById(id);
    }

    /**
     * Get the currently active MCP server.
     */
    public Optional<McpServerEntity> getActiveServer() {
        return repository.findActiveServer();
    }

    /**
     * Create a new MCP server configuration.
     * Encrypts the API key before storage.
     */
    @Transactional
    public McpServerEntity createServer(String name, String url, String apiKey, String description) {
        // Validate name uniqueness
        if (repository.existsByName(name)) {
            throw new IllegalArgumentException("MCP server with name '" + name + "' already exists");
        }

        // Encrypt API key
        String encryptedApiKey = encryptionService.encrypt(apiKey);

        // Create entity
        McpServerEntity entity = McpServerEntity.builder()
                .name(name)
                .url(url)
                .apiKeyEncrypted(encryptedApiKey)
                .description(description)
                .status("disconnected")
                .active(false)
                .toolCount(0)
                .build();

        McpServerEntity saved = repository.save(entity);
        log.info("Created MCP server: {} (id={})", name, saved.getId());

        return saved;
    }

    /**
     * Update an existing MCP server.
     */
    @Transactional
    public McpServerEntity updateServer(UUID id, String name, String url, String apiKey, String description) {
        McpServerEntity existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + id));

        // Check name uniqueness (excluding current server)
        repository.findByName(name).ifPresent(server -> {
            if (!server.getId().equals(id)) {
                throw new IllegalArgumentException("MCP server with name '" + name + "' already exists");
            }
        });

        // Update fields
        existing.setName(name);
        existing.setUrl(url);
        existing.setDescription(description);

        // Update API key if provided (non-empty)
        if (apiKey != null && !apiKey.isBlank()) {
            String encryptedApiKey = encryptionService.encrypt(apiKey);
            existing.setApiKeyEncrypted(encryptedApiKey);
        }

        McpServerEntity updated = repository.save(existing);
        log.info("Updated MCP server: {} (id={})", name, id);

        return updated;
    }

    /**
     * Delete an MCP server.
     */
    @Transactional
    public void deleteServer(UUID id) {
        McpServerEntity server = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + id));

        if (server.isActive()) {
            throw new IllegalStateException("Cannot delete active MCP server. Deactivate it first.");
        }

        repository.deleteById(id);
        log.info("Deleted MCP server: {} (id={})", server.getName(), id);
    }

    /**
     * Set a server as active (and deactivate all others).
     * Triggers reconnection to the newly active server.
     */
    @Transactional
    public void setActiveServer(UUID id) {
        McpServerEntity server = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + id));

        repository.setActive(id);
        log.info("Set MCP server as active: {} (id={})", server.getName(), id);

        // Trigger reconnection if DynamicMcpClientManager is available
        if (clientManager != null) {
            log.info("Triggering MCP client reconnection for new active server");
            clientManager.reconnectActiveServer();
        } else {
            log.debug("DynamicMcpClientManager not available (MCP may be disabled)");
        }
    }

    /**
     * Deactivate all MCP servers.
     */
    @Transactional
    public void deactivateAll() {
        repository.findActiveServer().ifPresent(server -> {
            server.setActive(false);
            repository.save(server);
            log.info("Deactivated MCP server: {}", server.getName());
        });
    }

    /**
     * Test connection to an MCP server.
     * Creates a temporary client, validates connection, and retrieves tools.
     * Does NOT keep the connection alive (Option A: Test-only).
     *
     * @param id Server ID
     * @return TestConnectionResult with status and details
     */
    public TestConnectionResult testConnection(UUID id) {
        McpServerEntity server = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + id));

        return testConnection(server);
    }

    /**
     * Test connection using server configuration.
     * Used for testing before saving a new server.
     */
    public TestConnectionResult testConnection(String url, String apiKey) {
        McpServerEntity tempServer = McpServerEntity.builder()
                .name("temp-test")
                .url(url)
                .apiKeyEncrypted(encryptionService.encrypt(apiKey))
                .build();

        return testConnection(tempServer);
    }

    /**
     * Internal test connection implementation.
     */
    private TestConnectionResult testConnection(McpServerEntity server) {
        log.info("Testing connection to MCP server: {}", server.getName());

        McpSyncClient client = null;
        try {
            // Decrypt API key
            String apiKey = encryptionService.decrypt(server.getApiKeyEncrypted());
            log.debug("Decrypted API key for connection test (length: {})", apiKey.length());

            // Create HttpClient wrapped with Authorization header support
            HttpClient baseClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            final HttpClient authorizingClient = new AuthorizingHttpClient(baseClient, apiKey);

            // Create a minimal builder that returns our pre-built authorizing client
            HttpClient.Builder wrappingBuilder = new HttpClient.Builder() {
                public HttpClient build() { return authorizingClient; }
                public HttpClient.Builder cookieHandler(CookieHandler h) { return this; }
                public HttpClient.Builder connectTimeout(Duration d) { return this; }
                public HttpClient.Builder sslContext(SSLContext c) { return this; }
                public HttpClient.Builder sslParameters(SSLParameters p) { return this; }
                public HttpClient.Builder executor(Executor e) { return this; }
                public HttpClient.Builder followRedirects(HttpClient.Redirect r) { return this; }
                public HttpClient.Builder version(HttpClient.Version v) { return this; }
                public HttpClient.Builder priority(int p) { return this; }
                public HttpClient.Builder proxy(ProxySelector ps) { return this; }
                public HttpClient.Builder authenticator(Authenticator a) { return this; }
            };

            // Create transport with the wrapping builder
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder(server.getUrl())
                    .endpoint("/mcp")
                    .clientBuilder(wrappingBuilder)
                    .build();

            // Create MCP client
            McpSchema.Implementation clientInfo = new McpSchema.Implementation(
                    commonProperties.getName() + " - test",
                    commonProperties.getVersion()
            );

            client = McpClient.sync(transport)
                    .clientInfo(clientInfo)
                    .requestTimeout(Duration.ofSeconds(10))
                    .build();

            // Initialize connection
            client.initialize();

            // List tools
            var toolsResult = client.listTools();
            int toolCount = toolsResult != null && toolsResult.tools() != null
                    ? toolsResult.tools().size()
                    : 0;

            // Update server status in database
            repository.updateStatus(
                    server.getId(),
                    "connected",
                    "Connection successful",
                    toolCount
            );
            repository.updateLastTested(server.getId());

            log.info("✅ Connection test successful: {} ({} tools)", server.getName(), toolCount);

            return TestConnectionResult.success(
                    "Connected successfully",
                    toolCount,
                    toolsResult != null ? toolsResult.tools() : List.of()
            );

        } catch (Exception e) {
            String errorMessage = e.getMessage();
            log.warn("❌ Connection test failed: {} - {}", server.getName(), errorMessage);

            // Update server status in database
            if (server.getId() != null) {
                repository.updateStatus(
                        server.getId(),
                        "error",
                        "Connection failed: " + errorMessage,
                        0
                );
                repository.updateLastTested(server.getId());
            }

            return TestConnectionResult.failure(errorMessage, e);

        } finally {
            // Close connection (Option A: test-only)
            if (client != null) {
                try {
                    client.close();
                    log.debug("Closed test connection to {}", server.getName());
                } catch (Exception e) {
                    log.warn("Error closing test connection: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Result of a connection test.
     */
    public static class TestConnectionResult {
        private final boolean success;
        private final String message;
        private final int toolCount;
        private final List<McpSchema.Tool> tools;
        private final Exception error;

        private TestConnectionResult(boolean success, String message, int toolCount,
                                     List<McpSchema.Tool> tools, Exception error) {
            this.success = success;
            this.message = message;
            this.toolCount = toolCount;
            this.tools = tools;
            this.error = error;
        }

        public static TestConnectionResult success(String message, int toolCount, List<McpSchema.Tool> tools) {
            return new TestConnectionResult(true, message, toolCount, tools, null);
        }

        public static TestConnectionResult failure(String message, Exception error) {
            return new TestConnectionResult(false, message, 0, List.of(), error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public int getToolCount() {
            return toolCount;
        }

        public List<McpSchema.Tool> getTools() {
            return tools;
        }

        public Exception getError() {
            return error;
        }

        @Override
        public String toString() {
            return "TestConnectionResult{" +
                    "success=" + success +
                    ", message='" + message + '\'' +
                    ", toolCount=" + toolCount +
                    '}';
        }
    }
}
