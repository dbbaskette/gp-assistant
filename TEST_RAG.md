# RAG Query Testing Guide

## Prerequisites

1. **Local LLM Server Running**
   ```bash
   # Check if LLM is running
   curl http://127.0.0.1:1234/v1/models
   ```

2. **PostgreSQL/Greenplum with Vector Data**
   ```bash
   # Check document count
   PGPASSWORD=VMware1! psql -h localhost -p 15432 -U gpadmin -d gp_assistant -c "SELECT COUNT(*) FROM gp_docs;"
   ```

3. **Application Started**
   ```bash
   # Start with MCP disabled for pure RAG testing
   export SPRING_AI_MCP_CLIENT_ENABLED=false
   export DOCS_INGEST_ON_STARTUP=false
   ./run.sh
   ```

## Manual Test Cases

### Test 1: Distributed Tables Query

**Question:** "What is a distributed table in Greenplum?"

**Expected Response:** Should explain distributed tables, segments, distribution keys, etc.

**Test Command:**
```bash
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What is a distributed table in Greenplum?",
    "conversationId": "test-rag-1"
  }'
```

**Success Criteria:**
- Response contains keywords: "distributed", "distribution", "segment"
- Response is coherent and factually correct about Greenplum
- Response time < 30 seconds

---

### Test 2: Table Partitioning Query

**Question:** "How does table partitioning work in Greenplum?"

**Expected Response:** Should explain range/list partitioning, partition keys, subpartitions, etc.

**Test Command:**
```bash
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{
    "question": "How does table partitioning work in Greenplum?",
    "conversationId": "test-rag-2"
  }'
```

**Success Criteria:**
- Response contains keywords: "partition", "range", "list"
- Explains partitioning concepts correctly
- Response time < 30 seconds

---

### Test 3: Conversation Memory

**Question 1:** "What is GPORCA?"

**Question 2:** "How does it improve query performance?"

**Expected Behavior:** The second question should understand "it" refers to GPORCA

**Test Commands:**
```bash
# First question
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What is GPORCA?",
    "conversationId": "test-memory-1"
  }'

# Follow-up question (same conversationId)
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{
    "question": "How does it improve query performance?",
    "conversationId": "test-memory-1"
  }'
```

**Success Criteria:**
- First response explains GPORCA (optimizer)
- Second response correctly interprets "it" as GPORCA
- Conversation context is maintained

---

### Test 4: Version-Specific Query

**Question:** "What are the new features in Greenplum 7?"

**Expected Response:** Should provide version-specific information

**Test Command:**
```bash
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What are the new features in Greenplum 7?",
    "targetVersion": "7.0",
    "conversationId": "test-version-1"
  }'
```

**Success Criteria:**
- Response mentions Greenplum 7 features
- Information is relevant to version 7.x

---

## Automated Test Execution

### Running the JUnit Test

```bash
# Run all RAG tests
mvn test -Dtest=RagQueryTest

# Run specific test
mvn test -Dtest=RagQueryTest#testDistributedTableQuery

# Run with extended timeouts (if embedding server is slow)
mvn test -Dtest=RagQueryTest \
  -Dspring.http.client.read-timeout=180s \
  -Dspring.http.client.connect-timeout=60s
```

### Test Conditions

The tests will automatically skip if:
- LLM server is not available at http://127.0.0.1:1234
- Vector store has no data
- Any other prerequisite is missing

---

## Troubleshooting

### Issue: Timeout errors

**Symptom:** `HttpTimeoutException: Request cancelled`

**Solutions:**
1. Increase read timeout in application.yaml:
   ```yaml
   spring.http.client.read-timeout: 180s
   ```

2. Check if embedding model is loaded:
   ```bash
   curl -X POST http://127.0.0.1:1234/v1/embeddings \
     -H "Content-Type: application/json" \
     -d '{"input": "test", "model": "text-embedding-nomic-embed-text-v2"}'
   ```

3. Reduce embedding batch size if needed

### Issue: Empty or irrelevant responses

**Symptom:** Response doesn't answer the question

**Solutions:**
1. Check vector store has documents:
   ```sql
   SELECT COUNT(*) FROM gp_docs;
   ```

2. Verify embedding dimensions match:
   ```sql
   SELECT LENGTH(embedding::text) FROM gp_docs LIMIT 1;
   ```

3. Check similarity threshold in application.yaml:
   ```yaml
   app.rag.similarity-threshold: 0.7
   ```

### Issue: LLM not available

**Symptom:** Tests are skipped

**Solutions:**
1. Start LLM server (LMStudio, Ollama, etc.)
2. Verify endpoint:
   ```bash
   curl http://127.0.0.1:1234/v1/models
   ```

---

## Test Results Format

When tests run successfully, they output:

```
=== RAG Query Test Results ===
Question: What is a distributed table in Greenplum?

Response: A distributed table in Greenplum is a table that is partitioned
and distributed across multiple segments based on a distribution key...

================================
```

---

## Integration with CI/CD

Add to your CI pipeline:

```yaml
# .github/workflows/test.yml
- name: Run RAG Tests
  run: |
    # Start LLM server (if available in CI)
    # Start PostgreSQL with vector extension
    mvn test -Dtest=RagQueryTest
  env:
    SPRING_AI_MCP_CLIENT_ENABLED: false
    DOCS_INGEST_ON_STARTUP: false
```

---

## Performance Benchmarks

Target response times:
- Simple query: < 10 seconds
- Complex query with RAG: < 30 seconds
- Conversation follow-up: < 15 seconds

Actual performance depends on:
- LLM model size and speed
- Embedding model performance
- Vector store query performance
- Network latency
