package com.baskettecase.gpassistant.controller;

import com.baskettecase.gpassistant.service.DocsChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Validated
public class ChatUiController {

    private final DocsChatService chatService;
    private final Environment environment;

    @PostMapping(path = "/message", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatMessageResponse> message(@Valid @RequestBody ChatMessageRequest request) {
        String conversationId = StringUtils.hasText(request.getConversationId())
                ? request.getConversationId()
                : UUID.randomUUID().toString();

        String targetVersion = StringUtils.hasText(request.getTargetVersion())
                ? request.getTargetVersion()
                : "7.0";

        String[] baselines = (request.getCompatibleBaselines() != null && request.getCompatibleBaselines().length > 0)
                ? request.getCompatibleBaselines()
                : new String[]{"6.x", "7.x"};

        String assume = StringUtils.hasText(request.getDefaultAssumeVersion())
                ? request.getDefaultAssumeVersion()
                : targetVersion;

        log.info("Chat UI message: question='{}' conversation='{}' targetVersion='{}'", 
                trimForLog(request.getQuestion()), conversationId, targetVersion);

        String answer = chatService.ask(request.getQuestion(), targetVersion, baselines, assume, conversationId);
        ChatMessageResponse response = new ChatMessageResponse(
                answer,
                conversationId,
                Instant.now().toString(),
                environment.getProperty("spring.ai.openai.chat.options.model", "local-chat-model")
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping(path = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatConfigResponse config() {
        String baseUrl = environment.getProperty("spring.ai.openai.base-url", "http://127.0.0.1:1234");
        String embeddingBase = environment.getProperty("spring.ai.openai.embedding.base-url", baseUrl);
        String embeddingPath = environment.getProperty("spring.ai.openai.embedding.embeddings-path", "/v1/embeddings");
        String apiKey = environment.getProperty("spring.ai.openai.api-key", "local-mode-placeholder");
        String chatModel = environment.getProperty("spring.ai.openai.chat.options.model", "local-chat-model");
        String embeddingModel = environment.getProperty("spring.ai.openai.embedding.options.model", "local-embedding-model");

        boolean isOpenAi = StringUtils.hasText(apiKey) && !"local-mode-placeholder".equals(apiKey);

        return new ChatConfigResponse(
                isOpenAi ? "OPENAI" : "LOCAL",
                baseUrl,
                embeddingBase + embeddingPath,
                chatModel,
                embeddingModel,
                conversationIdSeed()
        );
    }

    private String conversationIdSeed() {
        return UUID.randomUUID().toString();
    }

    private String trimForLog(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.length() > 120 ? text.substring(0, 117) + "…" : text;
    }

    @Data
    public static class ChatMessageRequest {
        @NotBlank(message = "Question is required")
        private String question;
        private String conversationId;
        private String targetVersion;
        private String[] compatibleBaselines;
        private String defaultAssumeVersion;
    }

    private record ChatMessageResponse(String answer, String conversationId, String respondedAt, String model) {}

    private record ChatConfigResponse(String mode, String baseUrl, String embeddingEndpoint,
                                      String chatModel, String embeddingModel, String conversationId) {}
}
