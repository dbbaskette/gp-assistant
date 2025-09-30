# 🚀 Greenplum AI Assistant (Spring Boot + Spring AI + pgvector)

![Greenplum Logo](https://greenplum.org/wp-content/uploads/2021/03/greenplum-logo.png) <!-- Placeholder image, you might want to replace this with a local one if available -->

## 🌟 Intelligent RAG for Greenplum Database

This project provides a **production-ready RAG (Retrieval-Augmented Generation)** assistant tailored for **Greenplum Database**. It intelligently ingests official documentation and leverages **Spring AI 1.1.0-SNAPSHOT** with **MCP (Model Context Protocol)** support to deliver intelligent, context-aware answers.

---

## ✨ Key Features

*   **🧠 RAG-powered Q&A**: Get precise answers from Greenplum documentation.
*   **📡 Version-Aware Context**: Automatically detects the connected Greenplum/PostgreSQL version and injects this context into AI prompts for highly relevant responses.
*   **🗣️ Seamless Conversation Flow**: Maintains chat history to enable natural, multi-turn conversations.
*   **🔌 MCP Client Integration**: Ready for dynamic tool integration via Model Context Protocol over HTTP SSE, expanding AI capabilities (currently disabled, waiting for your MCP servers!).
*   **📚 Smart Document Chunking**: Utilizes `TokenTextSplitter` for intelligent document segmentation, ensuring optimal context for the AI.
*   **📊 Production-Grade Metrics**: Integrated Prometheus metrics via Spring Boot Actuator for easy monitoring.
*   **🛡️ Robust Error Handling**: Comprehensive request validation and global exception handling for a stable application.
*   **⚙️ Highly Configurable**: Externalized configuration for easy customization via `application.yaml` or environment variables.

---

## 🛠️ Prerequisites

Before you begin, ensure you have the following installed:

*   **Java 21**: The application is built with the latest LTS Java version.
*   **Maven 3.9+**: The project uses a Maven Wrapper (`./mvnw`), so a local Maven installation is optional but recommended for advanced tasks.
*   **Greenplum or PostgreSQL**: A running database instance with the `pgvector` extension enabled.
*   **OpenAI API Key**: Required for generating document embeddings and chat completions.

---

## ⚙️ Configuration Essentials

All configurations can be managed in `src/main/resources/application.yaml` or through environment variables.

### 💾 Database Connection

Configure your database connection details:

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/gpdb}
    username: ${DB_USERNAME:gpuser}
    password: ${DB_PASSWORD:secret}
```

### 🔑 OpenAI API Key

Set your OpenAI API key as an environment variable:

```bash
export OPENAI_API_KEY=sk-... # Replace with your actual key
```

### 📑 Document Ingestion Settings

Define the Greenplum documentation URL and whether to ingest automatically on startup:

```yaml
app:
  rag:
    pdf-url: https://techdocs.broadcom.com/content/dam/broadcom/techdocs/us/en/pdf/vmware-tanzu/data-solutions/tanzu-greenplum/7/greenplum-database/greenplum-database.pdf
    ingest-on-startup: ${DOCS_INGEST_ON_STARTUP:true} # Set to false to disable auto-ingestion
```

### 🔍 RAG Parameters

Adjust how many relevant documents are retrieved and their similarity threshold:

```yaml
app:
  rag:
    top-k: 5                 # Number of most relevant documents to retrieve
    similarity-threshold: 0.7 # Minimum similarity score (0.0-1.0)
