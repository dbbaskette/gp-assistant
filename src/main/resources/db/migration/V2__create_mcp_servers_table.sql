-- MCP Servers Configuration Table
-- Stores MCP server connection details with encrypted API keys

CREATE TABLE IF NOT EXISTS mcp_servers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Connection details
    name VARCHAR(255) NOT NULL,
    url VARCHAR(512) NOT NULL,
    api_key_encrypted TEXT NOT NULL,  -- AES-256-GCM encrypted

    -- Status tracking
    is_active BOOLEAN DEFAULT false,
    status VARCHAR(50) DEFAULT 'disconnected',  -- disconnected, connected, error
    status_message TEXT,
    tool_count INTEGER DEFAULT 0,

    -- Metadata
    description TEXT,
    last_tested_at TIMESTAMP,
    last_connected_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- Greenplum-compatible constraint: name must share column with PRIMARY KEY
    UNIQUE(id, name)
);

-- Indexes
CREATE INDEX idx_mcp_servers_name ON mcp_servers(name);
CREATE INDEX idx_mcp_servers_active ON mcp_servers(is_active) WHERE is_active = true;
CREATE INDEX idx_mcp_servers_status ON mcp_servers(status);
CREATE INDEX idx_mcp_servers_created ON mcp_servers(created_at DESC);

-- Comments
COMMENT ON TABLE mcp_servers IS 'MCP server configurations for dynamic tool integration';
COMMENT ON COLUMN mcp_servers.api_key_encrypted IS 'AES-256-GCM encrypted API key (format: gpmcp_live_...)';
COMMENT ON COLUMN mcp_servers.is_active IS 'Only one server can be active at a time - enforced in application layer';
COMMENT ON COLUMN mcp_servers.status IS 'Current connection status: disconnected, connected, error';
COMMENT ON COLUMN mcp_servers.tool_count IS 'Number of tools discovered from this server';

-- Note: Single active server constraint is enforced in application layer via McpServerRepository.setActive()
-- Greenplum requires UNIQUE constraints to share columns with PRIMARY KEY, so we use transactional logic instead
