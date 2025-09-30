package com.baskettecase.gpassistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .filter(logRequest())
                .filter(logResponse());
    }

    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            log.debug("==== HTTP Request ====");
            log.debug("URI: {}", clientRequest.url());
            log.debug("Method: {}", clientRequest.method());
            log.debug("Headers: {}", clientRequest.headers());

            clientRequest.headers().forEach((name, values) -> {
                values.forEach(value -> log.debug("  {}: {}", name, value));
            });

            return Mono.just(clientRequest);
        });
    }

    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            log.debug("==== HTTP Response ====");
            log.debug("Status: {}", clientResponse.statusCode());
            log.debug("Headers: {}", clientResponse.headers().asHttpHeaders());
            return Mono.just(clientResponse);
        });
    }
}