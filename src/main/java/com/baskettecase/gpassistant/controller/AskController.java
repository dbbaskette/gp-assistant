package com.baskettecase.gpassistant.controller;

import com.baskettecase.gpassistant.service.DocsChatService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("/api")
@Validated
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private final DocsChatService chat;

    public AskController(DocsChatService chat) {
        this.chat = chat;
    }

    @PostMapping(path = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    @Timed(value = "gp_assistant.api.ask", description = "Time taken to process ask requests")
    public ResponseEntity<String> ask(@Valid @RequestBody AskRequest req) {
        log.info("Received ask request: question='{}', version='{}', conversationId='{}'", 
                req.question, req.targetVersion, req.conversationId);
        
        try {
            String[] baselines = req.compatibleBaselines != null ? req.compatibleBaselines : new String[]{"6.x", "7.x"};
            String assume = req.defaultAssumeVersion != null ? req.defaultAssumeVersion : "7.0";
            String version = req.targetVersion != null ? req.targetVersion : "7.0";
            String conversationId = req.conversationId;
            
            String response = chat.ask(req.question, version, baselines, assume, conversationId);
            
            log.debug("Successfully processed ask request for conversationId='{}'", conversationId);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error processing ask request: question='{}'", req.question, e);
            return ResponseEntity.internalServerError()
                    .body("Error processing your question: " + e.getMessage());
        }
    }

    public static class AskRequest {
        @NotBlank(message = "Question is required")
        private String question;

        private String targetVersion;
        private String[] compatibleBaselines;
        private String defaultAssumeVersion;

        /**
         * Optional conversation ID for maintaining chat history.
         * If not provided, the interaction will be stateless.
         */
        private String conversationId;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getTargetVersion() {
            return targetVersion;
        }

        public void setTargetVersion(String targetVersion) {
            this.targetVersion = targetVersion;
        }

        public String[] getCompatibleBaselines() {
            return compatibleBaselines;
        }

        public void setCompatibleBaselines(String[] compatibleBaselines) {
            this.compatibleBaselines = compatibleBaselines;
        }

        public String getDefaultAssumeVersion() {
            return defaultAssumeVersion;
        }

        public void setDefaultAssumeVersion(String defaultAssumeVersion) {
            this.defaultAssumeVersion = defaultAssumeVersion;
        }

        public String getConversationId() {
            return conversationId;
        }

        public void setConversationId(String conversationId) {
            this.conversationId = conversationId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AskRequest that = (AskRequest) o;
            return Objects.equals(question, that.question) &&
                    Objects.equals(targetVersion, that.targetVersion) &&
                    java.util.Arrays.equals(compatibleBaselines, that.compatibleBaselines) &&
                    Objects.equals(defaultAssumeVersion, that.defaultAssumeVersion) &&
                    Objects.equals(conversationId, that.conversationId);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(question, targetVersion, defaultAssumeVersion, conversationId);
            result = 31 * result + java.util.Arrays.hashCode(compatibleBaselines);
            return result;
        }

        @Override
        public String toString() {
            return "AskRequest{" +
                    "question='" + question + '\'' +
                    ", targetVersion='" + targetVersion + '\'' +
                    ", compatibleBaselines=" + java.util.Arrays.toString(compatibleBaselines) +
                    ", defaultAssumeVersion='" + defaultAssumeVersion + '\'' +
                    ", conversationId='" + conversationId + '\'' +
                    '}';
        }
    }
}
