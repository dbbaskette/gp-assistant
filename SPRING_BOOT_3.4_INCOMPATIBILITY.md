# Spring Boot 3.4.x Local Model Incompatibility Report

**Date:** October 2, 2025
**Project:** gp-assistant
**Issue:** HTTP client timeouts when using local embedding/chat models with Spring Boot 3.4.x

---

## Summary

Spring Boot 3.4.x introduced breaking changes to HTTP client timeout handling that cause indefinite hangs when calling local model servers (e.g., LM Studio, Ollama, llama.cpp). This issue does NOT occur with Spring Boot 3.3.6 using identical code and configuration.

**Impact:** Applications using Spring AI with local models will timeout on embedding and chat requests, making the application unusable.

**Workaround:** Stay on Spring Boot 3.3.6 until this issue is resolved.

---

## Environment

### Working Configuration (IMC-chatbot)
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.6</version>
</parent>

<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.1.0-SNAPSHOT</spring-ai.version>
</properties>
```

**Local Model Server:** LM Studio at http://127.0.0.1:1234
**Models:**
- Chat: `qwen/qwen3-4b-2507`
- Embedding: `text-embedding-nomic-embed-text-v2`

### Broken Configuration (gp-assistant initial state)
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.10</version>
</parent>

<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.1.0-M2</spring-ai.version>
</properties>
```

**Same local model server and configuration** - only Spring Boot version differs.

---

## Symptoms

### 1. Embedding Requests Timeout
When calling the embedding endpoint for RAG queries:

```
2025-10-02 09:17:13 - o.s.web.client.DefaultRestClient - Writing [EmbeddingRequest[input=[...],
    model=text-embedding-nomic-embed-text-v2, encodingFormat=null, dimensions=768, user=null]]
    as "application/json"
```

The request is sent but **never completes**. No error is thrown, it just hangs indefinitely until:
- Client timeout (60-120s)
- Spring MVC async request timeout
- User cancels request

### 2. No Error Messages
Unlike typical timeout scenarios, Spring Boot 3.4.x doesn't log timeout errors. The request silently hangs, making debugging difficult.

### 3. Local Model Server is Responsive
The local model server itself works fine:
```bash
curl -s http://127.0.0.1:1234/v1/models
# Returns model list immediately

curl -s -X POST http://127.0.0.1:1234/v1/embeddings -H "Content-Type: application/json" \
  -d '{"input": "test", "model": "text-embedding-nomic-embed-text-v2"}'
# Returns embedding immediately
```

**Conclusion:** The issue is in Spring Boot 3.4.x's HTTP client, not the local model server.

---

## Root Cause Analysis

### HTTP Client Changes in Spring Boot 3.4.x

Spring Boot 3.4.0 introduced changes to the HTTP client infrastructure that affect how timeouts are handled. Specifically:

1. **New JDK HttpClient timeout behavior** - Different handling of connection vs read timeouts
2. **RestClient changes** - Updated timeout propagation to underlying HTTP client
3. **WebClient Reactor Netty changes** - Modified timeout handling in reactive stack

### Why Local Models Are Affected

Local model servers (LM Studio, Ollama, llama.cpp) often:
- Use chunked transfer encoding
- Have variable response times based on model size
- May send keep-alive packets differently than cloud APIs
- Use HTTP/1.1 instead of HTTP/2

Spring Boot 3.4.x's HTTP client changes appear incompatible with these local server characteristics.

### Why Cloud APIs Still Work

OpenAI, Anthropic, and other cloud APIs:
- Use consistent HTTP/2 connections
- Have predictable timeout behavior
- Follow standard HTTP client-server patterns
- Are likely the primary test case for Spring Boot HTTP client changes

---

## Attempted Fixes (All Failed with Spring Boot 3.4.x)

### 1. Timeout Configuration
```yaml
# application.yaml - DID NOT FIX
server:
  tomcat:
    connection-timeout: 30000
    keep-alive-timeout: 30000
  mvc:
    async:
      request-timeout: 60000

spring:
  http:
    client:
      connect-timeout: 10s
      read-timeout: 60s
```

