package com.baskettecase.gpassistant.controller;

import com.baskettecase.gpassistant.service.DocsChatService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class AskController {

    private final DocsChatService chat;

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

    @Data
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
    }
}
