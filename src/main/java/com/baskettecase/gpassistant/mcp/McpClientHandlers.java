package com.baskettecase.gpassistant.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP Client event handlers and startup logging.
 * This will be activated when MCP client is enabled in application.yaml
 */
@Component
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true")
public class McpClientHandlers {

    private static final Logger log = LoggerFactory.getLogger(McpClientHandlers.class);

    private final ApplicationContext applicationContext;
    private ToolCallbackProvider toolCallbackProvider;

    @Value("${spring.ai.mcp.client.type:SYNC}")
    private String mcpType;

    @Value("${spring.ai.mcp.client.request-timeout:30s}")
    private String requestTimeout;

    @Value("${spring.ai.mcp.client.streamable-http.connections.gp-schema.url:http://localhost:8082}")
    private String mcpServerUrl;

    // Cache for tool names to avoid repeated reflection
    private final AtomicReference<List<String>> cachedToolNames = new AtomicReference<>();
    private volatile boolean toolNamesExtracted = false;

    public McpClientHandlers(ApplicationContext applicationContext,
                           @Autowired(required = false) ToolCallbackProvider toolCallbackProvider) {
        this.applicationContext = applicationContext;
        this.toolCallbackProvider = toolCallbackProvider;
        log.info("McpClientHandlers initialized (ToolCallbackProvider: {})",
                toolCallbackProvider != null ? "available" : "not available");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("=== MCP Client Configuration ===");
        log.info("MCP Client Status: ENABLED");
        log.info("MCP Client Type: {}", mcpType);
        log.info("Request Timeout: {}", requestTimeout);

        if (mcpServerUrl != null && !mcpServerUrl.isEmpty()) {
            log.info("MCP Server URL: {}", mcpServerUrl);
            logMcpServerStatus();
        } else {
            log.warn("No MCP server URLs configured in spring.ai.mcp.client.streamable-http.connections");
        }

        logMcpBeans();
        logMcpTools();
        log.info("=== End MCP Configuration ===");
    }

    private void logMcpServerStatus() {
        try {
            log.info("MCP server configured at: {}", mcpServerUrl);

            // Report tool discovery status using proper Spring AI approach
            if (toolCallbackProvider != null) {
                log.info("ToolCallbackProvider available: {}", toolCallbackProvider.getClass().getSimpleName());
            } else {
                log.warn("No ToolCallbackProvider found - MCP server may not be running");
            }
        } catch (Exception e) {
            log.error("Error checking MCP server status: {}", e.getMessage());
        }
    }

    private void logMcpBeans() {
        try {
            String[] beanNames = applicationContext.getBeanDefinitionNames();
            long mcpBeanCount = java.util.Arrays.stream(beanNames)
                    .filter(name -> name.toLowerCase().contains("mcp"))
                    .count();

            if (mcpBeanCount == 0) {
                log.warn("No MCP beans found. MCP client may not be fully initialized yet.");
            } else {
                log.info("MCP client initialized with {} Spring beans", mcpBeanCount);
            }
        } catch (Exception e) {
            log.error("Error while scanning for MCP beans: {}", e.getMessage());
        }
    }

    private void logMcpTools() {
        try {
            log.info("MCP Tools Discovery:");

            if (toolCallbackProvider != null) {
                try {
                    // Cache tool names for fast reporting
                    cacheToolNames(toolCallbackProvider);
                    reportCachedToolNames();

                    log.info("✅ MCP Tools: REGISTERED");
                } catch (Exception e) {
                    log.warn("⚠️  Error inspecting tool provider: {}", e.getMessage());
                    log.info("🛠️  MCP Tools: REGISTERED but not inspectable");
                }
            } else {
                log.warn("❌ MCP Tools: NONE DISCOVERED");
                log.warn("🔍 Possible issues:");
                log.warn("   - MCP server not running at {}", mcpServerUrl);
                log.warn("   - MCP server not exposing tools correctly");
                log.warn("   - Network connectivity issues");
                log.warn("   - MCP client configuration problems");
            }

        } catch (Exception e) {
            log.error("Error in MCP tools discovery: {}", e.getMessage());
        }
    }

