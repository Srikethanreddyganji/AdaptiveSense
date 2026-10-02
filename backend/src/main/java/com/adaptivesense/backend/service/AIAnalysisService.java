package com.adaptivesense.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class AIAnalysisService {

    private static final Logger log =
            LoggerFactory.getLogger(AIAnalysisService.class);

    private final RestClient restClient;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Value("${ai.service.internal-key:}")
    private String internalKey;

    public AIAnalysisService(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    private String resolveAiServiceUrl() {
        if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
            return "http://localhost:8000";
        }
        String trimmed = aiServiceUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            if (trimmed.contains(".onrender.com")) {
                trimmed = "https://" + trimmed;
            } else {
                trimmed = "http://" + trimmed;
            }
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public Map<String, Object> analyze(
            String currentMessage,
            Object previousConversation,
            Object previousEmotion) {

        Map<String, Object> request = Map.of(
                "text",
                currentMessage != null
                        ? currentMessage
                        : "",

                "previous_conversation",
                previousConversation != null
                        ? previousConversation
                        : "",

                "previous_emotion",
                previousEmotion != null
                        ? previousEmotion
                        : Map.of()
        );

        String baseUrl = resolveAiServiceUrl();
        String targetUrl = baseUrl + "/analyze";

        try {
            log.info("Sending NLP analysis request to: {}", targetUrl);
            log.debug("AI REQUEST: {}", request);

            RestClient.RequestBodySpec spec =
                    restClient
                            .post()
                            .uri(targetUrl)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON);

            if (internalKey != null && !internalKey.isBlank()) {
                spec = spec.header("X-Internal-Key", internalKey);
            }

            Map<String, Object> response =
                    spec
                            .body(request)
                            .retrieve()
                            .body(Map.class);

            if (response == null) {
                log.warn("AI service returned an empty response");
                return emptyAnalysis();
            }

            log.info("AI RESPONSE RECEIVED SUCCESSFULLY from {}", targetUrl);
            return response;

        } catch (Exception e) {
            log.error("AI analysis service request failed at {}: {}", targetUrl, e.getMessage());
            return emptyAnalysis();
        }
    }

    private Map<String, Object> emptyAnalysis() {
        return Map.of(
                "nlp",
                Map.of(),

                "social_cues",
                Map.of()
        );
    }
}