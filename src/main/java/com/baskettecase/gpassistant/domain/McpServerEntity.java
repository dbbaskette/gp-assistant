package com.baskettecase.gpassistant.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an MCP server configuration.
 * Stores connection details with encrypted API keys.
 */
public class McpServerEntity {

    private UUID id;
    private String name;
    private String url;
    private String apiKeyEncrypted;  // AES-256-GCM encrypted
    private boolean active;
    private String status;  // disconnected, connected, error
    private String statusMessage;
    private int toolCount;
    private String description;
    private Instant lastTestedAt;
    private Instant lastConnectedAt;
    private Instant createdAt;
    private Instant updatedAt;

    // Constructor
    public McpServerEntity() {
        this.id = UUID.randomUUID();
        this.status = "disconnected";
        this.toolCount = 0;
        this.active = false;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // Builder pattern for convenience
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final McpServerEntity entity = new McpServerEntity();

        public Builder id(UUID id) {
            entity.id = id;
            return this;
        }

        public Builder name(String name) {
            entity.name = name;
            return this;
        }

        public Builder url(String url) {
            entity.url = url;
            return this;
        }

        public Builder apiKeyEncrypted(String apiKeyEncrypted) {
            entity.apiKeyEncrypted = apiKeyEncrypted;
            return this;
        }

        public Builder active(boolean active) {
            entity.active = active;
            return this;
        }

        public Builder status(String status) {
            entity.status = status;
            return this;
        }

        public Builder statusMessage(String statusMessage) {
            entity.statusMessage = statusMessage;
            return this;
        }

        public Builder toolCount(int toolCount) {
            entity.toolCount = toolCount;
            return this;
        }

        public Builder description(String description) {
            entity.description = description;
            return this;
        }

        public Builder lastTestedAt(Instant lastTestedAt) {
            entity.lastTestedAt = lastTestedAt;
            return this;
        }

        public Builder lastConnectedAt(Instant lastConnectedAt) {
            entity.lastConnectedAt = lastConnectedAt;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            entity.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            entity.updatedAt = updatedAt;
            return this;
        }

        public McpServerEntity build() {
            return entity;
        }
    }

    // Status enum for type safety
    public enum Status {
        DISCONNECTED("disconnected"),
        CONNECTED("connected"),
        ERROR("error");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Status fromValue(String value) {
            for (Status status : values()) {
                if (status.value.equals(value)) {
                    return status;
                }
            }
            return DISCONNECTED;
        }
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    public void setApiKeyEncrypted(String apiKeyEncrypted) {
        this.apiKeyEncrypted = apiKeyEncrypted;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public int getToolCount() {
        return toolCount;
    }

    public void setToolCount(int toolCount) {
        this.toolCount = toolCount;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getLastTestedAt() {
        return lastTestedAt;
    }

    public void setLastTestedAt(Instant lastTestedAt) {
        this.lastTestedAt = lastTestedAt;
    }

    public Instant getLastConnectedAt() {
        return lastConnectedAt;
    }

    public void setLastConnectedAt(Instant lastConnectedAt) {
        this.lastConnectedAt = lastConnectedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "McpServerEntity{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", url='" + url + '\'' +
                ", active=" + active +
                ", status='" + status + '\'' +
                ", toolCount=" + toolCount +
                '}';
    }
}