```

### 🪵 Local Log File (optional)

Mirror console logs to a local file during development by setting an environment variable before you start the app:

```bash
export APP_LOG_FILE="logs/gp-assistant.log"
```

Leave `APP_LOG_FILE` unset (or empty) to keep console-only logging.

### 🤖 Local Embedding Endpoint (optional)

Point embeddings to any OpenAI-compatible server—such as a local model—without affecting chat completions:

```bash
export OPENAI_EMBEDDING_BASE_URL="http://127.0.0.1:1234"
export OPENAI_EMBEDDING_MODEL="text-embedding-nomic-embed-text-v2"
# Optional: override the embeddings path if your server differs from the default
export OPENAI_EMBEDDINGS_PATH="/v1/embeddings"
```

If the local endpoint requires a different key than `OPENAI_API_KEY`, set `OPENAI_EMBEDDING_API_KEY`. Leave it empty when no key is needed.

### 🌐 MCP Client Configuration

The MCP client is **disabled by default**. Enable it when your MCP servers are ready:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: ${MCP_CLIENT_ENABLED:false} # Set to true to enable MCP client
        type: SYNC # or SSE
        request-timeout: 30s
        sse:
          connections:
            schema:
              url: ${MCP_SCHEMA_SERVER_URL:https://your-schema-server}
            query:
              url: ${MCP_QUERY_SERVER_URL:https://your-query-server}
```

---

## 🗄️ Database Setup

