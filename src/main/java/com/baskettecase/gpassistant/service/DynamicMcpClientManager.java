package com.baskettecase.gpassistant.service;

import com.baskettecase.gpassistant.config.McpConnectionProperties;
import com.baskettecase.gpassistant.domain.McpConnectionStatus;
import com.baskettecase.gpassistant.domain.McpServerEntity;
import com.baskettecase.gpassistant.exception.McpConnectionException;
import com.baskettecase.gpassistant.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages dynamic MCP client connections with retry logic.
 * Loads connections from database (mcp_servers table) and handles automatic retry on failure.
 * Connects only to the active MCP server from the database.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.mcp.client.use-dynamic-manager", havingValue = "true")
public class DynamicMcpClientManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicMcpClientManager.class);

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    private final McpClientCommonProperties commonProperties;
    private final McpServerRepository mcpServerRepository;
    private final EncryptionService encryptionService;
    private final ScheduledExecutorService retryExecutor = Executors.newScheduledThreadPool(2);

    // In-memory storage
    private final Map<String, McpSyncClient> activeClients = new ConcurrentHashMap<>();
    private final Map<String, McpConnectionStatus> connectionStatuses = new ConcurrentHashMap<>();

    public DynamicMcpClientManager(
            McpClientCommonProperties commonProperties,
            McpServerRepository mcpServerRepository,
            EncryptionService encryptionService) {
        this.commonProperties = commonProperties;
        this.mcpServerRepository = mcpServerRepository;
        this.encryptionService = encryptionService;
        log.info("🔧 DynamicMcpClientManager initialized with database-backed configuration");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeConnections() {
        log.info("=== Initializing MCP Connections from Database ===");

        // Load active MCP server from database
        Optional<McpServerEntity> activeServerOpt = mcpServerRepository.findActive();

        if (activeServerOpt.isEmpty()) {
            log.warn("⚠️  No active MCP server configured in database");
            log.info("✅ Application running without MCP servers");
            log.info("💡 Configure MCP servers via Settings UI at /");
            return;
        }

        McpServerEntity server = activeServerOpt.get();
        log.info("Found active MCP server '{}' at {}", server.getName(), server.getUrl());

        // Create connection status
        McpConnectionStatus status = new McpConnectionStatus(
                server.getId().toString(),
                server.getUrl(),
                "/mcp",  // Standard endpoint
                true
        );
        connectionStatuses.put(server.getId().toString(), status);

        // Attempt to connect
        attemptConnection(server);

        log.info("=== MCP Connection Initialization Complete ===");
    }

    /**
     * Attempt to connect to an MCP server from database entity.
     */
    private void attemptConnection(McpServerEntity server) {
        String serverId = server.getId().toString();
        try {
            log.info("🔄 Attempting to connect to MCP server '{}' at {}", server.getName(), server.getUrl());

            McpSyncClient client = buildClient(server);
            activeClients.put(serverId, client);

            McpConnectionStatus status = connectionStatuses.get(serverId);
            status.markSuccess();

            // Discover and update tools
            updateToolInformation(serverId, client, status);

            // Update database status
            mcpServerRepository.updateStatus(
                    server.getId(),
                    McpServerEntity.Status.CONNECTED.getValue(),
                    "Connected successfully",
                    status.getToolCount()
            );
            mcpServerRepository.updateLastConnectedAt(server.getId());

            log.info("✅ Successfully connected to MCP server '{}' ({} tools)", server.getName(), status.getToolCount());

        } catch (Exception e) {
            log.warn("❌ Failed to connect to MCP server '{}': {}", server.getName(), e.getMessage());

            McpConnectionStatus status = connectionStatuses.get(serverId);
            status.markFailure(e.getMessage());

            // Update database status
            mcpServerRepository.updateStatus(
                    server.getId(),
                    McpServerEntity.Status.ERROR.getValue(),
                    e.getMessage(),
                    0
            );

            // Check if retryable
            if (isRetryableException(e)) {
                scheduleRetry(server);
            } else {
                log.error("🚫 Non-retryable error for '{}': {}", server.getName(), e.getMessage());
            }
        }
    }

    /**
     * Build MCP client from database entity with X-API-Key authentication.
     */
    private McpSyncClient buildClient(McpServerEntity server) {
        String baseUrl = server.getUrl();
        String endpoint = "/mcp";  // Standard MCP endpoint

        // Decrypt API key
        String apiKey = encryptionService.decrypt(server.getApiKeyEncrypted());
        log.debug("Building client for server '{}' with API key length: {}", server.getName(), apiKey.length());

        // Create HttpClient wrapped with X-API-Key header support
        HttpClient baseClient = HttpClient.newBuilder()
                .connectTimeout(commonProperties.getRequestTimeout())
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
            public HttpClient.Builder priority(int pri) { return this; }
            public HttpClient.Builder proxy(ProxySelector ps) { return this; }
            public HttpClient.Builder authenticator(Authenticator a) { return this; }
        };

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(baseUrl)
                .endpoint(endpoint)
                .clientBuilder(wrappingBuilder)
                .build();

        McpSchema.Implementation clientInfo = new McpSchema.Implementation(
                commonProperties.getName() + " - " + server.getName(),
                commonProperties.getVersion()
        );

        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(clientInfo)
                .requestTimeout(commonProperties.getRequestTimeout())
                .build();

        // Initialize the client
        client.initialize();

        log.info("✅ MCP client built and initialized for server '{}'", server.getName());

        return client;
    }

    private void updateToolInformation(String name, McpSyncClient client, McpConnectionStatus status) {
        try {
            var listToolsResult = client.listTools();
            if (listToolsResult != null && listToolsResult.tools() != null) {
                List<String> toolNames = listToolsResult.tools().stream()
                        .map(tool -> tool.name())
                        .toList();
                status.updateTools(toolNames);
                log.debug("📋 Discovered {} tools from '{}'", toolNames.size(), name);
            }
        } catch (Exception e) {
            log.warn("⚠️  Failed to list tools from '{}': {}", name, e.getMessage());
            status.updateTools(List.of());
        }
    }

    private boolean isRetryableException(Throwable ex) {
        String message = ex.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            if (lowerMessage.contains("connection refused") ||
                    lowerMessage.contains("connect exception") ||
                    lowerMessage.contains("timeout") ||
                    lowerMessage.contains("connection reset") ||
                    lowerMessage.contains("closed channel") ||
                    lowerMessage.contains("no route to host") ||
                    lowerMessage.contains("network unreachable") ||
                    lowerMessage.contains("failed to initialize")) {
                return true;
            }
        }

        return ex instanceof java.net.ConnectException ||
                ex instanceof java.net.SocketTimeoutException ||
                ex instanceof java.nio.channels.ClosedChannelException ||
                ex instanceof java.io.IOException;
    }

    private void scheduleRetry(McpServerEntity server) {
        String serverId = server.getId().toString();
        McpConnectionStatus status = connectionStatuses.get(serverId);

        if (status.getRetryCount() >= MAX_RETRY_ATTEMPTS) {
            log.error("🚫 Max retry attempts ({}) exceeded for '{}'. Giving up.", MAX_RETRY_ATTEMPTS, server.getName());
            mcpServerRepository.updateStatus(
                    server.getId(),
                    McpServerEntity.Status.ERROR.getValue(),
                    "Max retry attempts exceeded",
                    0
            );
            return;
        }

        status.incrementRetryCount();
        Duration delay = calculateRetryDelay(status.getRetryCount());
        status.setNextRetryAt(Instant.now().plus(delay));

        log.info("⏱️  Scheduling retry {} for '{}' in {} seconds",
                status.getRetryCount(), server.getName(), delay.getSeconds());

        retryExecutor.schedule(() -> {
            log.info("🔄 Executing retry {} for '{}'", status.getRetryCount(), server.getName());
            attemptConnection(server);
        }, delay.getSeconds(), TimeUnit.SECONDS);
    }

    private Duration calculateRetryDelay(int retryCount) {
        // Exponential backoff: 5s, 10s, 20s, 40s, 80s (capped at 5 min)
        long delaySeconds = Math.min(
                INITIAL_RETRY_DELAY.getSeconds() * (1L << (retryCount - 1)),
                MAX_RETRY_DELAY.getSeconds()
        );
        return Duration.ofSeconds(delaySeconds);
    }

    /**
     * Get all currently active MCP sync clients.
     */
    public Collection<McpSyncClient> getActiveClients() {
        return new ArrayList<>(activeClients.values());
    }

    /**
     * Get connection status for all configured connections.
     */
    public Collection<McpConnectionStatus> getAllStatuses() {
        return new ArrayList<>(connectionStatuses.values());
    }

    /**
     * Get connection status by server ID.
     */
    public McpConnectionStatus getStatus(String serverId) {
        return connectionStatuses.get(serverId);
    }

    /**
     * Manually retry the active MCP server connection.
     */
    public void retryActiveConnection() {
        Optional<McpServerEntity> activeServerOpt = mcpServerRepository.findActive();
        if (activeServerOpt.isEmpty()) {
            throw new McpConnectionException("No active MCP server configured");
        }

        McpServerEntity server = activeServerOpt.get();
        String serverId = server.getId().toString();
        McpConnectionStatus status = connectionStatuses.get(serverId);

        if (status == null) {
            // Create new status if missing
            status = new McpConnectionStatus(
                    serverId,
                    server.getUrl(),
                    "/mcp",
                    true
            );
            connectionStatuses.put(serverId, status);
        }

        log.info("🔄 Manual retry requested for '{}'", server.getName());
        status.setRetryCount(0); // Reset retry count for manual retry
        attemptConnection(server);
    }

    /**
     * Reconnect to the active MCP server (called when active server changes).
     */
    public void reconnectActiveServer() {
        // Close all existing connections
        activeClients.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing MCP client: {}", e.getMessage());
            }
        });
        activeClients.clear();
        connectionStatuses.clear();

        // Initialize connections from database
        initializeConnections();
    }

    /**
     * Shutdown executor on application stop.
     */
    public void shutdown() {
        log.info("🔌 Shutting down MCP client manager");
        retryExecutor.shutdown();
        activeClients.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing MCP client: {}", e.getMessage());
            }
        });
    }
}