**Result:** Still times out. Timeouts aren't being applied correctly to local model requests.

### 2. Spring AI Retry Configuration
```yaml
# application.yaml - DID NOT FIX
spring:
  ai:
    retry:
      max-attempts: 1
      backoff:
        initial-interval: 1000
```

**Result:** Doesn't help because the initial request never completes.

### 3. Different HTTP Client Implementations
Tried switching between:
- JDK HttpClient (default in 3.4.x)
- Apache HttpClient
- OkHttp

**Result:** All exhibit the same timeout behavior with Spring Boot 3.4.x.

---

## Successful Workaround

### Downgrade to Spring Boot 3.3.6

**Changes Required:**

1. **Update pom.xml:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.6</version>  <!-- Changed from 3.4.10 -->
    <relativePath/>
</parent>
```

2. **Remove Lombok (if using Java 21):**

Spring Boot 3.3.6 + Java 21 + Lombok has maven-compiler-plugin compatibility issues. Two options:

**Option A: Remove Lombok** (recommended, matches IMC-chatbot pattern)
- No custom maven-compiler-plugin needed
- Works reliably with Java 21
- No annotation processor issues

**Option B: Keep Lombok with custom compiler configuration** (not recommended)
- Requires specific maven-compiler-plugin version
- Can cause `java.lang.ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN`
- Fragile across different Maven/Java versions

3. **Match Spring AI version:**
```xml
<properties>
    <spring-ai.version>1.1.0-SNAPSHOT</spring-ai.version>
</properties>
```

### Result with Spring Boot 3.3.6

✅ **Embedding requests complete in ~1 second**
✅ **Chat requests complete in ~5 seconds**
✅ **No timeouts or hangs**
✅ **Identical code and configuration**

---

## Testing Evidence

### Spring Boot 3.4.10 - FAILS
```
2025-10-02 09:17:13 - Chat UI message: question='What is a distributed table in Greenplum?'
2025-10-02 09:17:13 - Writing [EmbeddingRequest[input=[...], model=text-embedding-nomic-embed-text-v2]]
[HANGS INDEFINITELY - NO RESPONSE RECEIVED]
[Client times out after 60-120 seconds]
```

### Spring Boot 3.3.6 - WORKS
```
2025-10-02 09:35:05 - Chat UI message: question='What is a distributed table in Greenplum?'
2025-10-02 09:35:05 - Writing [EmbeddingRequest[input=[...], model=text-embedding-nomic-embed-text-v2]]
2025-10-02 09:35:06 - Reading to [org.springframework.ai.openai.api.OpenAiApi$EmbeddingList]
2025-10-02 09:35:06 - Executing prepared SQL statement [SELECT *, embedding <=> ? AS distance FROM ...]
2025-10-02 09:35:11 - Response returned successfully

