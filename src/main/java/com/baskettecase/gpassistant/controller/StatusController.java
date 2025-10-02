package com.baskettecase.gpassistant.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for system status including MCP and database connectivity.
 */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private static final Logger log = LoggerFactory.getLogger(StatusController.class);

    private final JdbcTemplate jdbcTemplate;
    private final ToolCallbackProvider toolCallbackProvider;

    @Value("${spring.ai.mcp.client.enabled:false}")
    private boolean mcpEnabled;

    @Value("${spring.ai.mcp.client.streamable-http.connections.gp-schema.url:}")
    private String mcpServerUrl;

    @Value("${spring.datasource.url:}")
    private String databaseUrl;

    public StatusController(JdbcTemplate jdbcTemplate,
                          @Autowired(required = false) ToolCallbackProvider toolCallbackProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    @GetMapping
    public Map<String, Object> getStatus() {
        log.debug("Status check requested");

        Map<String, Object> status = new HashMap<>();
        status.put("application", "gp-assistant");
        status.put("timestamp", System.currentTimeMillis());

        // Database status
        status.put("database", getDatabaseStatus());

        // MCP status
        status.put("mcp", getMcpStatus());

        return status;
    }

    private Map<String, Object> getDatabaseStatus() {
        Map<String, Object> dbStatus = new HashMap<>();
        dbStatus.put("url", maskPassword(databaseUrl));

        try {
            // Try a simple query to verify connection
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);

            dbStatus.put("status", "connected");
            dbStatus.put("healthy", true);

            // Get database version
            try {
                String version = jdbcTemplate.queryForObject(
                        "SELECT version()", String.class);
                if (version != null && version.length() > 100) {
                    version = version.substring(0, 100) + "...";
                }
                dbStatus.put("version", version);
            } catch (Exception e) {
                log.debug("Could not retrieve database version", e);
            }

        } catch (Exception e) {
            dbStatus.put("status", "error");
            dbStatus.put("healthy", false);
            dbStatus.put("error", e.getMessage());
            log.error("Database health check failed", e);
        }

        return dbStatus;
    }

    private Map<String, Object> getMcpStatus() {
        Map<String, Object> mcpStatus = new HashMap<>();
        mcpStatus.put("enabled", mcpEnabled);

        if (!mcpEnabled) {
            mcpStatus.put("status", "disabled");
            mcpStatus.put("message", "MCP client is disabled. Set MCP_CLIENT_ENABLED=true to enable.");
            return mcpStatus;
        }

        mcpStatus.put("serverUrl", mcpServerUrl);
        mcpStatus.put("transport", "streamable-http");

        if (toolCallbackProvider != null) {
            mcpStatus.put("status", "connected");
            mcpStatus.put("healthy", true);

            try {
                // Try to extract tool count
                int toolCount = extractToolCount();
                mcpStatus.put("toolCount", toolCount);
                mcpStatus.put("message", "MCP server connected with " + toolCount + " tools available");
            } catch (Exception e) {
                mcpStatus.put("toolCount", "unknown");
                mcpStatus.put("message", "MCP server connected but tool count unavailable");
                log.debug("Could not extract tool count", e);
            }
        } else {
            mcpStatus.put("status", "disconnected");
            mcpStatus.put("healthy", false);
            mcpStatus.put("toolCount", 0);
            mcpStatus.put("message", "MCP server not connected or no tools available");
        }

        return mcpStatus;
    }

    private int extractToolCount() {
        if (toolCallbackProvider == null) {
            return 0;
        }

        try {
            String[] methodNames = {"getCallbacks", "getToolCallbacks", "getAllCallbacks"};

            for (String methodName : methodNames) {
                try {
                    java.lang.reflect.Method method = toolCallbackProvider.getClass().getMethod(methodName);
                    Object callbacks = method.invoke(toolCallbackProvider);

                    if (callbacks instanceof java.util.Map) {
                        return ((java.util.Map<?, ?>) callbacks).size();
                    }

                    if (callbacks instanceof java.util.Collection) {
                        return ((java.util.Collection<?>) callbacks).size();
                    }
                } catch (NoSuchMethodException e) {
                    // Try next method
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract tool count", e);
        }

        return 0;
    }

    private String maskPassword(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        // Mask password in JDBC URL
        return url.replaceAll("password=[^&;]+", "password=****");
    }
}
