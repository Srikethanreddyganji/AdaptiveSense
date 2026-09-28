package com.adaptivesense.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class AIAnalysisService {

    private static final Logger log =
            LoggerFactory.getLogger(AIAnalysisService.class);

    private final RestClient restClient;

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    @Value("${ai.service.internal-key:}")
    private String internalKey;

    public AIAnalysisService(RestClient.Builder builder) {
        this.restClient = builder.build();
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

        try {

            log.info(
                    "AI SERVICE URL: {}",
                    aiServiceUrl
            );

            log.debug(
                    "AI REQUEST: {}",
                    request
            );

            RestClient.RequestBodySpec spec =
                    restClient
                            .post()
                            .uri(aiServiceUrl + "/analyze")
                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )
                            .accept(
                                    MediaType.APPLICATION_JSON
                            );

            if (internalKey != null &&
                    !internalKey.isBlank()) {

                spec = spec.header(
                        "X-Internal-Key",
                        internalKey
                );
            }

            Map<String, Object> response =
                    spec
                            .body(request)
                            .retrieve()
                            .body(Map.class);

            if (response == null) {

                log.warn(
                        "AI service returned an empty response"
                );

                return emptyAnalysis();
            }

            log.info(
                    "AI RESPONSE RECEIVED SUCCESSFULLY"
            );

            return response;

        } catch (Exception e) {

            log.error(
                    "AI analysis service request failed",
                    e
            );

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