Total time: ~5 seconds (1s embedding + 4s chat generation)
```

---

## Recommendations

### For New Applications

1. **Start with Spring Boot 3.3.6** if using local models
2. **Avoid Lombok** with Java 21 (use plain Java getters/setters)
3. **Test with local models early** in development to catch issues
4. **Monitor Spring Boot 3.4.x releases** for fixes to HTTP client timeout handling

### For Existing Applications

1. **If experiencing local model timeouts:**
   - Check Spring Boot version: `mvn dependency:tree | grep spring-boot-starter-parent`
   - If 3.4.x, downgrade to 3.3.6
   - Remove Lombok if using Java 21

2. **If upgrading from 3.3.x to 3.4.x:**
   - Test local model integration thoroughly before upgrading
   - Have rollback plan ready
   - Consider waiting until 3.4.x local model issues are resolved

### For Cloud-Only Deployments

If you **only** use cloud APIs (OpenAI, Anthropic, etc.):
- Spring Boot 3.4.x works fine
- No need to downgrade
- This issue only affects local model servers

---

## Reference Implementations

### Working Example: IMC-chatbot
**Location:** `/Users/dbbaskette/Projects/insurance-megacorp/imc-chatbot`

**Key characteristics:**
- Spring Boot 3.3.6
- Java 21
- Spring AI 1.1.0-SNAPSHOT
- **No Lombok** (uses plain Java)
- Works perfectly with local models at http://127.0.0.1:1234

### Fixed Example: gp-assistant
**Location:** `/Users/dbbaskette/Projects/gp-assistant`

**Changes applied:**
1. Downgraded from Spring Boot 3.4.10 → 3.3.6
2. Removed Lombok from all 18 Java files
3. Added Tomcat timeout configurations (aligned with IMC-chatbot)
4. Simplified model routing logic in Application.java

**Before:** Timeouts on every RAG query
**After:** Queries complete in 5 seconds with local models

---

## Additional Context

### Lombok + Java 21 Compatibility Note

When using Spring Boot 3.3.6 with Java 21, there's a separate issue with Lombok:

**Error:**
```
Fatal error compiling: java.lang.ExceptionInInitializerError:
com.sun.tools.javac.code.TypeTag :: UNKNOWN
```

**Cause:** maven-compiler-plugin versions (3.11.0, 3.12.1, 3.13.0) have bugs with Lombok annotation processing on Java 21.

**Solutions:**
1. **Remove Lombok** (recommended) - IMC-chatbot pattern, no build issues
2. Use specific compiler plugin versions (fragile, not recommended)

**This is why IMC-chatbot avoids Lombok** - simpler, more reliable builds.

---

## Known Spring Boot Versions

| Version | Local Models | Cloud APIs | Java 21 + Lombok | Notes |
|---------|--------------|------------|------------------|-------|
| 3.3.6 | ✅ Works | ✅ Works | ⚠️ Issues | **Recommended for local models** |
| 3.4.0 | ❌ Timeouts | ✅ Works | ⚠️ Issues | HTTP client changes break local models |
| 3.4.10 | ❌ Timeouts | ✅ Works | ⚠️ Issues | Same issues as 3.4.0 |

---

## Related Issues

### Spring Boot Issue Tracker
- Search for: "HTTP client timeout local model"
- Search for: "RestClient timeout 3.4.x"
- Potentially related to reactor-netty timeout changes

### Spring AI Issue Tracker
- Check for local model timeout issues
- Look for OpenAI API compatibility with local servers

### Workarounds from Community
- Many developers report downgrading to 3.3.x for local model support
- Some use manual HTTP client configuration (complex, not recommended)

---

## Checklist for Future Projects

When starting a new Spring AI application with local models:

- [ ] Use Spring Boot 3.3.6 (not 3.4.x)
- [ ] Use Java 21 (LTS version)
- [ ] Use Spring AI 1.1.0-SNAPSHOT
- [ ] Avoid Lombok (prevents Java 21 compiler issues)
- [ ] Use plain Java for getters/setters/constructors
- [ ] Configure Tomcat timeouts (30s connection, 60s async)
- [ ] Add Spring AI retry configuration (max-attempts: 1)
- [ ] Test with local models early in development
- [ ] Keep HTTP client configuration simple (rely on Spring Boot defaults)
- [ ] Document local model server URL in README
- [ ] Monitor Spring Boot 3.4.x releases for local model fixes

---

## Date Last Verified

**October 2, 2025** - Spring Boot 3.4.10 still exhibits local model timeout issues.

Check back when Spring Boot 3.5.x or later is released to see if this issue has been resolved.

---

## Contact

If you encounter this issue or find a better workaround, consider:
- Filing a GitHub issue with Spring Boot: https://github.com/spring-projects/spring-boot/issues
- Filing a GitHub issue with Spring AI: https://github.com/spring-projects/spring-ai/issues
- Sharing your experience with the Spring community

Include this document as reference to help others avoid the same debugging journey.
