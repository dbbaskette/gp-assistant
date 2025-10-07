package com.baskettecase.gpassistant.controller;

import com.baskettecase.gpassistant.domain.McpServerEntity;
import com.baskettecase.gpassistant.repository.McpServerRepository;
import com.baskettecase.gpassistant.service.EncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller that proxies database and schema discovery to the gp-mcp-server.
 * Uses API key authentication from the active MCP server configuration in database.
 */
@RestController
@RequestMapping("/api/mcp")
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true")
public class McpDatabaseController {

    private static final Logger log = LoggerFactory.getLogger(McpDatabaseController.class);

    private final McpServerRepository mcpServerRepository;
    private final EncryptionService encryptionService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public McpDatabaseController(
            McpServerRepository mcpServerRepository,
            EncryptionService encryptionService) {
        this.mcpServerRepository = mcpServerRepository;
        this.encryptionService = encryptionService;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get available databases from the MCP server.
     * Proxies to GET /api/v1/databases on the gp-mcp-server.
     *
     * @param customApiKey API key from frontend (X-GP-MCP-API-Key header)
     * @param customUrl Server URL from frontend (X-GP-MCP-URL header)
     * @return JSON response with defaultDatabase, allowedDatabases, and targetHost
     */
    @GetMapping("/databases")
    public ResponseEntity<?> getDatabases(
            @RequestHeader(value = "X-GP-MCP-API-Key", required = false) String customApiKey,
            @RequestHeader(value = "X-GP-MCP-URL", required = false) String customUrl) {
        log.debug("Fetching databases from MCP server (customUrl={}, hasApiKey={})", customUrl, customApiKey != null);

        try {
            // Use custom settings from frontend if provided, otherwise use active server from database
            String baseUrl = customUrl;
            String apiKey = customApiKey;

            if (baseUrl == null || baseUrl.isEmpty()) {
                Optional<McpServerEntity> activeServerOpt = mcpServerRepository.findActive();
                if (activeServerOpt.isEmpty()) {
                    log.warn("No active MCP server configured and no custom URL provided");
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(Map.of("error", "MCP server not configured"));
                }
                McpServerEntity server = activeServerOpt.get();
                baseUrl = server.getUrl();
                if (apiKey == null) {
                    // gp-mcp-server expects raw API key without "Bearer " prefix
                    apiKey = encryptionService.decrypt(server.getApiKeyEncrypted());
                }
            }

            String apiUrl = baseUrl + "/api/v1/databases";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .GET();

            // Add X-API-Key header if API key is available
            // gp-mcp-server expects format: X-API-Key: {id}.{secret}
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.header("X-API-Key", apiKey);
                log.debug("Using API key authentication for MCP server");
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode jsonResponse = objectMapper.readTree(response.body());
                log.debug("Successfully fetched databases from MCP server");
                return ResponseEntity.ok(jsonResponse);
            } else {
                log.warn("MCP server returned status {}: {}", response.statusCode(), response.body());
                return ResponseEntity.status(response.statusCode())
                        .body(Map.of("error", "MCP server error: " + response.statusCode()));
            }

        } catch (Exception e) {
            log.error("Failed to fetch databases from MCP server", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to connect to MCP server: " + e.getMessage()));
        }
    }

    /**
     * Get schemas for a specific database from the MCP server.
     * Proxies to GET /api/v1/databases/{database}/schemas on the gp-mcp-server.
     *
     * @param database the database name (optional, uses default if not specified)
     * @param customApiKey API key from frontend (X-GP-MCP-API-Key header)
     * @param customUrl Server URL from frontend (X-GP-MCP-URL header)
     * @return JSON response with schemas array
     */
    @GetMapping("/schemas")
    public ResponseEntity<?> getSchemas(
            @RequestParam(required = false) String database,
            @RequestHeader(value = "X-GP-MCP-API-Key", required = false) String customApiKey,
            @RequestHeader(value = "X-GP-MCP-URL", required = false) String customUrl) {
        log.debug("Fetching schemas from MCP server for database: {}", database);

        try {
            // Use custom settings from frontend if provided, otherwise use active server from database
            String baseUrl = customUrl;
            String apiKey = customApiKey;

            if (baseUrl == null || baseUrl.isEmpty()) {
                Optional<McpServerEntity> activeServerOpt = mcpServerRepository.findActive();
                if (activeServerOpt.isEmpty()) {
                    log.warn("No active MCP server configured and no custom URL provided");
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(Map.of("error", "MCP server not configured"));
                }
                McpServerEntity server = activeServerOpt.get();
                baseUrl = server.getUrl();
                if (apiKey == null) {
                    // gp-mcp-server expects raw API key without "Bearer " prefix
                    apiKey = encryptionService.decrypt(server.getApiKeyEncrypted());
                }
            }

            // If database not specified, get default from /api/v1/databases first
            String targetDatabase = database;
            if (targetDatabase == null || targetDatabase.isEmpty()) {
                try {
                    String dbApiUrl = baseUrl + "/api/v1/databases";
                    HttpRequest.Builder dbRequestBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(dbApiUrl))
                            .GET();

                    if (apiKey != null && !apiKey.isEmpty()) {
                        dbRequestBuilder.header("Authorization", apiKey);
                    }

                    HttpRequest dbRequest = dbRequestBuilder.build();
                    HttpResponse<String> dbResponse = httpClient.send(dbRequest, HttpResponse.BodyHandlers.ofString());

                    if (dbResponse.statusCode() == 200) {
                        JsonNode dbJson = objectMapper.readTree(dbResponse.body());
                        targetDatabase = dbJson.get("defaultDatabase").asText();
                        log.debug("Using default database: {}", targetDatabase);
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch default database, will try without database parameter", e);
                }
            }

            String apiUrl = baseUrl + "/api/v1/databases/" +
                    (targetDatabase != null ? targetDatabase : "default") + "/schemas";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .GET();

            // Add Authorization header if API key is available
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.header("Authorization", apiKey);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode jsonResponse = objectMapper.readTree(response.body());
                log.debug("Successfully fetched schemas from MCP server");
                return ResponseEntity.ok(jsonResponse);
            } else {
                log.warn("MCP server returned status {}: {}", response.statusCode(), response.body());
                return ResponseEntity.status(response.statusCode())
                        .body(Map.of("error", "MCP server error: " + response.statusCode()));
            }

        } catch (Exception e) {
            log.error("Failed to fetch schemas from MCP server", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to connect to MCP server: " + e.getMessage()));
        }
    }
}
