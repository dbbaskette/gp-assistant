package com.baskettecase.gpassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration properties for MCP server connections.
 * Supports multiple MCP server configurations from application.yaml.
 */
@Component
@ConfigurationProperties(prefix = "spring.ai.mcp.client.streamable-http")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true")
public class McpConnectionProperties {

    /**
     * Map of connection names to their configurations.
     * Example:
     * spring.ai.mcp.client.streamable-http.connections:
     *   gp-schema:
     *     url: http://localhost:8082
     *     endpoint: /mcp
     *     enabled: true
     */
    private Map<String, ConnectionConfig> connections = new HashMap<>();

    public Map<String, ConnectionConfig> getConnections() {
        return connections;
    }

    public void setConnections(Map<String, ConnectionConfig> connections) {
        this.connections = connections;
    }

    public static class ConnectionConfig {
        private String url;
        private String endpoint = "/mcp";
        private boolean enabled = true;
        private Map<String, String> headers = new HashMap<>();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ConnectionConfig that = (ConnectionConfig) o;
            return enabled == that.enabled &&
                    Objects.equals(url, that.url) &&
                    Objects.equals(endpoint, that.endpoint) &&
                    Objects.equals(headers, that.headers);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url, endpoint, enabled, headers);
        }

        @Override
        public String toString() {
            return "ConnectionConfig{" +
                    "url='" + url + '\'' +
                    ", endpoint='" + endpoint + '\'' +
                    ", enabled=" + enabled +
                    ", headers=" + headers +
                    '}';
        }
    }
}
