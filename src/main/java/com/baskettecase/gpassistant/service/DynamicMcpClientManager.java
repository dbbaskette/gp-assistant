package com.baskettecase.gpassistant.service;

import com.baskettecase.gpassistant.config.McpConnectionProperties;
import com.baskettecase.gpassistant.domain.McpConnectionStatus;
import com.baskettecase.gpassistant.exception.McpConnectionException;
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

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages dynamic MCP client connections with retry logic.
 * Loads connections from application.yaml and handles automatic retry on failure.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true")
public class DynamicMcpClientManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicMcpClientManager.class);

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    private final McpClientCommonProperties commonProperties;
    private final ScheduledExecutorService retryExecutor = Executors.newScheduledThreadPool(2);

    // In-memory storage
    private final Map<String, McpSyncClient> activeClients = new ConcurrentHashMap<>();
    private final Map<String, McpConnectionStatus> connectionStatuses = new ConcurrentHashMap<>();

    public DynamicMcpClientManager(McpClientCommonProperties commonProperties) {
        this.commonProperties = commonProperties;
        log.info("🔧 DynamicMcpClientManager initialized");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeConnections() {
        log.info("=== Initializing MCP Connections ===");

        // Create connections from environment variables
        Map<String, McpConnectionProperties.ConnectionConfig> connections = new ConcurrentHashMap<>();

        // Default gp-schema connection
        String gpSchemaUrl = System.getenv().getOrDefault("MCP_SCHEMA_SERVER_URL", "http://localhost:8082");
        String gpSchemaEndpoint = System.getenv().getOrDefault("MCP_SCHEMA_SERVER_ENDPOINT", "/mcp");
        String gpSchemaEnabled = System.getenv().getOrDefault("MCP_SCHEMA_SERVER_ENABLED", "true");

        if ("true".equalsIgnoreCase(gpSchemaEnabled)) {
            McpConnectionProperties.ConnectionConfig config = new McpConnectionProperties.ConnectionConfig();
            config.setUrl(gpSchemaUrl);
            config.setEndpoint(gpSchemaEndpoint);
            config.setEnabled(true);
            connections.put("gp-schema", config);
            log.info("Configured MCP connection 'gp-schema' at {}{}", gpSchemaUrl, gpSchemaEndpoint);
        }

        if (connections.isEmpty()) {
            log.warn("⚠️  No MCP connections configured");
            log.info("✅ Application running without MCP servers");
            return;
        }

        log.info("Found {} configured MCP connection(s)", connections.size());

        connections.forEach((name, config) -> {
            McpConnectionStatus status = new McpConnectionStatus(
                    name,
                    config.getUrl(),
                    config.getEndpoint(),
                    config.isEnabled()
            );
            connectionStatuses.put(name, status);

            if (config.isEnabled()) {
                attemptConnection(name, config);
            } else {
                status.markDisabled();
                log.info("❌ Connection '{}' is disabled in configuration", name);
            }
        });

        log.info("=== MCP Connection Initialization Complete ===");
    }

    private void attemptConnection(String name, McpConnectionProperties.ConnectionConfig config) {
        try {
            log.info("🔄 Attempting to connect to MCP server '{}' at {}{}", name, config.getUrl(), config.getEndpoint());

            McpSyncClient client = buildClient(name, config);
            activeClients.put(name, client);

            McpConnectionStatus status = connectionStatuses.get(name);
            status.markSuccess();

            // Discover and update tools
            updateToolInformation(name, client, status);

            log.info("✅ Successfully connected to MCP server '{}' ({} tools)", name, status.getToolCount());

        } catch (Exception e) {
            log.warn("❌ Failed to connect to MCP server '{}': {}", name, e.getMessage());

            McpConnectionStatus status = connectionStatuses.get(name);
            status.markFailure(e.getMessage());

            // Check if retryable
            if (isRetryableException(e)) {
                scheduleRetry(name, config);
            } else {
                log.error("🚫 Non-retryable error for '{}': {}", name, e.getMessage());
            }
        }
    }

    private McpSyncClient buildClient(String name, McpConnectionProperties.ConnectionConfig config) {
        String baseUrl = config.getUrl();
        String endpoint = config.getEndpoint() != null ? config.getEndpoint() : "/mcp";

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(baseUrl)
                .endpoint(endpoint)
                .clientBuilder(HttpClient.newBuilder())
                .build();

        McpSchema.Implementation clientInfo = new McpSchema.Implementation(
                commonProperties.getName() + " - " + name,
                commonProperties.getVersion()
        );

        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(clientInfo)
                .requestTimeout(commonProperties.getRequestTimeout())
                .build();

        // Initialize the client
        client.initialize();

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

    private void scheduleRetry(String name, McpConnectionProperties.ConnectionConfig config) {
        McpConnectionStatus status = connectionStatuses.get(name);

        if (status.getRetryCount() >= MAX_RETRY_ATTEMPTS) {
            log.error("🚫 Max retry attempts ({}) exceeded for '{}'. Giving up.", MAX_RETRY_ATTEMPTS, name);
            return;
        }

        status.incrementRetryCount();
        Duration delay = calculateRetryDelay(status.getRetryCount());
        status.setNextRetryAt(Instant.now().plus(delay));

        log.info("⏱️  Scheduling retry {} for '{}' in {} seconds",
                status.getRetryCount(), name, delay.getSeconds());

        retryExecutor.schedule(() -> {
            log.info("🔄 Executing retry {} for '{}'", status.getRetryCount(), name);
            attemptConnection(name, config);
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
     * Get connection status by name.
     */
    public McpConnectionStatus getStatus(String name) {
        return connectionStatuses.get(name);
    }

    /**
     * Manually retry a failed connection.
     */
    public void retryConnection(String name) {
        McpConnectionStatus status = connectionStatuses.get(name);
        if (status == null) {
            throw new McpConnectionException("Connection '" + name + "' not found");
        }

        // Reconstruct config from status
        McpConnectionProperties.ConnectionConfig config = new McpConnectionProperties.ConnectionConfig();
        config.setUrl(status.getUrl());
        config.setEndpoint(status.getEndpoint());
        config.setEnabled(status.isEnabled());

        log.info("🔄 Manual retry requested for '{}'", name);
        status.setRetryCount(0); // Reset retry count for manual retry
        attemptConnection(name, config);
    }

    /**
     * Disable a connection.
     */
    public void disableConnection(String name) {
        McpConnectionStatus status = connectionStatuses.get(name);
        if (status == null) {
            throw new McpConnectionException("Connection '" + name + "' not found");
        }

        log.info("❌ Disabling connection '{}'", name);

        // Remove active client
        McpSyncClient client = activeClients.remove(name);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing client for '{}': {}", name, e.getMessage());
            }
        }

        status.markDisabled();
    }

    /**
     * Re-enable a disabled connection.
     */
    public void enableConnection(String name) {
        McpConnectionStatus status = connectionStatuses.get(name);
        if (status == null) {
            throw new McpConnectionException("Connection '" + name + "' not found");
        }

        // Reconstruct config from status
        McpConnectionProperties.ConnectionConfig config = new McpConnectionProperties.ConnectionConfig();
        config.setUrl(status.getUrl());
        config.setEndpoint(status.getEndpoint());
        config.setEnabled(true);

        log.info("✅ Enabling connection '{}'", name);
        status.setEnabled(true);
        status.setRetryCount(0);
        attemptConnection(name, config);
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
