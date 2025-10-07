package com.baskettecase.gpassistant.service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * HttpClient wrapper that adds X-API-Key header to all requests.
 * gp-mcp-server expects format: X-API-Key: {id}.{secret}
 * Workaround for HttpClientStreamableHttpTransport not supporting custom headers.
 */
public class AuthorizingHttpClient extends HttpClient {

    private final HttpClient delegate;
    private final String apiKey;

    public AuthorizingHttpClient(HttpClient delegate, String apiKey) {
        this.delegate = delegate;
        // gp-mcp-server expects X-API-Key header with format {id}.{secret}
        this.apiKey = apiKey;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        HttpRequest modifiedRequest = addAuthorizationHeader(request);
        return delegate.send(modifiedRequest, responseBodyHandler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler) {
        HttpRequest modifiedRequest = addAuthorizationHeader(request);
        return delegate.sendAsync(modifiedRequest, responseBodyHandler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        HttpRequest modifiedRequest = addAuthorizationHeader(request);
        return delegate.sendAsync(modifiedRequest, responseBodyHandler, pushPromiseHandler);
    }

    private HttpRequest addAuthorizationHeader(HttpRequest request) {
        return HttpRequest.newBuilder(request, (name, value) -> true)
                .header("X-API-Key", apiKey)
                .build();
    }

    // Delegate all other methods to the wrapped client
    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }
}
