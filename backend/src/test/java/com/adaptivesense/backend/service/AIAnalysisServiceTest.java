package com.adaptivesense.backend.service;

import com.adaptivesense.backend.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AIAnalysisServiceTest {

    private AIAnalysisService aiAnalysisService;

    @BeforeEach
    void setUp() {
        aiAnalysisService = new AIAnalysisService(RestClient.builder());
    }

    @Test
    void failClosedInProductionWhenInternalKeyIsMissing() {
        ReflectionTestUtils.setField(aiAnalysisService, "environment", "production");
        ReflectionTestUtils.setField(aiAnalysisService, "internalKey", "");

        ApiException exc = assertThrows(ApiException.class, () ->
                aiAnalysisService.analyze("Hello", "", Map.of())
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exc.getStatus());
        assertEquals("INTERNAL_AUTHENTICATION_FAILURE", exc.getCode());
    }

    @Test
    void allowsDevelopmentWithoutInternalKey() {
        ReflectionTestUtils.setField(aiAnalysisService, "environment", "development");
        ReflectionTestUtils.setField(aiAnalysisService, "internalKey", "");
        ReflectionTestUtils.setField(aiAnalysisService, "aiServiceUrl", "http://localhost:9999"); // unreachable port

        // Should not throw security exception; should return graceful empty fallback
        Map<String, Object> result = aiAnalysisService.analyze("Hello", "", Map.of());
        assertNotNull(result);
        assertTrue(result.containsKey("nlp"));
    }

    @Test
    void analyzePropagatesRequestIdToAiService() throws Exception {
        AtomicReference<String> receivedRequestId = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze", exchange -> {
            receivedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            byte[] response = "{\"nlp\":{},\"social_cues\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ReflectionTestUtils.setField(aiAnalysisService, "aiServiceUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            aiAnalysisService.analyze("hello", List.of(), Map.of(), "server-generated-id");
            assertEquals("server-generated-id", receivedRequestId.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeVoice_unreachable_failsWithoutInventingAnalysis() {
        ReflectionTestUtils.setField(aiAnalysisService, "environment", "development");
        ReflectionTestUtils.setField(aiAnalysisService, "aiServiceUrl", "http://localhost:9999");

        ApiException exception = assertThrows(ApiException.class, () ->
                aiAnalysisService.analyzeVoice(
                        new byte[]{1, 2, 3},
                        "audio.webm",
                        "audio/webm",
                        "fallback transcript",
                        "",
                        Map.of()
                )
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("AI_SERVICE_UNAVAILABLE", exception.getCode());
    }

    @Test
    void analyzeVoice_mapsStructuredDecoderFailureAndPreservesMultipartFields() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        AtomicReference<String> contentType = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze-voice", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"detail\":{\"code\":\"AUDIO_DECODING_FAILURE\",\"message\":\"safe\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ReflectionTestUtils.setField(aiAnalysisService, "aiServiceUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            ApiException exception = assertThrows(ApiException.class, () ->
                    aiAnalysisService.analyzeVoice(
                            new byte[]{1, 2, 3}, "recording.webm", "audio/webm",
                            "client transcript", List.of(Map.of("user", "test")), Map.of()
                    )
            );
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatus());
            assertEquals("AUDIO_DECODING_FAILURE", exception.getCode());
            assertTrue(contentType.get().startsWith("multipart/form-data"));
            assertTrue(contentType.get().toLowerCase().contains("boundary="));
            assertTrue(requestBody.get().contains("name=\"audio\""));
            assertTrue(requestBody.get().contains("filename=\"recording.webm\""));
            assertTrue(requestBody.get().contains("Content-Type: audio/webm"));
            assertTrue(requestBody.get().contains("name=\"transcript\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeVoice_distinguishesInternalAuthenticationFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze-voice", exchange -> {
            byte[] response = "{\"detail\":{\"code\":\"INTERNAL_AUTHENTICATION_FAILURE\",\"message\":\"safe\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ReflectionTestUtils.setField(aiAnalysisService, "aiServiceUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            ApiException exception = assertThrows(ApiException.class, () ->
                    aiAnalysisService.analyzeVoice(new byte[]{1}, "recording.webm", "audio/webm", "hi", null, null)
            );
            assertEquals("INTERNAL_AUTHENTICATION_FAILURE", exception.getCode());
            assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        } finally {
            server.stop(0);
        }
    }
}
