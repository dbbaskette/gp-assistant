# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Greenplum AI Assistant** is a production-ready RAG (Retrieval-Augmented Generation) chatbot for Greenplum Database documentation. Built with Spring Boot 3.3.6 and Spring AI 1.1.0-SNAPSHOT, it provides intelligent, version-aware answers using pgvector for semantic search and supports Model Context Protocol (MCP) for dynamic tool integration.

**Tech Stack**: Java 21, Spring Boot, Spring AI, pgvector, Flyway, OpenAI API (or local models), MCP Client

## Development Commands

### Running the Application

```bash
# First run or after major changes (clean build)
./run.sh -c

# Normal run (no build)
./run.sh

# Build and run
./run.sh -b

# Windows
run.bat
```

The run scripts auto-load `.env` and print model configuration before starting.

### Maven Commands

```bash
# Build only
./mvnw compile -DskipTests

# Clean build
./mvnw clean compile -DskipTests

# Run Spring Boot directly
./mvnw spring-boot:run

# Package
./mvnw package -DskipTests
```

### Testing

```bash
# Run all tests
./mvnw test

# Run specific test
./mvnw test -Dtest=RagQueryTest
```

### Environment Setup

1. Copy `.env.template` to `.env`
2. Configure database: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
3. Choose model provider:
   - **Local models** (default): Set `LOCAL_MODEL_BASE_URL=http://127.0.0.1:1234/v1`
   - **OpenAI**: Set `OPENAI_API_KEY=sk-...`
4. For MCP integration: Set `SPRING_AI_MCP_CLIENT_ENABLED=true` and configure `GP_MCP_SERVER_API_KEY`

## Architecture

### Model Provider Selection

