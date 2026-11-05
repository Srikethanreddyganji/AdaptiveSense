package com.adaptivesense.backend.service;

import com.adaptivesense.backend.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HTTP client for the local Ollama chat API. No provider credentials are used. */
@Service
public class OllamaProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    private final RestClient restClient;
    private final Semaphore generationPermit = new Semaphore(1, true);

    @Value("${ollama.base-url:http://127.0.0.1:11434}")
    private String baseUrl;

    @Value("${ollama.model:qwen2.5:3b}")
    private String model;

    @Value("${ollama.num-ctx:4096}")
    private int numCtx;

    @Value("${ollama.num-predict:220}")
    private int numPredict;

    @Value("${ollama.keep-alive:5m}")
    private String keepAlive;

    @Value("${ollama.request-queue-timeout:90s}")
    private Duration queueTimeout;

    public OllamaProvider(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /** Messages follow Ollama's native {role, content} chat format. */
    public String chat(List<Map<String, String>> messages, String requestId) {
        String correlationId = requestId == null || requestId.isBlank() ? "unavailable" : requestId;
        boolean acquired;
        try {
            acquired = generationPermit.tryAcquire(queueTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The local response model is busy. Please try again shortly.", "OLLAMA_BUSY");
        }
        if (!acquired) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "The local response model is busy. Please try again shortly.", "OLLAMA_BUSY");
        }

        String selectedModel = model == null || model.isBlank() ? "qwen2.5:3b" : model.trim();
        long started = System.nanoTime();
        try {
            Map<String, Object> request = Map.of(
                    "model", selectedModel,
                    "messages", messages,
                    "stream", false,
                    "keep_alive", keepAlive,
                    "options", Map.of(
                            "num_ctx", Math.min(4096, Math.max(1024, numCtx)),
                            "num_predict", Math.min(512, Math.max(64, numPredict))
                    )
            );
            log.info("Ollama generation started request_id={} model={} stream=false.",
                    correlationId, selectedModel);
            Map<?, ?> response = restClient.post()
                    .uri(chatUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            Object messageObject = response == null ? null : response.get("message");
            Object contentObject = messageObject instanceof Map<?, ?> message ? message.get("content") : null;
            String content = contentObject instanceof String text ? text.trim() : "";
            if (content.isBlank()) {
                log.warn("Ollama returned an invalid response request_id={} model={} request_ms={}.",
                        correlationId, selectedModel, elapsedMs(started));
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "The local response model returned an invalid response. Please try again.",
                        "OLLAMA_RESPONSE_INVALID");
            }
            log.info("Ollama generation completed request_id={} model={} request_ms={}.",
                    correlationId, selectedModel, elapsedMs(started));
            return content;
        } catch (HttpClientErrorException.NotFound exception) {
            log.warn("Ollama model missing request_id={} model={} status=404 request_ms={}.",
                    correlationId, selectedModel, elapsedMs(started));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The configured local model is not available. Pull qwen2.5:3b in Ollama and try again.",
                    "OLLAMA_MODEL_NOT_FOUND");
        } catch (ResourceAccessException exception) {
            if (isTimeout(exception)) {
                log.warn("Ollama request timed out request_id={} model={} request_ms={}.",
                        correlationId, selectedModel, elapsedMs(started));
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,
                        "The local response model took too long. Please try again.", "OLLAMA_TIMEOUT");
            }
            log.warn("Ollama is unavailable request_id={} model={} cause={} request_ms={}.",
                    correlationId, selectedModel, rootCause(exception).getClass().getSimpleName(), elapsedMs(started));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The local response model is unavailable. Make sure Ollama is running.", "OLLAMA_UNAVAILABLE");
        } catch (HttpClientErrorException exception) {
            log.warn("Ollama rejected request request_id={} model={} status={} request_ms={}.",
                    correlationId, selectedModel, exception.getStatusCode().value(), elapsedMs(started));
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The local response model rejected the request.", "OLLAMA_REQUEST_REJECTED");
        } catch (HttpServerErrorException exception) {
            log.warn("Ollama server error request_id={} model={} status={} request_ms={}.",
                    correlationId, selectedModel, exception.getStatusCode().value(), elapsedMs(started));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The local response model could not complete the request.", "OLLAMA_SERVER_ERROR");
        } catch (RestClientResponseException exception) {
            log.warn("Unexpected Ollama HTTP response request_id={} model={} status={} request_ms={}.",
                    correlationId, selectedModel, exception.getStatusCode().value(), elapsedMs(started));
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The local response service returned an unexpected response.", "OLLAMA_HTTP_ERROR");
        } catch (RestClientException exception) {
            log.warn("Ollama response could not be parsed request_id={} model={} exception={} request_ms={}.",
                    correlationId, selectedModel, exception.getClass().getSimpleName(), elapsedMs(started));
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The local response model returned an invalid response.", "OLLAMA_RESPONSE_INVALID");
        } finally {
            generationPermit.release();
        }
    }

    public String chat(String systemPrompt, String userPrompt) {
        return chat(List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ), null);
    }

    String chatUrl() {
        String root = baseUrl == null || baseUrl.isBlank()
                ? "http://127.0.0.1:11434" : baseUrl.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return root + "/api/chat";
    }

    private static long elapsedMs(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }
}
