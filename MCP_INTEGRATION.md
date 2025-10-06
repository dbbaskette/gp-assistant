# MCP Server Integration Guide

## Overview

The gp-assistant application integrates with the gp-mcp-server using **API key-based authentication**. The gp-mcp-server now handles all database connections via encrypted API keys, eliminating the need to configure database credentials in gp-assistant for MCP tool access.

## Architecture

```
┌─────────────────┐         ┌──────────────────┐         ┌────────────────┐
│  gp-assistant   │         │  gp-mcp-server   │         │   Greenplum    │
│                 │         │                  │         │   Database(s)  │
│  Web UI         │────────▶│  REST API        │────────▶│                │
│  - Database     │  API    │  /api/v1/        │  JDBC   │  - analytics   │
│    Dropdown     │  Key    │  databases       │         │  - warehouse   │
│  - Schema       │         │  - Metadata      │         │  - staging     │
│    Dropdown     │         │  - Auth          │         │                │
│                 │         │                  │         │                │
│  MCP Client     │────────▶│  MCP Tools       │         │                │
│  - Chat AI      │  API    │  /mcp            │         │                │
│  - RAG          │  Key    │  - gp.runQuery   │         │                │
│                 │         │  - gp.listTables │         │                │
└─────────────────┘         └──────────────────┘         └────────────────┘
```

## Key Concepts

### API Key Authentication

- **Single Source of Truth**: Database credentials are stored encrypted in the gp-mcp-server API key
- **Security**: API keys use AES-256-GCM encryption
- **Permissions**: Each API key can be restricted to specific databases and schemas
- **Multi-Database**: One gp-assistant instance can access multiple databases via API key permissions

### Database Discovery

The gp-assistant UI dynamically discovers available databases and schemas from the gp-mcp-server:

1. **Databases**: Fetched from `/api/v1/databases` (returns allowed databases for the API key)
2. **Schemas**: Fetched from `/api/v1/databases/{db}/schemas` for each selected database

## Setup Instructions

### Step 1: Generate API Key on gp-mcp-server

1. Ensure gp-mcp-server is running:
   ```bash
   cd ../gp-mcp-server
   source .env
   ./run.sh
   ```

2. Visit the API Key Management UI:
   ```
   http://localhost:8082/admin/api-keys
   ```

3. Fill in the form:
   - **Target Host**: Your Greenplum host (e.g., `localhost:15432`)
   - **Username**: Database user (e.g., `gpadmin`)
   - **Password**: Database password
   - **Default Database**: Primary database (e.g., `gp_assistant`)
   - **Allowed Databases**: Comma-separated list of accessible databases
   - **Allowed Schemas**: Comma-separated list of accessible schemas (optional)
   - **Environment**: `live` (for production) or `test` (for testing)
   - **Description**: Purpose of this key (e.g., "gp-assistant production")

4. Click **"Test Connection"** to verify credentials

5. Click **"Generate API Key"**

6. **IMPORTANT**: Copy the API key immediately! It will only be shown once.
   - Format: `gpmcp_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`

### Step 2: Configure gp-assistant

1. Edit your `.env` file:
   ```bash
   cd ../gp-assistant
   nano .env
   ```

2. Add/update the MCP configuration section:
   ```bash
   # --- MCP Server Configuration ---
   MCP_SCHEMA_SERVER_URL=http://localhost:8082
   MCP_SCHEMA_SERVER_ENDPOINT=/mcp
   MCP_SCHEMA_SERVER_ENABLED=true
   MCP_SCHEMA_SERVER_API_KEY=gpmcp_live_your_actual_api_key_here
   ```

3. Ensure MCP client is enabled:
   ```bash
   SPRING_AI_MCP_CLIENT_ENABLED=true
   ```

### Step 3: Start gp-assistant

```bash
source .env
./mvnw spring-boot:run
```

Watch the logs for successful MCP connection:
```
✅ Successfully connected to MCP server 'gp-schema' (X tools)
```

### Step 4: Verify Integration

1. Open the web UI:
   ```
   http://localhost:8080
   ```

2. Check the status indicators:
   - 🌐 **API** - Should be green (application is running)
   - 💾 **DB** - Should be green (vector store database connected)
   - 🔌 **MCP** - Should be green (MCP server connected with tools)
   - 🧠 **Model** - Should be green (AI model ready)

3. Verify database dropdown:
   - Should populate with databases from your API key's `allowedDatabases`
   - Should auto-select the `defaultDatabase`

4. Verify schema dropdown:
   - Select a database from the dropdown
   - Schemas should populate automatically
   - Should auto-select `public` if available

## Database Dropdown Flow

### MCP Mode (Primary)

```javascript
// Frontend: chat.js
async function loadDatabases() {
  const response = await fetch('/api/mcp/databases');
  // Response: {
  //   defaultDatabase: "analytics",
  //   allowedDatabases: ["analytics", "warehouse", "staging"],
  //   targetHost: "greenplum.example.com:5432"
  // }
}
```

```java
// Backend: McpDatabaseController.java
@GetMapping("/mcp/databases")
public ResponseEntity<?> getDatabases() {
  // 1. Get API key from DynamicMcpClientManager
  // 2. Make HTTP GET to gp-mcp-server /api/v1/databases
  // 3. Forward response to frontend
}
```