The `Application.java` main method configures the AI provider **before Spring starts**:
- Checks for `OPENAI_API_KEY` environment variable
- If present → configures OpenAI endpoints and models
- If absent → configures local OpenAI-compatible server (default: `http://127.0.0.1:1234`)
- All configuration via system properties (not Spring's `@Value`)

### Core RAG Flow

1. **Document Ingestion** ([DocsIngestor.java](src/main/java/com/baskettecase/gpassistant/DocsIngestor.java))
   - Downloads Greenplum PDF documentation
   - Splits into chunks using `TokenTextSplitter`
   - Generates embeddings via `EmbeddingModel`
   - Stores in pgvector (`gp_docs` table)
   - Triggered by `DOCS_INGEST_ON_STARTUP=true` or `POST /admin/ingest`

2. **Query Processing** ([DocsChatService.java](src/main/java/com/baskettecase/gpassistant/service/DocsChatService.java))
   - Loads prompts from `src/main/resources/prompts/gp_system.txt` and `gp_user.txt`
   - Injects context: connected database version, target version, database/schema names
   - Uses `QuestionAnswerAdvisor` to retrieve relevant docs from pgvector
   - Uses `ChatMemory` for conversation history (20 messages per conversation)
   - If MCP enabled: Adds tool callbacks for dynamic database queries
   - Returns AI response

3. **Vector Store** ([AiConfig.java](src/main/java/com/baskettecase/gpassistant/config/AiConfig.java))
   - PgVectorStore with IVFFlat index for fast similarity search
   - Configured via `app.vectorstore.dimensions` (must match embedding model width)
   - RAG parameters: `app.rag.top-k=5`, `app.rag.similarity-threshold=0.7`

### MCP Integration

**What is MCP?** Model Context Protocol allows AI models to call external tools (e.g., database queries) dynamically during conversation.

**Architecture**:
- **MCP Server** (gp-mcp-server): Separate service exposing database tools (runQuery, listTables, etc.)
- **MCP Client** (this app): Discovers tools from server, exposes them to ChatClient
- **Authentication**: API key-based (encrypted database credentials in key)

**Key Components**:
- [ResilientMcpToolCallbackConfig.java](src/main/java/com/baskettecase/gpassistant/config/ResilientMcpToolCallbackConfig.java): Wraps MCP tools with retry/fallback logic
- [DynamicMcpClientManager.java](src/main/java/com/baskettecase/gpassistant/service/DynamicMcpClientManager.java): Manages MCP connections with exponential backoff retry
- [McpDatabaseController.java](src/main/java/com/baskettecase/gpassistant/controller/McpDatabaseController.java): Proxies database metadata requests to MCP server

**Enabling MCP**:
1. Set `SPRING_AI_MCP_CLIENT_ENABLED=true`
2. Generate API key from gp-mcp-server at `http://localhost:8082/admin/api-keys`
3. Set `GP_MCP_SERVER_API_KEY=gpmcp_live_...`
4. App will retry failed connections automatically (5 attempts, exponential backoff)

### Version-Aware Context

**Problem**: Greenplum has different versions (6.x, 7.x) with feature differences.

**Solution**:
- [GreenplumVersionService.java](src/main/java/com/baskettecase/gpassistant/service/GreenplumVersionService.java) detects connected database version
- Injects `{{CONNECTED_VERSION}}` and `{{IS_GREENPLUM}}` into prompts
- Allows targeting responses to specific versions via `targetVersion` parameter

### Chat UI

**Path**: `src/main/resources/static/`
- [index.html](src/main/resources/static/index.html): Liquid glass chat interface
- [chat.js](src/main/resources/static/assets/js/chat.js): Frontend logic (conversation memory, database/schema dropdowns, status indicators)
- Calls `POST /api/chat/message` for streaming-like experience
- Stores `conversationId` in localStorage to maintain chat history

**Status Indicators**:
- 🌐 API - Application health
- 💾 DB - Vector store database connection
- 🔌 MCP - MCP server connection + tool count
- 🧠 Model - AI model readiness

## Database Schema

Managed by Flyway (`src/main/resources/db/migration/V1__enable_vector_extensions.sql`):

```sql
CREATE EXTENSION IF NOT EXISTS pgvector;

CREATE TABLE IF NOT EXISTS public.gp_docs (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(1536)  -- Dimension must match embedding model
);

CREATE INDEX IF NOT EXISTS gp_docs_embedding_idx
ON public.gp_docs USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);
```

**Important**: Change `APP_VECTORSTORE_DIMENSIONS` if using different embedding model (e.g., 768 for nomic-embed-text).

## Configuration Patterns

### Environment Variables vs application.yaml

- **Environment Variables**: Override defaults, loaded in `Application.java` or via `.env`
- **application.yaml**: Defaults with `${ENV_VAR:default}` syntax
- **Precedence**: Environment > application.yaml > code defaults

### Key Configuration Locations

1. **Model Selection**: [Application.java:19-46](src/main/java/com/baskettecase/gpassistant/Application.java#L19-L46) (before Spring context)
2. **RAG Configuration**: [application.yaml:68-72](src/main/resources/application.yaml#L68-L72)
3. **MCP Configuration**: [application.yaml:28-47](src/main/resources/application.yaml#L28-L47)
4. **Database Connection**: [application.yaml:4-12](src/main/resources/application.yaml#L4-L12)

## Prompt Engineering

Prompts are **externalized** in `src/main/resources/prompts/`:
- `gp_system.txt`: System role (defines AI behavior, version context)
- `gp_user.txt`: User role (question format, RAG context injection)

**Template Variables** (replaced at runtime):
- `{{TARGET_GP_VERSION}}` - Target version for answer
- `{{CONNECTED_VERSION}}` - Actual connected database version
- `{{DATABASE_NAME}}` - Current database context
- `{{SCHEMA_NAME}}` - Current schema context
- `{{USER_QUESTION}}` - User's question
- `{{RESOURCES}}` - RAG-retrieved document chunks

**Editing Prompts**: Modify text files, restart app (no code changes needed).

## Metrics & Monitoring

**Exposed Endpoints**:
- `/actuator/health` - Health check
- `/actuator/metrics` - All metrics
- `/actuator/prometheus` - Prometheus format

**Custom Metrics** (Micrometer):
- `gp_assistant.docs.ingest.success` - Successful ingestions
- `gp_assistant.docs.ingest.failure` - Failed ingestions
- `gp_assistant.docs.ingest.duration` - Ingestion time
- `gp_assistant.chat.queries` - Total queries
- `gp_assistant.chat.duration` - Query processing time

## Common Development Tasks

### Adding New MCP Tools

1. Implement tool in gp-mcp-server (separate repo)
2. Restart gp-mcp-server
3. Restart gp-assistant (auto-discovers new tools)
4. Tools appear in `GET /api/mcp/tools`

### Changing Embedding Model

1. Update environment variables:
   ```bash
   LOCAL_EMBEDDING_MODEL=new-model-name
   APP_VECTORSTORE_DIMENSIONS=768  # Match new model dimension
   ```
2. Clear existing vectors: `TRUNCATE TABLE public.gp_docs;`
3. Restart app with `DOCS_INGEST_ON_STARTUP=true`

### Adding New Documentation Sources

Edit [DocsIngestor.java](src/main/java/com/baskettecase/gpassistant/DocsIngestor.java):
1. Add new PDF URL to configuration or load multiple sources
2. Modify `ingestPdf()` to handle multiple sources
3. Consider metadata tagging for source tracking

## Important Notes

### Spring AI Version

Uses **1.1.0-SNAPSHOT** for MCP support. Snapshot dependencies require Spring snapshot repositories (already configured in [pom.xml](pom.xml#L119-L147)).

### Model Compatibility

- **OpenAI**: Use `gpt-4o-mini` or better for tool calling
- **Local Models**: Must support OpenAI-compatible API + function calling
- **Embeddings**: Dimension must match pgvector schema (change requires DB migration)

### MCP Tool Integration

**Issue**: `ChatClient.Builder.defaultToolCallbacks()` doesn't work with local models in current Spring AI version.

**Workaround**: Tools are added per-request in [DocsChatService.java:126-137](src/main/java/com/baskettecase/gpassistant/service/DocsChatService.java#L126-L137) using `promptBuilder.toolCallbacks(tools)`.

### Conversation Memory

- **Storage**: In-memory only (lost on restart)
- **Window**: 20 messages per conversation
- **For Production**: Replace `InMemoryChatMemoryRepository` with Redis/database-backed implementation

## Troubleshooting

### MCP Connection Issues

Check logs for:
```
❌ Failed to connect to MCP server 'gp-schema': Connection refused
```

Solutions:
1. Verify gp-mcp-server is running: `curl http://localhost:8082/actuator/health`
2. Check API key is set: `echo $GP_MCP_SERVER_API_KEY`
3. Test authentication: `curl -H "Authorization: $GP_MCP_SERVER_API_KEY" http://localhost:8082/api/v1/databases`

### Ingestion Failures

Common causes:
- PDF download timeout (increase `spring.http.client.read-timeout`)
- Embedding API rate limits (reduce `app.docs.batch-size`)
- Database connection timeout (check Greenplum/PostgreSQL connectivity)

### Model Not Responding

1. Check model base URL: Look for "Using OpenAI/local models at..." in startup logs
2. Test endpoint: `curl http://127.0.0.1:1234/v1/models`
3. Verify API key (if using OpenAI): `echo $OPENAI_API_KEY`

## Related Documentation

- [README.md](README.md) - Full feature overview and setup
- [MCP_INTEGRATION.md](MCP_INTEGRATION.md) - Detailed MCP setup guide
- [.env.template](.env.template) - All available environment variables
