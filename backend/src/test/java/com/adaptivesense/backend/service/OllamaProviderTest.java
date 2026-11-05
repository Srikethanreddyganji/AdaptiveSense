package com.adaptivesense.backend.service;

import com.adaptivesense.backend.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OllamaProviderTest {

    private OllamaProvider provider;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OllamaProvider(builder);
        ReflectionTestUtils.setField(provider, "baseUrl", "http://127.0.0.1:11434");
        ReflectionTestUtils.setField(provider, "model", "qwen2.5:3b");
        ReflectionTestUtils.setField(provider, "numCtx", 4096);
        ReflectionTestUtils.setField(provider, "numPredict", 220);
        ReflectionTestUtils.setField(provider, "keepAlive", "30m");
        ReflectionTestUtils.setField(provider, "numThread", 4);
        ReflectionTestUtils.setField(provider, "queueTimeout", Duration.ofMillis(25));
    }

    @Test
    void postsNativeChatRequestAndReturnsActualMessageContent() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().json("""
                        {"model":"qwen2.5:3b","stream":false,"keep_alive":"30m",
                         "options":{"num_ctx":4096,"num_predict":220,"num_thread":4},
                         "messages":[{"role":"user","content":"Hello"}]}
                        """))
                .andRespond(withSuccess("""
                        {"message":{"role":"assistant","content":"I hear you."},"done":true}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals("I hear you.", provider.chat(List.of(Map.of("role", "user", "content", "Hello")), "req-1"));
        server.verify();
    }

    @Test
    void letsOllamaChooseCpuThreadsWhenNoOverrideIsConfigured() {
        ReflectionTestUtils.setField(provider, "numThread", 0);
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andExpect(content().json("""
                        {"options":{"num_ctx":4096,"num_predict":220}}
                        """, false))
                .andRespond(withSuccess("""
                        {"message":{"content":"The response is complete."},"done":true}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));
        assertEquals("The response is complete.", provider.chat(
                List.of(Map.of("role", "user", "content", "Hello")), "req-default-threads"));
        server.verify();
    }

    @Test
    void missingModelIsReportedWithoutFallback() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("model not found"));
        ApiException error = assertThrows(ApiException.class,
                () -> provider.chat(List.of(Map.of("role", "user", "content", "Hello")), "req-2"));
        assertEquals("OLLAMA_MODEL_NOT_FOUND", error.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        server.verify();
    }

    @Test
    void serverErrorIsCategorizedAsOllamaServerError() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andRespond(withServerError());
        ApiException error = assertThrows(ApiException.class,
                () -> provider.chat(List.of(Map.of("role", "user", "content", "Hello")), "req-3"));
        assertEquals("OLLAMA_SERVER_ERROR", error.getCode());
        server.verify();
    }

    @Test
    void timeoutIsReportedSeparately() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andRespond(withException(new SocketTimeoutException("timeout")));
        ApiException error = assertThrows(ApiException.class,
                () -> provider.chat(List.of(Map.of("role", "user", "content", "Hello")), "req-4"));
        assertEquals("OLLAMA_TIMEOUT", error.getCode());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, error.getStatus());
        server.verify();
    }

    @Test
    void connectionRefusedIsReportedAsUnavailable() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andRespond(withException(new ConnectException("connection refused")));
        ApiException error = assertThrows(ApiException.class,
                () -> provider.chat(List.of(Map.of("role", "user", "content", "Hello")), "req-6"));
        assertEquals("OLLAMA_UNAVAILABLE", error.getCode());
        server.verify();
    }

    @Test
    void providerClientErrorIsNotRetriedOrMisreportedAsModelOutput() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("do not expose this body"));
        ApiException error = assertThrows(ApiException.class,
                () -> provider.chat(List.of(Map.of("role", "user", "content", "Hello")), "req-7"));
        assertEquals("OLLAMA_REQUEST_REJECTED", error.getCode());
        assertFalse(error.getMessage().contains("do not expose this body"));
        server.verify();
    }

    @Test
    void blankOrMalformedProviderContentIsNotPresentedAsSuccess() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andRespond(withSuccess("{\"message\":{\"content\":\" \"}}", org.springframework.http.MediaType.APPLICATION_JSON));
        ApiException error = assertThrows(ApiException.class,
                () -> provider.chat(List.of(Map.of("role", "user", "content", "Hello")), "req-5"));
        assertEquals("OLLAMA_RESPONSE_INVALID", error.getCode());
        server.verify();
    }

    @Test
    void malformedJsonIsClassifiedAsInvalidResponse() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andRespond(withSuccess("not-json", org.springframework.http.MediaType.APPLICATION_JSON));
        ApiException error = assertThrows(ApiException.class,
                () -> provider.chat(List.of(Map.of("role", "user", "content", "Hello")), "req-8"));
        assertEquals("OLLAMA_RESPONSE_INVALID", error.getCode());
        server.verify();
    }

    @Test
    void streamsNdjsonDeltasImmediatelyAndReturnsTheCompleteResponse() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andExpect(content().json("""
                        {"model":"qwen2.5:3b","stream":true,"keep_alive":"30m",
                         "options":{"num_ctx":4096,"num_predict":220,"num_thread":4}}
                        """, false))
                .andRespond(withSuccess("""
                        {"message":{"role":"assistant","content":"Hello"},"done":false}
                        {"message":{"role":"assistant","content":" there."},"done":false}
                        {"message":{"role":"assistant","content":""},"done":true}
                        """, org.springframework.http.MediaType.parseMediaType("application/x-ndjson")));

        java.util.List<String> deltas = new java.util.ArrayList<>();
        String answer = provider.chatStream(
                List.of(Map.of("role", "user", "content", "Hello")), "req-stream", deltas::add);
        assertEquals(List.of("Hello", " there."), deltas);
        assertEquals("Hello there.", answer);
        server.verify();
    }

    @Test
    void streamProviderErrorIsSafeAndDoesNotReturnPartialTextAsSuccess() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andRespond(withSuccess("{\"error\":\"model qwen2.5:3b not found\"}\n",
                        org.springframework.http.MediaType.parseMediaType("application/x-ndjson")));
        ApiException error = assertThrows(ApiException.class, () -> provider.chatStream(
                List.of(Map.of("role", "user", "content", "Hello")), "req-stream-error", ignored -> {}));
        assertEquals("OLLAMA_MODEL_NOT_FOUND", error.getCode());
        assertFalse(error.getMessage().contains("qwen2.5:3b"));
        server.verify();
    }

    @Test
    void prematureStreamEndDoesNotBecomeACompleteResponse() {
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andRespond(withSuccess("{\"message\":{\"content\":\"partial\"},\"done\":false}\n",
                        org.springframework.http.MediaType.parseMediaType("application/x-ndjson")));
        ApiException error = assertThrows(ApiException.class, () -> provider.chatStream(
                List.of(Map.of("role", "user", "content", "Hello")), "req-stream-ended", ignored -> {}));
        assertEquals("OLLAMA_STREAM_INTERRUPTED", error.getCode());
        server.verify();
    }
}
