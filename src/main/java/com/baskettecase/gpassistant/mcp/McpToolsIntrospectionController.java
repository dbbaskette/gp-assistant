package com.baskettecase.gpassistant.mcp;

import com.baskettecase.gpassistant.domain.McpConnectionStatus;
import com.baskettecase.gpassistant.exception.McpConnectionException;
import com.baskettecase.gpassistant.service.DynamicMcpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.*;

/**
 * Controller for inspecting available MCP tools and configuration.
 * Only enabled when MCP client is configured.
 */
@RestController
@RequestMapping("/api/mcp")
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true")
public class McpToolsIntrospectionController {

    private static final Logger log = LoggerFactory.getLogger(McpToolsIntrospectionController.class);

    private final ToolCallbackProvider toolCallbackProvider;
    private final DynamicMcpClientManager clientManager;

    @Value("${spring.ai.mcp.client.type:SYNC}")
    private String mcpType;

    public McpToolsIntrospectionController(
            @Autowired(required = false) ToolCallbackProvider toolCallbackProvider,
            DynamicMcpClientManager clientManager) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.clientManager = clientManager;
    }

    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        log.info("MCP tools introspection requested via REST API");

        Map<String, Object> response = new HashMap<>();
        response.put("mcpEnabled", true);
        response.put("clientType", mcpType);

        if (toolCallbackProvider != null) {
            try {
                List<String> toolNames = extractToolNames();
                response.put("availableTools", toolNames);
                response.put("toolCount", toolNames.size());
                response.put("status", "success");
                response.put("message", "Tools retrieved from Spring AI ToolCallbackProvider");
            } catch (Exception e) {
                response.put("availableTools", Collections.emptyList());
                response.put("toolCount", 0);
                response.put("status", "partial");
                response.put("message", "ToolCallbackProvider exists but tools not extractable: " + e.getMessage());
            }
        } else {
            response.put("availableTools", Collections.emptyList());
            response.put("toolCount", 0);
            response.put("status", "no_provider");
            response.put("message", "No ToolCallbackProvider available - MCP servers may not be running");
        }

        return response;
    }

    private List<String> extractToolNames() {
        List<String> toolNames = new java.util.ArrayList<>();

        try {
            // Try different possible method names to get the callbacks
            String[] methodNames = {"getCallbacks", "getToolCallbacks", "getAllCallbacks", "getTools"};

            for (String methodName : methodNames) {
                try {
                    java.lang.reflect.Method method = toolCallbackProvider.getClass().getMethod(methodName);
                    Object callbacks = method.invoke(toolCallbackProvider);

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

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        log.info("MCP status requested via REST API");

        Map<String, Object> response = new HashMap<>();
        response.put("mcpEnabled", true);
        response.put("clientType", mcpType);

        // Get all connection statuses
        Collection<McpConnectionStatus> statuses = clientManager.getAllStatuses();
        List<Map<String, Object>> connections = new ArrayList<>();

        int totalTools = 0;
        int activeCount = 0;

        for (McpConnectionStatus status : statuses) {
            Map<String, Object> connMap = new HashMap<>();
            connMap.put("name", status.getName());
            connMap.put("url", status.getFullUrl());
            connMap.put("status", status.getStatus().name());
            connMap.put("enabled", status.isEnabled());
            connMap.put("toolCount", status.getToolCount());
            connMap.put("tools", status.getAvailableTools());
            connMap.put("lastSuccessAt", status.getLastSuccessAt());
            connMap.put("lastFailureAt", status.getLastFailureAt());
            connMap.put("lastErrorMessage", status.getLastErrorMessage());
            connMap.put("retryCount", status.getRetryCount());
            connMap.put("nextRetryAt", status.getNextRetryAt());

            connections.add(connMap);

            if (status.getStatus() == McpConnectionStatus.Status.ACTIVE) {
                activeCount++;
                totalTools += status.getToolCount();
            }
        }

        response.put("connections", connections);
        response.put("totalConnections", connections.size());
        response.put("activeConnections", activeCount);
        response.put("totalTools", totalTools);
        response.put("message", String.format("%d active connection(s) with %d total tools", activeCount, totalTools));

        return response;
    }

    /**
     * Manually retry a failed connection
     */
    @PostMapping("/connections/{name}/retry")
    public ResponseEntity<Map<String, Object>> retryConnection(@PathVariable String name) {
        log.info("Manual retry requested for connection '{}'", name);

        try {
            clientManager.retryConnection(name);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Retry initiated for connection '" + name + "'"
            ));
        } catch (McpConnectionException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Disable a connection
     */
    @PostMapping("/connections/{name}/disable")
    public ResponseEntity<Map<String, Object>> disableConnection(@PathVariable String name) {
        log.info("Disable requested for connection '{}'", name);

        try {
            clientManager.disableConnection(name);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Connection '" + name + "' disabled"
            ));
        } catch (McpConnectionException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Enable a connection
     */
    @PostMapping("/connections/{name}/enable")
    public ResponseEntity<Map<String, Object>> enableConnection(@PathVariable String name) {
        log.info("Enable requested for connection '{}'", name);

        try {
            clientManager.enableConnection(name);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Connection '" + name + "' enabled and connecting"
            ));
        } catch (McpConnectionException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }
}