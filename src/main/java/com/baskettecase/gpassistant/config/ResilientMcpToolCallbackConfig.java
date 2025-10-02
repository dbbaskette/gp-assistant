package com.baskettecase.gpassistant.config;

import com.baskettecase.gpassistant.service.DynamicMcpClientManager;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Dynamic MCP tool callback provider that allows graceful startup even when MCP server is unavailable.
 * Integrates with DynamicMcpClientManager to get active clients only.
 * Based on spring-metal's DynamicMcpToolCallbackProvider pattern.
 */
@Component
@Primary
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true")
public class ResilientMcpToolCallbackConfig implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(ResilientMcpToolCallbackConfig.class);

    private final DynamicMcpClientManager clientManager;
    private final ObjectProvider<McpToolFilter> toolFilterProvider;
    private final ObjectProvider<McpToolNamePrefixGenerator> namePrefixProvider;
    private final ObjectProvider<ToolContextToMcpMetaConverter> metaConverterProvider;

    // Cache to prevent duplicate tool registration
    private volatile ToolCallback[] cachedToolCallbacks = null;
    private volatile long lastCacheUpdate = 0;
    private static final long CACHE_EXPIRY_MS = 30000; // 30 seconds

    public ResilientMcpToolCallbackConfig(
            DynamicMcpClientManager clientManager,
            ObjectProvider<McpToolFilter> toolFilterProvider,
            ObjectProvider<McpToolNamePrefixGenerator> namePrefixProvider,
            ObjectProvider<ToolContextToMcpMetaConverter> metaConverterProvider) {
        this.clientManager = clientManager;
        this.toolFilterProvider = toolFilterProvider;
        this.namePrefixProvider = namePrefixProvider;
        this.metaConverterProvider = metaConverterProvider;
        log.info("🔧 Initialized resilient MCP tool callback provider with DynamicMcpClientManager");
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        // Check cache first
        long currentTime = System.currentTimeMillis();
        if (cachedToolCallbacks != null && (currentTime - lastCacheUpdate) < CACHE_EXPIRY_MS) {
            log.debug("🔧 Returning cached tool callbacks ({} tools)", cachedToolCallbacks.length);
            return cachedToolCallbacks;
        }

        // Get active clients from DynamicMcpClientManager
        Collection<McpSyncClient> activeClients = clientManager.getActiveClients();
        List<McpSyncClient> clients = new ArrayList<>(activeClients);

        log.info("🔧 MCP Tool Discovery: Found {} active MCP sync client(s)", clients.size());

        if (clients.isEmpty()) {
            log.warn("⚠️  No MCP clients available - MCP server may not be running");
            log.info("✅ Application running without MCP tools");
            log.info("💡 MCP tools will be discovered dynamically when server becomes available");
            cachedToolCallbacks = new ToolCallback[0];
            lastCacheUpdate = currentTime;
            return cachedToolCallbacks;
        }

        try {
            SyncMcpToolCallbackProvider.Builder builder = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(clients);

            // Add optional filters and converters
            McpToolFilter filter = toolFilterProvider.getIfUnique(() -> (client, tool) -> true);
            if (filter != null) {
                builder.toolFilter(filter);
            }

            McpToolNamePrefixGenerator prefixGenerator = namePrefixProvider.getIfAvailable();
            if (prefixGenerator != null) {
                builder.toolNamePrefixGenerator(prefixGenerator);
            }

            ToolContextToMcpMetaConverter metaConverter = metaConverterProvider.getIfAvailable();
            if (metaConverter != null) {
                builder.toolContextToMcpMetaConverter(metaConverter);
            }

            ToolCallback[] toolCallbacks = builder.build().getToolCallbacks();

            log.info("🛠️  MCP Tool Registration Complete: {} tool(s) registered", toolCallbacks.length);

            // Log tool details
            for (int i = 0; i < toolCallbacks.length; i++) {
                ToolCallback tool = toolCallbacks[i];
                try {
                    if (tool.getToolDefinition() != null) {
                        log.info("🔧 Tool {}: Name='{}', Description='{}'",
                                i + 1,
                                tool.getToolDefinition().name(),
                                tool.getToolDefinition().description());
                    }
                } catch (Exception e) {
                    log.debug("Could not get tool definition for tool {}", i + 1);
                }
            }

            if (toolCallbacks.length == 0) {
                log.warn("⚠️  No tools discovered from {} MCP client(s)", clients.size());
            }

            // Update cache
            cachedToolCallbacks = toolCallbacks;
            lastCacheUpdate = currentTime;

            return toolCallbacks;
        } catch (Exception e) {
            log.error("❌ Failed to create MCP tool callbacks: {}", e.getMessage());
            log.info("✅ Returning empty tools to allow application to continue");
            cachedToolCallbacks = new ToolCallback[0];
            lastCacheUpdate = currentTime;
            return cachedToolCallbacks;
        }
    }
}
