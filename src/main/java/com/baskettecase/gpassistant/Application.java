package com.baskettecase.gpassistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.util.StringUtils;

@SpringBootApplication
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        configureModelProvider();
        SpringApplication.run(Application.class, args);
    }

    private static void configureModelProvider() {
        String openAiKey = env("OPENAI_API_KEY");

        // Default to local models, switch to OpenAI if API key is provided
        if (StringUtils.hasText(openAiKey)) {
            // OpenAI configuration
            setIfMissing("spring.ai.openai.base-url",
                    envOrDefault("OPENAI_BASE_URL", "https://api.openai.com"));
            setIfMissing("spring.ai.openai.api-key", openAiKey);
            setIfMissing("spring.ai.openai.chat.options.model",
                    envOrDefault("OPENAI_CHAT_MODEL", "gpt-4o-mini"));
            setIfMissing("spring.ai.openai.embedding.options.model",
                    envOrDefault("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small"));
            log.info("Using OpenAI models at {}", System.getProperty("spring.ai.openai.base-url"));
        } else {
            // Local model configuration (default)
            String localBaseUrl = envOrDefault("LOCAL_MODEL_BASE_URL", "http://127.0.0.1:1234");
            setIfMissing("spring.ai.openai.base-url", localBaseUrl);
            setIfMissing("spring.ai.openai.api-key", envOrDefault("LOCAL_MODEL_API_KEY", "dummy-key"));
            setIfMissing("spring.ai.openai.chat.options.model",
                    envOrDefault("LOCAL_CHAT_MODEL", "local-chat-model"));
            setIfMissing("spring.ai.openai.embedding.options.model",
                    envOrDefault("LOCAL_EMBEDDING_MODEL", "local-embedding-model"));
            setIfMissing("spring.ai.openai.chat.options.temperature",
                    envOrDefault("OPENAI_CHAT_TEMPERATURE", "1.0"));
            log.info("Using local models at {}", localBaseUrl);
        }
    }

    private static void setIfMissing(String key, String value) {
        if (StringUtils.hasText(value) && !StringUtils.hasText(System.getProperty(key))) {
            System.setProperty(key, value);
        }
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String envOrDefault(String key, String fallback) {
        String value = env(key);
        return StringUtils.hasText(value) ? value : fallback;
    }
}
