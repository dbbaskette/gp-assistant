package com.baskettecase.gpassistant.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-memory model for tracking MCP connection status.
 * Not persisted - resets on application restart.
 */
public class McpConnectionStatus {

    public enum Status {
        CONNECTING,  // Initial connection attempt in progress
        ACTIVE,      // Successfully connected
        ERROR,       // Connection failed
        DISABLED     // Manually disabled
    }

    private final String name;
    private final String url;
    private final String endpoint;
    private boolean enabled;

    private Status status = Status.CONNECTING;
    private Instant lastSuccessAt;
    private Instant lastFailureAt;
    private String lastErrorMessage;

    // Retry tracking
    private int retryCount = 0;
    private Instant nextRetryAt;

    // Tool tracking
    private List<String> availableTools = new ArrayList<>();
    private int toolCount = 0;

    public McpConnectionStatus(String name, String url, String endpoint, boolean enabled) {
        this.name = name;
        this.url = url;
        this.endpoint = endpoint;
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public void setLastFailureAt(Instant lastFailureAt) {
        this.lastFailureAt = lastFailureAt;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public List<String> getAvailableTools() {
        return availableTools;
    }

    public void setAvailableTools(List<String> availableTools) {
        this.availableTools = availableTools;
    }

    public int getToolCount() {
        return toolCount;
    }

    public void setToolCount(int toolCount) {
        this.toolCount = toolCount;
    }

    public void markSuccess() {
        this.status = Status.ACTIVE;
        this.lastSuccessAt = Instant.now();
        this.lastErrorMessage = null;
        this.retryCount = 0;
        this.nextRetryAt = null;
    }

    public void markFailure(String errorMessage) {
        this.status = Status.ERROR;
        this.lastFailureAt = Instant.now();
        this.lastErrorMessage = errorMessage;
    }

    public void markDisabled() {
        this.status = Status.DISABLED;
        this.enabled = false;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public void updateTools(List<String> tools) {
        this.availableTools = tools != null ? new ArrayList<>(tools) : new ArrayList<>();
        this.toolCount = this.availableTools.size();
    }

    public String getFullUrl() {
        return url + endpoint;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        McpConnectionStatus that = (McpConnectionStatus) o;
        return enabled == that.enabled &&
                retryCount == that.retryCount &&
                toolCount == that.toolCount &&
                Objects.equals(name, that.name) &&
                Objects.equals(url, that.url) &&
                Objects.equals(endpoint, that.endpoint) &&
                status == that.status &&
                Objects.equals(lastSuccessAt, that.lastSuccessAt) &&
                Objects.equals(lastFailureAt, that.lastFailureAt) &&
                Objects.equals(lastErrorMessage, that.lastErrorMessage) &&
                Objects.equals(nextRetryAt, that.nextRetryAt) &&
                Objects.equals(availableTools, that.availableTools);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, url, endpoint, enabled, status, lastSuccessAt,
                lastFailureAt, lastErrorMessage, retryCount, nextRetryAt,
                availableTools, toolCount);
    }

    @Override
    public String toString() {
        return "McpConnectionStatus{" +
                "name='" + name + '\'' +
                ", url='" + url + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", enabled=" + enabled +
                ", status=" + status +
                ", lastSuccessAt=" + lastSuccessAt +
                ", lastFailureAt=" + lastFailureAt +
                ", lastErrorMessage='" + lastErrorMessage + '\'' +
                ", retryCount=" + retryCount +
                ", nextRetryAt=" + nextRetryAt +
                ", availableTools=" + availableTools +
                ", toolCount=" + toolCount +
                '}';
    }
}
