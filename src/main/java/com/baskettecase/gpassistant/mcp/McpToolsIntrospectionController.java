package com.baskettecase.gpassistant.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * Controller for inspecting available MCP tools.
 * Only enabled when MCP client is configured.
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true")
public class McpToolsIntrospectionController {

    @GetMapping("/tools")
    public List<String> listTools() {
        log.debug("MCP tools introspection requested");
        // TODO: Implement when MCP servers are configured
        return Collections.emptyList();
    }
}