package com.baskettecase.gpassistant.controller;

import com.baskettecase.gpassistant.domain.McpServerEntity;
import com.baskettecase.gpassistant.service.McpServerConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST API for managing MCP server configurations.
 * Provides endpoints for CRUD operations, connection testing, and activation.
 */
@RestController
@RequestMapping("/api/mcp/servers")
public class McpServerController {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);

    private final McpServerConfigService configService;

    public McpServerController(McpServerConfigService configService) {
        this.configService = configService;
    }

    /**
     * Get all MCP servers.
     * Returns sanitized data (no decrypted API keys).
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllServers() {
        List<McpServerEntity> servers = configService.getAllServers();

        List<Map<String, Object>> response = servers.stream()
                .map(this::toSafeDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get a single MCP server by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getServer(@PathVariable UUID id) {
        return configService.getServer(id)
                .map(server -> ResponseEntity.ok(toSafeDto(server)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get the currently active MCP server.
     */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveServer() {
        return configService.getActiveServer()
                .map(server -> ResponseEntity.ok(toSafeDto(server)))
                .orElse(ResponseEntity.ok(Map.of("active", false)));
    }

    /**
     * Create a new MCP server.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createServer(@RequestBody CreateServerRequest request) {
        try {
            McpServerEntity server = configService.createServer(
                    request.name(),
                    request.url(),
                    request.apiKey(),
                    request.description()
            );

            log.info("Created MCP server: {}", server.getName());

            return ResponseEntity.status(HttpStatus.CREATED).body(toSafeDto(server));

        } catch (IllegalArgumentException e) {
            log.warn("Failed to create MCP server: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update an existing MCP server.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateServer(
            @PathVariable UUID id,
            @RequestBody UpdateServerRequest request) {

        try {
            McpServerEntity server = configService.updateServer(
                    id,
                    request.name(),
                    request.url(),
                    request.apiKey(),
                    request.description()
            );

            log.info("Updated MCP server: {}", server.getName());

            return ResponseEntity.ok(toSafeDto(server));

        } catch (IllegalArgumentException e) {
            log.warn("Failed to update MCP server: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete an MCP server.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteServer(@PathVariable UUID id) {
        try {
            configService.deleteServer(id);
            log.info("Deleted MCP server: {}", id);

            return ResponseEntity.ok(Map.of("success", true, "message", "Server deleted"));

        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Failed to delete MCP server: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Set a server as active.
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activateServer(@PathVariable UUID id) {
        try {
            configService.setActiveServer(id);
            log.info("Activated MCP server: {}", id);

            return ResponseEntity.ok(Map.of("success", true, "message", "Server activated"));

        } catch (IllegalArgumentException e) {
            log.warn("Failed to activate MCP server: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Deactivate all MCP servers.
     */
    @PostMapping("/deactivate-all")
    public ResponseEntity<Map<String, Object>> deactivateAll() {
        configService.deactivateAll();
        log.info("Deactivated all MCP servers");

        return ResponseEntity.ok(Map.of("success", true, "message", "All servers deactivated"));
    }

    /**
     * Test connection to an MCP server.
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable UUID id) {
        try {
            McpServerConfigService.TestConnectionResult result = configService.testConnection(id);

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            response.put("toolCount", result.getToolCount());

            if (result.isSuccess()) {
                response.put("tools", result.getTools().stream()
                        .map(tool -> Map.of(
                                "name", tool.name(),
                                "description", tool.description() != null ? tool.description() : ""
                        ))
                        .collect(Collectors.toList()));
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Connection test failed", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Connection test failed: " + e.getMessage(),
                    "toolCount", 0
            ));
        }
    }

    /**
     * Test connection with credentials (before saving).
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnectionWithCredentials(
            @RequestBody TestConnectionRequest request) {

        try {
            McpServerConfigService.TestConnectionResult result =
                    configService.testConnection(request.url(), request.apiKey());

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            response.put("toolCount", result.getToolCount());

            if (result.isSuccess()) {
                response.put("tools", result.getTools().stream()
                        .map(tool -> Map.of(
                                "name", tool.name(),
                                "description", tool.description() != null ? tool.description() : ""
                        ))
                        .collect(Collectors.toList()));
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Connection test failed", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Connection test failed: " + e.getMessage(),
                    "toolCount", 0
            ));
        }
    }

    /**
     * Convert entity to safe DTO (no decrypted API key).
     */
    private Map<String, Object> toSafeDto(McpServerEntity server) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", server.getId().toString());
        dto.put("name", server.getName());
        dto.put("url", server.getUrl());
        dto.put("description", server.getDescription());
        dto.put("active", server.isActive());
        dto.put("status", server.getStatus());
        dto.put("statusMessage", server.getStatusMessage());
        dto.put("toolCount", server.getToolCount());
        dto.put("lastTestedAt", server.getLastTestedAt() != null ? server.getLastTestedAt().toString() : null);
        dto.put("lastConnectedAt", server.getLastConnectedAt() != null ? server.getLastConnectedAt().toString() : null);
        dto.put("createdAt", server.getCreatedAt() != null ? server.getCreatedAt().toString() : null);
        dto.put("updatedAt", server.getUpdatedAt() != null ? server.getUpdatedAt().toString() : null);

        // Mask API key (show only prefix)
        dto.put("hasApiKey", server.getApiKeyEncrypted() != null && !server.getApiKeyEncrypted().isEmpty());

        return dto;
    }

    // Request DTOs
    record CreateServerRequest(String name, String url, String apiKey, String description) {}
    record UpdateServerRequest(String name, String url, String apiKey, String description) {}
    record TestConnectionRequest(String url, String apiKey) {}
}
