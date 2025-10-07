# Known Issues

## Spring Boot 3.4+ Local Model Compatibility Issue

### Problem
When using local LLM models (LMStudio, Ollama, etc.) with Spring Boot 3.4.x or 3.5.x, embedding and chat requests may timeout or hang indefinitely.

### Symptoms
- `HttpTimeoutException: Request cancelled` after 60 seconds
- Embedding requests to `http://127.0.0.1:1234/v1/embeddings` timeout
- Chat requests hang without completing
- The local model server is working fine when tested directly with curl

### Root Cause
Spring Boot 3.4+ changed the default HTTP client implementation and timeout handling which causes compatibility issues with some local model servers that don't fully comply with the OpenAI API spec.

### Workarounds

#### Option 1: Use OpenAI or Claude Instead
Configure real API endpoints instead of local models:

```bash
export OPENAI_API_KEY=sk-...
# App will automatically use OpenAI instead of local models
./run.sh
```

#### Option 2: Downgrade to Spring Boot 3.3.x (Recommended for Local Models)
Edit `pom.xml`:
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>  <!-- Change from 3.4.10 -->
</parent>
```

Then rebuild:
```bash
mvn clean compile
./run.sh
```

#### Option 3: Configure Different HTTP Client
Add custom HTTP client configuration that's more tolerant (experimental):

```java
@Bean
public RestClient.Builder restClientBuilder() {
    return RestClient.builder()
        .requestFactory(new SimpleClientHttpRequestFactory());
}
```

#### Option 4: Use Remote LLM Service
Deploy your local model behind a proxy that normalizes the API responses.

### Impact
- **RAG queries fail** when using local embedding models
- **Chat completions timeout** when using local chat models
- **Tests cannot run** without remote API access

### Testing Status
- ✅ Tests work with OpenAI API
- ✅ Tests work with Spring Boot 3.3.x + local models
- ❌ Tests fail with Spring Boot 3.4.x + local models

### Related Issues
- Spring Boot issue: [spring-projects/spring-boot#xxxxx](https://github.com/spring-projects/spring-boot/issues/xxxxx)
- Spring AI issue: [spring-projects/spring-ai#xxxxx](https://github.com/spring-projects/spring-ai/issues/xxxxx)

### Verification
Test if local model works:

```bash
# Test embedding endpoint directly
curl -X POST http://127.0.0.1:1234/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"input": "test", "model": "text-embedding-nomic-embed-text-v2"}' | jq .

# Test chat endpoint directly
curl -X POST http://127.0.0.1:1234/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "test", "messages": [{"role": "user", "content": "hi"}]}' | jq .
```

If both work, but the Spring Boot app times out, it's the Spring Boot 3.4+ compatibility issue.

---

## MCP Server Connection Issues

### Problem
Application fails to start when MCP server is configured but not running.

### Solution
✅ **Fixed in current version** - App now starts gracefully and retries MCP connections automatically.

See [DynamicMcpClientManager](src/main/java/com/baskettecase/gpassistant/service/DynamicMcpClientManager.java) for retry implementation.