    /**
     * Cache tool names from the ToolCallbackProvider for fast subsequent access
     */
    private void cacheToolNames(ToolCallbackProvider toolProvider) {
        if (toolNamesExtracted) {
            return; // Already cached
        }

        try {
            List<String> toolNames = extractToolNamesFromProvider(toolProvider);
            cachedToolNames.set(toolNames);
            toolNamesExtracted = true;
        } catch (Exception e) {
            log.debug("Could not cache tool names: {}", e.getMessage());
            toolNamesExtracted = true; // Mark as attempted to avoid retries
        }
    }

    /**
     * Report cached tool names (fast operation)
     */
    private void reportCachedToolNames() {
        List<String> toolNames = cachedToolNames.get();
        if (toolNames == null) {
            log.info("🔧 Registered Tools: Not cached (extraction failed)");
            return;
        }

        if (toolNames.isEmpty()) {
            log.info("🔧 Registered Tools: NONE");
        } else {
            log.info("🔧 Registered Tools ({} total):", toolNames.size());
            int index = 1;
            for (String toolName : toolNames) {
                log.info("   {}. 🛠️  {}", index++, toolName);
            }
        }
    }

    /**
     * Extract tool names from provider (simplified from working example)
     */
    private List<String> extractToolNamesFromProvider(ToolCallbackProvider toolProvider) {
        List<String> toolNames = new java.util.ArrayList<>();

        try {
            // Try different possible method names to get the callbacks
            String[] methodNames = {"getCallbacks", "getToolCallbacks", "getAllCallbacks", "getTools"};

            for (String methodName : methodNames) {
                try {
                    java.lang.reflect.Method method = toolProvider.getClass().getMethod(methodName);
                    Object callbacks = method.invoke(toolProvider);

                    if (callbacks instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, ?> toolMap = (java.util.Map<String, ?>) callbacks;
                        toolNames.addAll(toolMap.keySet());
                        return toolNames;
                    }

                    if (callbacks instanceof java.util.Collection) {
                        java.util.Collection<?> toolCollection = (java.util.Collection<?>) callbacks;
                        for (Object tool : toolCollection) {
                            String toolName = extractToolName(tool);
                            if (toolName != null) {
                                toolNames.add(toolName);
                            }
                        }
                        return toolNames;
                    }
                    break;
                } catch (NoSuchMethodException e) {
                    // Try next method name
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract tool names via reflection: {}", e.getMessage());
        }

        return toolNames;
    }

    /**
     * Extract tool name from a tool object (simplified from working example)
     */
    private String extractToolName(Object tool) {
        if (tool == null) {
            return null;
        }

        try {
            // Try getName method
            java.lang.reflect.Method getNameMethod = tool.getClass().getMethod("getName");
            Object name = getNameMethod.invoke(tool);
            if (name != null) {
                return name.toString();
            }
        } catch (Exception e) {
            // Try name field
            try {
                java.lang.reflect.Field nameField = tool.getClass().getDeclaredField("name");
                nameField.setAccessible(true);
                Object name = nameField.get(tool);
                if (name != null) {
                    return name.toString();
                }
            } catch (Exception e2) {
                // Parse toString for name patterns
                String toolString = tool.toString();
                if (toolString.contains("name=")) {
                    String[] parts = toolString.split("name=");
                    if (parts.length > 1) {
                        String namePart = parts[1].split("[,\\]}]")[0].trim();
                        return namePart.replaceAll("[\"']", "");
                    }
                }
                return tool.getClass().getSimpleName();
            }
        }
        return null;
    }

    // TODO: Add MCP client event handlers when MCP servers are configured
    // Example handlers for logging, sampling, etc.

}