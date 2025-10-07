package com.baskettecase.gpassistant.repository;

import com.baskettecase.gpassistant.domain.McpServerEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for MCP server configurations.
 * Uses Spring JDBC Template for database operations.
 */
@Repository
public class McpServerRepository {

    private final JdbcTemplate jdbcTemplate;

    public McpServerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<McpServerEntity> rowMapper = (rs, rowNum) -> McpServerEntity.builder()
            .id(UUID.fromString(rs.getString("id")))
            .name(rs.getString("name"))
            .url(rs.getString("url"))
            .apiKeyEncrypted(rs.getString("api_key_encrypted"))
            .active(rs.getBoolean("is_active"))
            .status(rs.getString("status"))
            .statusMessage(rs.getString("status_message"))
            .toolCount(rs.getInt("tool_count"))
            .description(rs.getString("description"))
            .lastTestedAt(toInstant(rs.getTimestamp("last_tested_at")))
            .lastConnectedAt(toInstant(rs.getTimestamp("last_connected_at")))
            .createdAt(toInstant(rs.getTimestamp("created_at")))
            .updatedAt(toInstant(rs.getTimestamp("updated_at")))
            .build();

    /**
     * Save a new MCP server configuration.
     */
    public McpServerEntity save(McpServerEntity entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }

        String sql = """
            INSERT INTO mcp_servers (
                id, name, url, api_key_encrypted, is_active, status, status_message,
                tool_count, description, last_tested_at, last_connected_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                url = EXCLUDED.url,
                api_key_encrypted = EXCLUDED.api_key_encrypted,
                is_active = EXCLUDED.is_active,
                status = EXCLUDED.status,
                status_message = EXCLUDED.status_message,
                tool_count = EXCLUDED.tool_count,
                description = EXCLUDED.description,
                last_tested_at = EXCLUDED.last_tested_at,
                last_connected_at = EXCLUDED.last_connected_at,
                updated_at = EXCLUDED.updated_at
            """;

        jdbcTemplate.update(sql,
                entity.getId(),
                entity.getName(),
                entity.getUrl(),
                entity.getApiKeyEncrypted(),
                entity.isActive(),
                entity.getStatus(),
                entity.getStatusMessage(),
                entity.getToolCount(),
                entity.getDescription(),
                toTimestamp(entity.getLastTestedAt()),
                toTimestamp(entity.getLastConnectedAt()),
                toTimestamp(entity.getCreatedAt()),
                toTimestamp(entity.getUpdatedAt())
        );

        return entity;
    }

    /**
     * Find all MCP servers.
     */
    public List<McpServerEntity> findAll() {
        String sql = "SELECT * FROM mcp_servers ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    /**
     * Find MCP server by ID.
     */
    public Optional<McpServerEntity> findById(UUID id) {
        String sql = "SELECT * FROM mcp_servers WHERE id = ?";
        List<McpServerEntity> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find MCP server by name.
     */
    public Optional<McpServerEntity> findByName(String name) {
        String sql = "SELECT * FROM mcp_servers WHERE name = ?";
        List<McpServerEntity> results = jdbcTemplate.query(sql, rowMapper, name);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find the currently active MCP server.
     */
    public Optional<McpServerEntity> findActiveServer() {
        String sql = "SELECT * FROM mcp_servers WHERE is_active = true LIMIT 1";
        List<McpServerEntity> results = jdbcTemplate.query(sql, rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Alias for findActiveServer().
     */
    public Optional<McpServerEntity> findActive() {
        return findActiveServer();
    }

    /**
     * Set a server as active and deactivate all others.
     */
    public void setActive(UUID id) {
        jdbcTemplate.update("BEGIN TRANSACTION");
        try {
            // Deactivate all servers
            jdbcTemplate.update("UPDATE mcp_servers SET is_active = false, updated_at = ?",
                    Timestamp.from(Instant.now()));

            // Activate the selected server
            jdbcTemplate.update(
                    "UPDATE mcp_servers SET is_active = true, updated_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now()),
                    id
            );

            jdbcTemplate.update("COMMIT");
        } catch (Exception e) {
            jdbcTemplate.update("ROLLBACK");
            throw e;
        }
    }

    /**
     * Update connection status for a server.
     */
    public void updateStatus(UUID id, String status, String statusMessage, int toolCount) {
        String sql = """
            UPDATE mcp_servers
            SET status = ?,
                status_message = ?,
                tool_count = ?,
                last_connected_at = ?,
                updated_at = ?
            WHERE id = ?
            """;

        jdbcTemplate.update(sql,
                status,
                statusMessage,
                toolCount,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                id
        );
    }

    /**
     * Update last tested timestamp.
     */
    public void updateLastTested(UUID id) {
        String sql = "UPDATE mcp_servers SET last_tested_at = ?, updated_at = ? WHERE id = ?";
        Instant now = Instant.now();
        jdbcTemplate.update(sql, Timestamp.from(now), Timestamp.from(now), id);
    }

    /**
     * Update last connected timestamp.
     */
    public void updateLastConnectedAt(UUID id) {
        String sql = "UPDATE mcp_servers SET last_connected_at = ?, updated_at = ? WHERE id = ?";
        Instant now = Instant.now();
        jdbcTemplate.update(sql, Timestamp.from(now), Timestamp.from(now), id);
    }

    /**
     * Delete MCP server by ID.
     */
    public void deleteById(UUID id) {
        String sql = "DELETE FROM mcp_servers WHERE id = ?";
        jdbcTemplate.update(sql, id);
    }

    /**
     * Count total MCP servers.
     */
    public long count() {
        String sql = "SELECT COUNT(*) FROM mcp_servers";
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * Check if a server name already exists (for unique constraint).
     */
    public boolean existsByName(String name) {
        String sql = "SELECT COUNT(*) FROM mcp_servers WHERE name = ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, name);
        return count != null && count > 0;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}
