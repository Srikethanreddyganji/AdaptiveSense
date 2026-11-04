package com.adaptivesense.backend.service;

import com.adaptivesense.backend.repository.ConversationRepository;
import com.adaptivesense.backend.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.client.response.MockRestResponseCreators;

import java.util.List;
import java.util.Map;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;

@ExtendWith(MockitoExtension.class)
class GeminiServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private UserMemoryService userMemoryService;

    private GeminiService geminiService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(GeminiService.class, "quotaBlockedUntilEpochMs", 0L);
        geminiService = new GeminiService(
                RestClient.builder(),
                conversationRepository,
                userMemoryService
        );
    }

    @Test
    void unconfiguredApiKeyReturnsCategorizedFailureInsteadOfInventedResponse() {
        ReflectionTestUtils.setField(geminiService, "apiKey", "");

        ApiException error = assertThrows(ApiException.class, () -> geminiService.generateResponse(
                1L,
                "Hello",
                Map.of("nlp", Map.of())
        ));

        assertEquals("GEMINI_UNAVAILABLE", error.getCode());
    }

    @Test
    void isSevereDistress_detectedFromAiAnalysis() {
        Map<String, Object> severeDistressAnalysis = Map.of(
                "social_cues", Map.of("severe_distress", true)
        );

        Boolean result = ReflectionTestUtils.invokeMethod(
                geminiService,
                "isSevereDistress",
                severeDistressAnalysis
        );

        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void quotaResponseDoesNotRetryOrFallbackAndApiKeyIsHeaderOnly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        geminiService = new GeminiService(builder, conversationRepository, userMemoryService);
        ReflectionTestUtils.setField(geminiService, "apiKey", "test-secret");
        ReflectionTestUtils.setField(geminiService, "modelName", "gemini-3.8-flash");
        when(conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(userMemoryService.getMemory(1L)).thenReturn("");

        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"))
                .andExpect(method(POST))
                .andExpect(header("x-goog-api-key", "test-secret"))
                .andRespond(MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        ApiException error = assertThrows(ApiException.class,
                () -> geminiService.generateResponse(1L, "I feel overwhelmed", Map.of()));

        assertEquals("GEMINI_QUOTA_EXCEEDED", error.getCode());
        server.verify();
    }

    @Test
    void repeatedServiceUnavailableIsRetriedOnceThenReturnedAsUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        geminiService = new GeminiService(builder, conversationRepository, userMemoryService);
        configureResponseCall();
        when(userMemoryService.getMemory(1L)).thenReturn("");
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo(geminiUrl()))
                    .andExpect(method(POST))
                    .andRespond(MockRestResponseCreators.withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                            .body("{\"error\":{\"message\":\"provider detail must not be logged\"}}"));
        }

        ApiException error = assertThrows(ApiException.class,
                () -> geminiService.generateResponse(1L, "I feel overwhelmed", Map.of()));

        assertEquals("GEMINI_UNAVAILABLE", error.getCode());
        server.verify();
    }

    @Test
    void transientServiceUnavailableRetriesOnceAndReturnsSuccessfulProviderResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        geminiService = new GeminiService(builder, conversationRepository, userMemoryService);
        configureResponseCall();
        when(userMemoryService.getMemory(1L)).thenReturn("");
        server.expect(requestTo(geminiUrl()))
                .andExpect(method(POST))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(geminiUrl()))
                .andExpect(method(POST))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"I am here with you."}]}}]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals("I am here with you.",
                geminiService.generateResponse(1L, "I feel overwhelmed", Map.of()));
        server.verify();
    }

    @Test
    void conversationTimeoutReturnsFallbackWithoutRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        geminiService = new GeminiService(builder, conversationRepository, userMemoryService);
        configureResponseCall();
        when(userMemoryService.getMemory(1L)).thenReturn("");
        server.expect(requestTo(geminiUrl()))
                .andExpect(method(POST))
                .andRespond(MockRestResponseCreators.withException(new SocketTimeoutException("read timed out")));

        ApiException error = assertThrows(ApiException.class,
                () -> geminiService.generateResponse(1L, "I feel overwhelmed", Map.of()));

        assertEquals("GEMINI_TIMEOUT", error.getCode());
        server.verify();
    }

    @Test
    void memoryReadFailureDoesNotPreventSuccessfulConversationResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        geminiService = new GeminiService(builder, conversationRepository, userMemoryService);
        configureResponseCall();
        when(userMemoryService.getMemory(1L)).thenThrow(
                new DataAccessResourceFailureException("private database detail"));
        when(conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        server.expect(requestTo(geminiUrl()))
                .andExpect(method(POST))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"I am here with you."}]}}]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals("I am here with you.",
                geminiService.generateResponse(1L, "I feel overwhelmed", Map.of()));
        server.verify();
    }

    private void configureResponseCall() {
        ReflectionTestUtils.setField(geminiService, "apiKey", "test-secret");
        ReflectionTestUtils.setField(geminiService, "modelName", "gemini-3.8-flash");
        when(conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
    }

    private static String geminiUrl() {
        return "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent";
    }
}
