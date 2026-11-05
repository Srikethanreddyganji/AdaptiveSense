package com.adaptivesense.backend.service;

import com.adaptivesense.backend.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HTTP client for the local Ollama chat API. No provider credentials are used. */
@Service
public class OllamaProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;
    private final Semaphore generationPermit = new Semaphore(1, true);
    private final Object permitLock = new Object();
    private int foregroundWaiters;

    @Value("${ollama.base-url:http://127.0.0.1:11434}")
    private String baseUrl;

    @Value("${ollama.model:qwen2.5:3b}")
    private String model;

    @Value("${ollama.num-ctx:4096}")
    private int numCtx;

    @Value("${ollama.num-predict:220}")
    private int numPredict;

    @Value("${ollama.num-thread:0}")
    private int numThread;

    @Value("${ollama.keep-alive:30m}")
    private String keepAlive;

    @Value("${ollama.request-queue-timeout:90s}")
    private Duration queueTimeout;

    public OllamaProvider(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /** Messages follow Ollama's native {role, content} chat format. */
    public String chat(List<Map<String, String>> messages, String requestId) {
        return generate(messages, requestId, false, ignored -> {}, false);
    }

    /** Streams Ollama deltas as they arrive and returns the exact accumulated response. */
    public String chatStream(List<Map<String, String>> messages, String requestId, Consumer<String> onDelta) {
        return generate(messages, requestId, true, onDelta == null ? ignored -> {} : onDelta, false);
    }

    /** Best-effort background generation skips instead of queueing ahead of user-facing work. */
    public String chatBackground(List<Map<String, String>> messages) {
        return generate(messages, null, false, ignored -> {}, true);
    }

    private String generate(List<Map<String, String>> messages, String requestId,
                            boolean streaming, Consumer<String> onDelta, boolean background) {
        String correlationId = requestId == null || requestId.isBlank() ? "unavailable" : requestId;
        boolean acquired;
        if (background) {
            synchronized (permitLock) {
                acquired = foregroundWaiters == 0 && generationPermit.tryAcquire();
            }
            if (!acquired) {
                log.debug("Optional Ollama memory update skipped while foreground generation is active.");
                return null;
            }
        } else {
            synchronized (permitLock) {
                foregroundWaiters++;
            }
            try {
                acquired = generationPermit.tryAcquire(queueTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The local response model is busy. Please try again shortly.", "OLLAMA_BUSY");
            } finally {
                synchronized (permitLock) {
                    foregroundWaiters--;
                }
            }
        }
        if (!acquired) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "The local response model is busy. Please try again shortly.", "OLLAMA_BUSY");
        }

        String selectedModel = model == null || model.isBlank() ? "qwen2.5:3b" : model.trim();
        long started = System.nanoTime();
        try {
            Map<String, Object> options = new java.util.LinkedHashMap<>();
            options.put("num_ctx", Math.min(4096, Math.max(1024, numCtx)));
            options.put("num_predict", Math.min(512, Math.max(64, numPredict)));
            if (numThread > 0) options.put("num_thread", Math.min(8, numThread));
            Map<String, Object> request = Map.of(
                    "model", selectedModel,
                    "messages", messages,
                    "stream", streaming,
                    "keep_alive", keepAlive,
                    "options", options
            );
            log.info("Ollama generation started request_id={} model={} stream={}.",
                    correlationId, selectedModel, streaming);
            var requestSpec = restClient.post()
                    .uri(chatUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(streaming ? MediaType.parseMediaType("application/x-ndjson") : MediaType.APPLICATION_JSON)
                    .body(request);
            String content = streaming
                    ? readStream(requestSpec.retrieve().body(InputStream.class), correlationId, selectedModel,
                            started, onDelta)
                    : readSingleResponse(requestSpec.retrieve().body(Map.class));
            content = content.trim();
            if (content.isBlank()) {
                log.warn("Ollama returned an invalid response request_id={} model={} request_ms={}.",
                        correlationId, selectedModel, elapsedMs(started));
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "The local response model returned an invalid response. Please try again.",
                        "OLLAMA_RESPONSE_INVALID");
            }
            log.info("Ollama generation completed request_id={} model={} stream={} response_chars={} total_ms={}",
                    correlationId, selectedModel, streaming, content.length(), elapsedMs(started));
            return content;
        } catch (HttpClientErrorException.NotFound exception) {
            log.warn("Ollama model missing request_id={} model={} status=404 request_ms={}.",
                    correlationId, selectedModel, elapsedMs(started));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The configured local model is not available. Check that it is installed in Ollama.",
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
        } catch (ApiException exception) {
            throw exception;
        } finally {
            generationPermit.release();
        }
    }

    private String readSingleResponse(Map<?, ?> response) {
        Object messageObject = response == null ? null : response.get("message");
        Object contentObject = messageObject instanceof Map<?, ?> message ? message.get("content") : null;
        return contentObject instanceof String text ? text : "";
    }

    private String readStream(InputStream body, String requestId, String selectedModel,
                              long started, Consumer<String> onDelta) {
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The local response model returned an invalid stream.", "OLLAMA_RESPONSE_INVALID");
        }
        StringBuilder response = new StringBuilder();
        boolean done = false;
        long firstDeltaAt = 0L;
        try (InputStream input = body;
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                final Map<?, ?> event;
                try {
                    event = objectMapper.readValue(line, Map.class);
                } catch (JsonProcessingException exception) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY,
                            "The local response model returned an invalid stream.", "OLLAMA_RESPONSE_INVALID");
                }
                Object providerError = event.get("error");
                if (providerError instanceof String) {
                    String category = String.valueOf(providerError).toLowerCase().contains("model")
                            ? "OLLAMA_MODEL_NOT_FOUND" : "OLLAMA_SERVER_ERROR";
                    HttpStatus status = category.equals("OLLAMA_MODEL_NOT_FOUND")
                            ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
                    throw new ApiException(status,
                            category.equals("OLLAMA_MODEL_NOT_FOUND")
                                    ? "The configured local model is not available. Check that it is installed in Ollama."
                                    : "The local response model could not complete the request.", category);
                }
                Object messageObject = event.get("message");
                Object contentObject = messageObject instanceof Map<?, ?> message ? message.get("content") : null;
                if (contentObject instanceof String delta && !delta.isEmpty()) {
                    if (firstDeltaAt == 0L) {
                        firstDeltaAt = System.nanoTime();
                        log.info("Ollama first delta request_id={} model={} first_delta_ms={}",
                                requestId, selectedModel, elapsedMs(started));
                    }
                    response.append(delta);
                    onDelta.accept(delta);
                }
                if (Boolean.TRUE.equals(event.get("done"))) {
                    done = true;
                    break;
                }
            }
        } catch (IOException exception) {
            if (isTimeout(exception)) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,
                        "The local response model took too long. Please try again.", "OLLAMA_TIMEOUT");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The local response stream was interrupted. Please try again.", "OLLAMA_STREAM_INTERRUPTED");
        }
        if (!done) {
            log.warn("Ollama stream ended before done request_id={} model={} response_chars={} total_ms={}",
                    requestId, selectedModel, response.length(), elapsedMs(started));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The local response stream ended early. Please try again.", "OLLAMA_STREAM_INTERRUPTED");
        }
        return response.toString();
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
