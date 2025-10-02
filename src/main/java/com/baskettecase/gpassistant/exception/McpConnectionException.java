package com.baskettecase.gpassistant.exception;

/**
 * Exception thrown when MCP server connection fails.
 */
public class McpConnectionException extends RuntimeException {

    public McpConnectionException(String message) {
        super(message);
    }

    public McpConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