```java
// gp-mcp-server: DatabaseDiscoveryController.java
@GetMapping("/api/v1/databases")
public ResponseEntity<?> getDatabases(@RequestHeader("Authorization") String apiKey) {
  // 1. Decrypt database credentials from API key
  // 2. Return allowed databases configuration
}
```

### Fallback Mode

If MCP server is unavailable, gp-assistant falls back to the local `DatabaseController`:

```javascript
async function loadDatabasesFallback() {
  const response = await fetch('/api/database/list');
  // Response: [{name: "gp_assistant"}, ...]
  // Uses gp-assistant's own database connection
}
```

## Troubleshooting

### MCP Status Indicator is Red

**Symptoms**: 🔌 MCP shows red dot, "MCP: not connected" tooltip

**Solutions**:

1. **Check gp-mcp-server is running**:
   ```bash
   curl http://localhost:8082/actuator/health
   ```

2. **Check API key is set**:
   ```bash
   echo $MCP_SCHEMA_SERVER_API_KEY
   ```

3. **Check gp-assistant logs**:
   ```
   ❌ Failed to connect to MCP server 'gp-schema': Connection refused
   ```

4. **Verify API key authentication**:
   ```bash
   curl -H "Authorization: $MCP_SCHEMA_SERVER_API_KEY" http://localhost:8082/api/v1/databases
   ```

### Database Dropdown is Empty

**Symptoms**: Dropdown shows "No databases available"

**Solutions**:

1. **Check API key has allowed databases**:
   - Visit http://localhost:8082/admin/api-keys
   - Verify your API key has databases in "Allowed Databases" field

2. **Test MCP endpoint directly**:
   ```bash
   curl -H "Authorization: $MCP_SCHEMA_SERVER_API_KEY" http://localhost:8082/api/v1/databases
   ```

3. **Check browser console** (F12):
   ```
   Failed to load databases from MCP: ...
   ```

### "Access denied to database: X" Error

**Symptoms**: Error when trying to use a specific database

**Solution**: Your API key doesn't have permission for that database. Generate a new API key with broader permissions or ask admin to update your key.

## Security Considerations

### API Key Storage

- ✅ **DO**: Store API key in `.env` file (excluded from git via `.gitignore`)
- ✅ **DO**: Use environment variables in production
- ❌ **DON'T**: Commit API keys to version control
- ❌ **DON'T**: Share API keys between environments (dev/staging/prod)

### API Key Rotation

To rotate an API key:

1. Generate new API key on gp-mcp-server
2. Update `.env` with new key:
   ```bash
   MCP_SCHEMA_SERVER_API_KEY=gpmcp_live_new_key_here
   ```
3. Restart gp-assistant:
   ```bash
   source .env
   ./mvnw spring-boot:run
   ```
4. (Optional) Revoke old key on gp-mcp-server

### Network Security

For production deployments:

- Use HTTPS for gp-mcp-server (`https://mcp.example.com`)
- Configure `MCP_SCHEMA_SERVER_URL` to use HTTPS:
  ```bash
  MCP_SCHEMA_SERVER_URL=https://mcp.example.com
  ```
- Consider using a reverse proxy (nginx, Traefik) with TLS termination

## Multi-Database Support

The API key can grant access to multiple databases:

```
allowedDatabases: ["analytics", "warehouse", "staging", "reporting"]
```

Users can switch between databases using the dropdown:

1. Select database → `GET /api/mcp/schemas?database=warehouse`
2. Select schema → Chat queries use that context
3. MCP tools (`gp.runQuery`) use the selected database

## Advanced Configuration

### Custom MCP Server URL

For non-default deployments:

```bash
MCP_SCHEMA_SERVER_URL=https://mcp.mycompany.com
MCP_SCHEMA_SERVER_ENDPOINT=/custom/mcp
```

### Disable MCP Integration

To run gp-assistant without MCP:

```bash
SPRING_AI_MCP_CLIENT_ENABLED=false
```

The application will:
- Skip MCP connection attempts
- Use only local database for dropdown population
- Disable MCP-powered tools in chat

## Reference

### Environment Variables

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `MCP_SCHEMA_SERVER_URL` | Base URL of gp-mcp-server | `http://localhost:8082` | Yes |
| `MCP_SCHEMA_SERVER_ENDPOINT` | MCP endpoint path | `/mcp` | No |
| `MCP_SCHEMA_SERVER_ENABLED` | Enable MCP connection | `true` | No |
| `MCP_SCHEMA_SERVER_API_KEY` | API key for authentication | (none) | **Yes** |
| `SPRING_AI_MCP_CLIENT_ENABLED` | Enable MCP client | `false` | Yes |

### REST Endpoints

#### gp-assistant

- `GET /api/mcp/databases` - Get available databases from MCP server
- `GET /api/mcp/schemas?database={db}` - Get schemas for a database

#### gp-mcp-server

- `GET /api/v1/databases` - List allowed databases for API key
- `GET /api/v1/databases/{db}/schemas` - List schemas in a database
- `POST /admin/api-keys/generate` - Generate new API key
- `GET /admin/api-keys` - Web UI for API key management

## Related Documentation

- [gp-mcp-server CLIENT_INTEGRATION_GUIDE.md](../gp-mcp-server/CLIENT_INTEGRATION_GUIDE.md)
- [gp-mcp-server API_KEY_GUIDE.md](../gp-mcp-server/API_KEY_GUIDE.md)
- [gp-mcp-server README.md](../gp-mcp-server/README.md)
