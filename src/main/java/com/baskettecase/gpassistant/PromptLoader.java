package com.baskettecase.gpassistant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
public class PromptLoader {
    
    /**
     * Load a prompt from the classpath.
     * 
     * @param classpathPath Path to the resource in the classpath (e.g., "prompts/gp_system.txt")
     * @return The content of the prompt file
     * @throws RuntimeException if the file cannot be loaded
     */
    public static String load(String classpathPath) {
        try {
            log.debug("Loading prompt from classpath: {}", classpathPath);
            ClassPathResource resource = new ClassPathResource(classpathPath);
            if (!resource.exists()) {
                throw new IOException("Resource not found: " + classpathPath);
            }
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.debug("Successfully loaded prompt from {}, length: {} characters", classpathPath, content.length());
            return content;
        } catch (IOException e) {
            log.error("Failed to load prompt from classpath: {}", classpathPath, e);
            throw new RuntimeException("Failed to load prompt: " + classpathPath, e);
        }
    }
}