The application uses [Flyway](https://flywaydb.org/) for automatic database migrations at startup, which will:

*   ✅ Enable the `pgvector` extension.
*   ✅ Create the `public.gp_docs` table with a 1536-dimensional embedding column.
*   ✅ Create an `IVFFlat` Approximate Nearest Neighbor (ANN) index for lightning-fast similarity searches.

**Important**: If your database user lacks `CREATE EXTENSION` permissions, you'll need to run the following SQL command manually:

```sql
CREATE EXTENSION IF NOT EXISTS pgvector;
```

---

## 📚 Document Ingestion

### Manual Ingestion

You can trigger documentation ingestion at any time via the admin endpoint:

```bash
curl -X POST http://localhost:8080/admin/ingest
```

### Automatic Ingestion

Set `app.rag.ingest-on-startup=true` in `application.yaml` (default) or override via environment variable:

```bash
export DOCS_INGEST_ON_STARTUP=true
```

**⚡ Performance Tips for Ingestion**
*   For very large document sets, consider dropping the ANN index *before* ingestion and recreating it *after* the load, followed by an `ANALYZE` command.
*   Adjust the `lists` parameter in your `IVFFlat` index based on your corpus size (e.g., `~200` for up to `100,000` chunks).

---

## 🏃 Getting Started & Running

A `run.sh` (for Unix/macOS/Linux) and `run.bat` (for Windows) script are provided for convenience.

```bash
# Set your OpenAI API key (if not already set)
export OPENAI_API_KEY=sk-your-openai-api-key

# First time setup: clean build and run
./run.sh -c

# Subsequent runs: just run
./run.sh

# If you make code changes: build and run
./run.sh -b
```

The application will typically start on port `8080`.

---

## 📡 API Endpoints

### 💬 Ask Questions

**Endpoint**: `POST /api/ask`

**Request Body Example**:

```json
{
  "question": "How do I read EXPLAIN ANALYZE outputs in Greenplum 7?",
  "targetVersion": "7.0",
  "compatibleBaselines": ["6.x", "7.x"],
  "defaultAssumeVersion": "7.0",
  "conversationId": "user123-session456"
}
```

**Response**: Plain text answer from the AI assistant.

**Example `curl` call**:

```bash
curl -s -X POST http://localhost:8080/api/ask \
  -H 'Content-Type: application/json' \
  -d '{
    "question":"What version of Greenplum are we connected to?",
    "conversationId":"my-session"
  }'
```

### 🧑‍💻 Admin Endpoints

#### Trigger Document Ingestion
```bash
POST /admin/ingest
```

#### Get Database Information
```bash
GET /admin/database-info
```

**Response Example**:
```json
{
  "productName": "Greenplum Database",
  "version": "7.1.0",
  "fullVersion": "Greenplum Database 7.1.0",
  "isGreenplum": true
}
```

### ⚙️ MCP Tools Introspection (When enabled)

```bash
GET /api/mcp/tools
```

Returns a list of currently registered MCP tool names.

### 📈 Actuator Endpoints

Standard Spring Boot Actuator endpoints for monitoring:

*   `GET /actuator/health`       # Application health check
*   `GET /actuator/metrics`      # Comprehensive application metrics
*   `GET /actuator/prometheus`   # Prometheus-formatted metrics

---

## 📊 Custom Metrics

The application exposes the following custom metrics (via Micrometer/Prometheus):

*   `gp_assistant.docs.ingest.success`: Counter for successful document ingestions.
*   `gp_assistant.docs.ingest.failure`: Counter for failed document ingestions.
*   `gp_assistant.docs.ingest.duration`: Timer for the duration of document ingestion processes.
*   `gp_assistant.chat.queries`: Counter for the total number of chat queries processed.
*   `gp_assistant.chat.query.duration`: Timer for the time taken to process individual chat queries.
*   `gp_assistant.api.ask`: Request timing for the `/api/ask` endpoint.

---

## 🧰 Model Context Protocol (MCP)

This application includes **MCP client support** using Spring AI's `spring-ai-starter-mcp-client-webflux` artifact. MCP enables dynamic tool integration with AI models via HTTP Server-Sent Events (SSE).

### 🧐 How It Works

1.  **Tool Discovery**: At startup, the MCP client discovers tools from configured servers.
2.  **Dynamic Integration**: Discovered tools are exposed as dynamic `ToolCallbacks` to the AI model.
3.  **Tool Execution**: When the AI model decides to use a tool, the call is streamed over SSE to the MCP server.
4.  **Result Streaming**: Results from the MCP server are streamed back and seamlessly incorporated into the AI's response.

### 💡 Example Use Cases

*   **Schema Server**: Dynamically query database schemas (e.g., list relations, describe tables, get distribution keys).
*   **Query Server**: Execute read-only SQL queries or analyze query plans using database-specific tools.
*   **Admin Server**: Perform administrative tasks (e.g., `ANALYZE`, `VACUUM`), or retrieve database statistics.

---

## 🗣️ Conversation History

The application maintains in-memory conversation history. To keep context across multiple requests, simply include the `conversationId` field:

**First Request**:
```json
{
  "question": "What version of Greenplum are we currently connected to?",
  "conversationId": "my-unique-session-id"
}
```

**Follow-up Request (same conversation)**:
```json
{
  "question": "Does this version support column-oriented storage?",
  "conversationId": "my-unique-session-id"
}
```

---

## 🔒 Security Considerations

*   **Authentication**: API endpoints are currently **unauthenticated**. For production use, consider adding Spring Security.
*   **Credentials**: Database and API keys should *always* be externalized (e.g., environment variables, Spring Cloud Config, Vault).
*   **HTTPS**: Always use HTTPS in production environments.

---

## 🐳 Docker Support (Future)

A `docker-compose.yaml` setup for easily running Greenplum Database and this AI assistant will be added in a future release.

---

## 📂 Project Structure Highlights

*   `src/main/resources/prompts/`: Custom system and user prompt templates for the AI model.
*   `src/main/resources/db/migration/`: Flyway migration scripts for database schema management.
*   `src/main/java/com/baskettecase/gpassistant/`: Main Java source code.

---

## 🤝 Contributing

This project is a minimal starter. Contributions are welcome to extend its functionality, for example:

*   Implementing robust authentication and authorization.
*   Integrating with a persistent chat memory (e.g., Redis, database).
*   Adding document versioning and tracking capabilities.
*   Implementing streaming responses for enhanced user experience.
*   Developing additional MCP tool servers for more sophisticated interactions.
*   Building a responsive web UI for the chat interface.

---

## 📜 License

This project is licensed under the [Apache License, Version 2.0](LICENSE).

---

## 🙏 Acknowledgments

A big thank you to the open-source projects and communities that make this possible:

*   [Spring AI](https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/) - The powerful framework for AI integration.
*   [Greenplum Database](https://greenplum.org/) - The robust MPP database.
*   [OpenAI](https://openai.com/) - Providing cutting-edge embedding and chat models.
*   [pgvector](https://github.com/pgvector/pgvector) - Enabling efficient vector similarity search in PostgreSQL.

---

For more detailed information about Spring AI's MCP support, refer to the [official Spring AI documentation](https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/api/model-context-protocol.html).
