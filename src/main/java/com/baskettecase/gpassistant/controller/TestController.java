package com.baskettecase.gpassistant.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    private final VectorStore vectorStore;

    public TestController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostMapping("/embed-single")
    public String testSingleEmbedding(@RequestBody String text) {
        try {
            log.info("Testing single embedding with text: {}",
                    text.length() > 100 ? text.substring(0, 100) + "..." : text);

            Document doc = new Document(text, Map.of("test", "true"));

            log.info("Calling vectorStore.add() with single document");
            long start = System.currentTimeMillis();

            vectorStore.add(List.of(doc));

            long time = System.currentTimeMillis() - start;
            String result = "Successfully embedded text in " + time + "ms";
            log.info(result);

            return result;
        } catch (Exception e) {
            log.error("Failed to embed text", e);
            return "Error: " + e.getMessage();
        }
    }
}