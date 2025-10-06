# SQLite Vector Store Implementation Guide (Option 3)

**Status:** Future Enhancement
**Estimated Effort:** 2-3 days
**Spring AI Version:** 1.1.0-SNAPSHOT or later

---

## Overview

This document outlines how to implement a custom SQLite-based vector store for Spring AI using the [sqlite-vec](https://github.com/asg017/sqlite-vec) extension. This would enable **zero-dependency deployment** of gp-assistant without requiring PostgreSQL/Greenplum for the RAG vector storage.

**Benefits:**
- Single file database (`gp-assistant.db`)
- No external database server required
- Portable: Copy file = backup/restore
- Fast local queries
- Ideal for edge deployments, demos, and development

**Trade-offs:**
- Custom implementation to maintain
- Limited write concurrency vs PostgreSQL
- Need to test ANN index performance at scale
- No horizontal scaling

---

## Architecture

```
┌─────────────────────────────────────────────┐
│ Spring AI Application                       │
├─────────────────────────────────────────────┤
│ VectorStore Interface                       │
│ ↓                                           │
│ SqliteVecVectorStore (custom)               │
│ ↓                                           │
│ SQLite JDBC Driver                          │
│ ↓                                           │
│ gp-assistant.db (with sqlite-vec extension) │
│ ├─ vector_store table                       │
│ ├─ mcp_servers table                        │
│ └─ app metadata tables                      │
└─────────────────────────────────────────────┘
```

---

## Implementation Steps

### 1. Add Dependencies

```xml
<!-- pom.xml -->
<dependencies>
    <!-- SQLite JDBC Driver -->
    <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
        <version>3.47.1.0</version>
    </dependency>

    <!-- sqlite-vec extension (native library) -->
    <dependency>
        <groupId>io.github.asg017</groupId>
        <artifactId>sqlite-vec</artifactId>
        <version>0.1.3</version>
    </dependency>
</dependencies>
```

### 2. Implement VectorStore Interface

```java
package com.baskettecase.gpassistant.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SqliteVecVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteVecVectorStore.class);

    private final Connection connection;
    private final EmbeddingModel embeddingModel;
    private final int dimensions;
    private final String tableName;

    public SqliteVecVectorStore(
            String dbPath,
            EmbeddingModel embeddingModel,
            int dimensions,
            String tableName) throws SQLException {

        this.embeddingModel = embeddingModel;
        this.dimensions = dimensions;
        this.tableName = tableName;

        // Load sqlite-vec extension
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);

        this.connection = DriverManager.getConnection(
            "jdbc:sqlite:" + dbPath,
            config.toProperties()
        );

        // Load vec0 extension
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SELECT load_extension('vec0')");
        }

        initializeSchema();
    }

    private void initializeSchema() throws SQLException {
        String createTable = String.format("""
            CREATE TABLE IF NOT EXISTS %s (
                id TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                metadata TEXT,
                embedding BLOB NOT NULL
            )
            """, tableName);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTable);

            // Create virtual table for vector index
            String createVectorIndex = String.format("""
                CREATE VIRTUAL TABLE IF NOT EXISTS %s_vec_idx
                USING vec0(
                    id TEXT PRIMARY KEY,
                    embedding FLOAT[%d]
                )
                """, tableName, dimensions);

            stmt.execute(createVectorIndex);

            log.info("Initialized SQLite vector store table: {}", tableName);
        }
    }

    @Override
    public void add(List<Document> documents) {
        String insertSql = String.format(
            "INSERT OR REPLACE INTO %s (id, content, metadata, embedding) VALUES (?, ?, ?, ?)",
            tableName
        );

        String insertVecSql = String.format(
            "INSERT OR REPLACE INTO %s_vec_idx (id, embedding) VALUES (?, ?)",
            tableName
        );

        try (PreparedStatement docStmt = connection.prepareStatement(insertSql);
             PreparedStatement vecStmt = connection.prepareStatement(insertVecSql)) {

            connection.setAutoCommit(false);

            for (Document doc : documents) {
                // Generate embedding
                List<Double> embedding = embeddingModel.embed(doc);
                byte[] embeddingBytes = serializeEmbedding(embedding);

                // Insert document
                docStmt.setString(1, doc.getId());
                docStmt.setString(2, doc.getContent());
                docStmt.setString(3, serializeMetadata(doc.getMetadata()));
                docStmt.setBytes(4, embeddingBytes);
                docStmt.addBatch();

                // Insert vector index entry
                vecStmt.setString(1, doc.getId());
                vecStmt.setBytes(2, embeddingBytes);
                vecStmt.addBatch();
            }

            docStmt.executeBatch();
            vecStmt.executeBatch();
            connection.commit();

            log.info("Added {} documents to vector store", documents.size());

        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                log.error("Rollback failed", rollbackEx);
            }
            throw new RuntimeException("Failed to add documents", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                log.error("Failed to reset auto-commit", e);
            }
        }
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        // Generate embedding for query
        List<Double> queryEmbedding = embeddingModel.embed(
            Document.builder().content(request.getQuery()).build()
        );

        // Perform vector similarity search using sqlite-vec
        String searchSql = String.format("""
            SELECT d.id, d.content, d.metadata,
                   vec_distance_cosine(v.embedding, ?) as distance
            FROM %s d
            JOIN %s_vec_idx v ON d.id = v.id
            WHERE distance < ?
            ORDER BY distance ASC
            LIMIT ?
            """, tableName, tableName);

        List<Document> results = new ArrayList<>();

        try (PreparedStatement stmt = connection.prepareStatement(searchSql)) {
            stmt.setBytes(1, serializeEmbedding(queryEmbedding));
            stmt.setDouble(2, 1.0 - request.getSimilarityThreshold());
            stmt.setInt(3, request.getTopK());

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String id = rs.getString("id");
                String content = rs.getString("content");
                String metadataJson = rs.getString("metadata");
                double distance = rs.getDouble("distance");

                Map<String, Object> metadata = deserializeMetadata(metadataJson);
                metadata.put("distance", distance);

                results.add(Document.builder()
                    .id(id)
                    .content(content)
                    .metadata(metadata)
                    .build());
            }

            log.debug("Found {} similar documents", results.size());

        } catch (SQLException e) {
            throw new RuntimeException("Similarity search failed", e);
        }

        return results;
    }

    @Override
    public void delete(List<String> idList) {
        String deleteSql = String.format("DELETE FROM %s WHERE id = ?", tableName);
        String deleteVecSql = String.format("DELETE FROM %s_vec_idx WHERE id = ?", tableName);

        try (PreparedStatement docStmt = connection.prepareStatement(deleteSql);
             PreparedStatement vecStmt = connection.prepareStatement(deleteVecSql)) {

            connection.setAutoCommit(false);

            for (String id : idList) {
                docStmt.setString(1, id);
                docStmt.addBatch();

                vecStmt.setString(1, id);
                vecStmt.addBatch();
            }

            docStmt.executeBatch();
            vecStmt.executeBatch();
            connection.commit();

            log.info("Deleted {} documents", idList.size());

        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                log.error("Rollback failed", rollbackEx);
            }
            throw new RuntimeException("Failed to delete documents", e);
        }
    }

    private byte[] serializeEmbedding(List<Double> embedding) {
        // Convert List<Double> to byte array for storage
        // sqlite-vec expects float32 format
        ByteBuffer buffer = ByteBuffer.allocate(embedding.size() * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        for (Double value : embedding) {
            buffer.putFloat(value.floatValue());
        }

        return buffer.array();
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        // Convert metadata map to JSON string
        try {
            return new ObjectMapper().writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata", e);
            return "{}";
        }
    }

    private Map<String, Object> deserializeMetadata(String json) {
        try {
            return new ObjectMapper().readValue(json,
                new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize metadata", e);
            return new HashMap<>();
        }
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            log.info("Closed SQLite vector store connection");
        }
    }
}
```

### 3. Spring Configuration

```java
@Configuration
@ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "sqlite")
public class SqliteVectorStoreConfig {

    @Value("${app.vectorstore.sqlite.path:./data/gp-assistant.db}")
    private String dbPath;

    @Value("${app.vectorstore.dimensions:768}")
    private int dimensions;

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) throws SQLException {
        // Ensure data directory exists
        Path dataDir = Paths.get(dbPath).getParent();
        if (dataDir != null && !Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
        }

        return new SqliteVecVectorStore(
            dbPath,
            embeddingModel,
            dimensions,
            "gp_docs"
        );
    }
}
```

### 4. Application Configuration

```yaml
# application.yaml
app:
  vectorstore:
    type: sqlite  # or "pgvector"
    sqlite:
      path: ${SQLITE_DB_PATH:./data/gp-assistant.db}
    dimensions: ${APP_VECTORSTORE_DIMENSIONS:768}
```

---

## sqlite-vec Extension Setup

### Option 1: Bundled Native Library

Package the sqlite-vec native library with your application:

```
src/main/resources/
└── native/
    ├── linux-x86_64/
    │   └── vec0.so
    ├── darwin-arm64/
    │   └── vec0.dylib
    └── win32-x86_64/
        └── vec0.dll
```

Load extension from classpath:
```java
InputStream libStream = getClass().getResourceAsStream("/native/" + osArch + "/vec0" + ext);
Path tempLib = Files.createTempFile("vec0", ext);
Files.copy(libStream, tempLib, StandardCopyOption.REPLACE_EXISTING);
stmt.execute("SELECT load_extension('" + tempLib.toString() + "')");
```

### Option 2: System-Installed Extension

Require users to install sqlite-vec system-wide:

```bash
# macOS
brew install sqlite-vec

# Linux (build from source)
git clone https://github.com/asg017/sqlite-vec.git
cd sqlite-vec
make loadable
sudo cp vec0.so /usr/lib/sqlite/
```

Then load via:
```java
stmt.execute("SELECT load_extension('vec0')");
```

---

## Performance Considerations

### 1. ANN Index Configuration

sqlite-vec supports different index types:

```sql
-- Default (brute force, exact search)
CREATE VIRTUAL TABLE vec_idx USING vec0(
    embedding FLOAT[768]
);

-- With IVF index for approximate search (faster on large datasets)
CREATE VIRTUAL TABLE vec_idx USING vec0(
    embedding FLOAT[768],
    index_type='ivf',
    nlist=100  -- Number of clusters
);
```

**Benchmark before production:**
- Test with your expected dataset size (10K, 100K, 1M documents)
- Compare query latency vs pgvector IVFFlat
- Monitor index build time on ingestion

### 2. Connection Pooling

SQLite doesn't benefit from connection pooling like PostgreSQL. Use a single connection with proper locking:

```java
@Bean
public SqliteVecVectorStore vectorStore() {
    SQLiteConfig config = new SQLiteConfig();
    config.setJournalMode(SQLiteConfig.JournalMode.WAL); // Write-Ahead Logging
    config.setBusyTimeout(30000); // 30 second busy timeout

    return new SqliteVecVectorStore(config);
}
```

### 3. Batch Operations

Always use batching for ingestion:

```java
connection.setAutoCommit(false);
for (Document doc : documents) {
    stmt.addBatch();
}
stmt.executeBatch();
connection.commit();
```

---

## Migration Path

### From PgVector to SQLite-vec

```java
@Component
public class VectorStoreMigrator {

    public void migratePgVectorToSqlite(
            PgVectorStore source,
            SqliteVecVectorStore target) {

        int batchSize = 1000;
        int offset = 0;

        while (true) {
            // Read batch from PgVector
            List<Document> batch = source.similaritySearch(
                SearchRequest.query("").topK(batchSize).offset(offset)
            );

            if (batch.isEmpty()) break;

            // Write to SQLite
            target.add(batch);

            log.info("Migrated batch: {} documents", batch.size());
            offset += batchSize;
        }

        log.info("Migration complete");
    }
}
```

### Export/Import via JSON

```bash
# Export from PgVector
curl http://localhost:8080/admin/export-vectors > vectors.jsonl

# Import to SQLite
curl -X POST http://localhost:8080/admin/import-vectors \
  -H "Content-Type: application/x-ndjson" \
  --data-binary @vectors.jsonl
```

---

## Testing Strategy

### Unit Tests

```java
@SpringBootTest
class SqliteVecVectorStoreTest {

    @TempDir
    Path tempDir;

    private SqliteVecVectorStore vectorStore;

    @BeforeEach
    void setup() throws SQLException {
        Path dbPath = tempDir.resolve("test.db");
        vectorStore = new SqliteVecVectorStore(
            dbPath.toString(),
            new MockEmbeddingModel(768),
            768,
            "test_vectors"
        );
    }

    @Test
    void testAddAndSearch() {
        // Create test documents
        List<Document> docs = List.of(
            Document.builder()
                .content("Greenplum is a database")
                .metadata(Map.of("source", "docs"))
                .build()
        );

        // Add to store
        vectorStore.add(docs);

        // Search
        List<Document> results = vectorStore.similaritySearch(
            SearchRequest.query("database").topK(5)
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).contains("Greenplum");
    }
}
```

### Load Testing

```java
@Test
void testLargeDatasetPerformance() {
    // Generate 10K documents
    List<Document> docs = generateDocuments(10_000);

    // Measure ingestion time
    long start = System.currentTimeMillis();
    vectorStore.add(docs);
    long ingestTime = System.currentTimeMillis() - start;

    log.info("Ingested 10K docs in {}ms", ingestTime);

    // Measure query time
    start = System.currentTimeMillis();
    List<Document> results = vectorStore.similaritySearch(
        SearchRequest.query("test query").topK(10)
    );
    long queryTime = System.currentTimeMillis() - start;

    log.info("Query returned in {}ms", queryTime);

    // Assertions
    assertThat(ingestTime).isLessThan(60_000); // < 1 min
    assertThat(queryTime).isLessThan(1_000);   // < 1 sec
}
```

---

## Deployment Considerations

### Docker

```dockerfile
FROM eclipse-temurin:21-jre-alpine

# Install SQLite with extensions support
RUN apk add --no-cache sqlite sqlite-dev

# Copy application
COPY target/gp-assistant.jar /app/app.jar
COPY vec0.so /usr/lib/sqlite/

# Volume for database
VOLUME /data

ENV SQLITE_DB_PATH=/data/gp-assistant.db

CMD ["java", "-jar", "/app/app.jar"]
```

### Backup Strategy

```bash
# SQLite backup is simple - just copy the file
cp ./data/gp-assistant.db ./backups/gp-assistant-$(date +%Y%m%d).db

# Or use SQLite's built-in backup API
sqlite3 ./data/gp-assistant.db ".backup ./backups/backup.db"
```

---

## Contribution to Spring AI

Once implemented and tested, consider contributing back:

1. **Fork Spring AI:** `https://github.com/spring-projects/spring-ai`
2. **Create Module:** `spring-ai-sqlite-vec-store`
3. **Follow Pattern:** Match `PgVectorStore` structure
4. **Add Tests:** Unit + integration tests
5. **Documentation:** Add to Vector Databases chapter
6. **Submit PR:** Reference issue for SQLite support

**Potential Community Impact:**
- First embedded vector store for Spring AI
- Enable edge deployments
- Lower barrier to entry for RAG demos

---

## Resources

- **sqlite-vec GitHub:** https://github.com/asg017/sqlite-vec
- **sqlite-vec Blog:** https://alexgarcia.xyz/blog/2024/sqlite-vec-stable-release/
- **Spring AI VectorStore Interface:** https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/vectorstore/VectorStore.html
- **SQLite JDBC:** https://github.com/xerial/sqlite-jdbc
- **Spring AI Contribution Guide:** https://github.com/spring-projects/spring-ai/blob/main/CONTRIBUTING.md

---

## Timeline Estimate

| Phase | Effort | Description |
|-------|--------|-------------|
| Research & Prototyping | 4 hours | Test sqlite-vec, explore API |
| Core Implementation | 8 hours | Implement VectorStore interface |
| Testing & Tuning | 6 hours | Unit tests, performance benchmarks |
| Documentation | 2 hours | Code docs, migration guide |
| **Total** | **20 hours** | **~2.5 days** |

---

## Decision Criteria for Future

**When to revisit SQLite-vec implementation:**

✅ **Yes, implement if:**
- Need to support air-gapped/offline deployments
- Targeting embedded devices (Raspberry Pi, etc.)
- Want to simplify demo/trial deployments
- Community requests embedded vector store for Spring AI

❌ **No, stick with PgVector if:**
- Production deployments all have PostgreSQL/Greenplum
- Write concurrency is critical
- Leveraging Greenplum's MPP architecture
- Team lacks time for custom implementation

---

**Next Steps (when ready):**
1. Create GitHub issue: "Implement SQLite-vec VectorStore"
2. Spike: 1-day prototype to validate approach
3. Decide: Build in-house or contribute to Spring AI
4. Implement: Follow this guide
5. Test: Benchmark vs PgVector
6. Deploy: Release as experimental feature flag
