package com.baskettecase.gpassistant.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for database and schema listing.
 */
@RestController
@RequestMapping("/api/database")
public class DatabaseController {

    private static final Logger log = LoggerFactory.getLogger(DatabaseController.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/list")
    public List<Map<String, Object>> listDatabases() {
        log.debug("Listing databases");
        try {
            return jdbcTemplate.queryForList(
                    "SELECT datname as name FROM pg_database WHERE datistemplate = false ORDER BY datname"
            );
        } catch (Exception e) {
            log.error("Failed to list databases", e);
            throw new RuntimeException("Failed to list databases", e);
        }
    }

    @GetMapping("/schemas")
    public List<Map<String, Object>> listSchemas(@RequestParam(required = false) String database) {
        log.debug("Listing schemas for database: {}", database);
        try {
            // Note: We can't switch databases in JDBC, so this lists schemas in current database
            return jdbcTemplate.queryForList(
                    "SELECT schema_name as name FROM information_schema.schemata " +
                    "WHERE schema_name NOT IN ('information_schema', 'pg_catalog', 'pg_toast', 'gp_toolkit') " +
                    "ORDER BY schema_name"
            );
        } catch (Exception e) {
            log.error("Failed to list schemas", e);
            throw new RuntimeException("Failed to list schemas", e);
        }
    }
}
