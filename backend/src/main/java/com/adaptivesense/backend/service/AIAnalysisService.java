package com.adaptivesense.backend.service;

import com.adaptivesense.backend.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

@Service
public class AIAnalysisService {

    private static final Logger log =
            LoggerFactory.getLogger(AIAnalysisService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Value("${ai.service.internal-key:}")
    private String internalKey;

    @Value("${app.environment:${ENVIRONMENT:development}}")
    private String environment;

    public AIAnalysisService(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        // A cold Whisper model load plus decoding/analysis can exceed the normal API timeout.
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(180));
        this.restClient = builder.clone().requestFactory(requestFactory).build();
        this.objectMapper = new ObjectMapper();
    }

    private void checkInternalKeySecurity() {
        boolean isProduction = "production".equalsIgnoreCase(environment)
                || "prod".equalsIgnoreCase(environment);
        if (isProduction && (internalKey == null || internalKey.isBlank())) {
            log.error("Internal API key is not configured for production environment");
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "AI service authentication is not configured.",
                    "INTERNAL_AUTHENTICATION_FAILURE"
            );
        }
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
        return analyze(currentMessage, previousConversation, previousEmotion, null);
    }

    public Map<String, Object> analyze(
            String currentMessage,
            Object previousConversation,
            Object previousEmotion,
            String requestId) {

        checkInternalKeySecurity();

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
            log.info("Sending NLP analysis request request_id={} to: {}",
                    requestId == null ? "unavailable" : requestId, targetUrl);

            RestClient.RequestBodySpec spec =
                    restClient
                            .post()
                            .uri(targetUrl)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON);

            if (internalKey != null && !internalKey.isBlank()) {
                spec = spec.header("X-Internal-Key", internalKey);
            }
            if (requestId != null && !requestId.isBlank()) {
                spec = spec.header("X-Request-ID", requestId);
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

            log.info("AI NLP analysis completed request_id={} from {}",
                    requestId == null ? "unavailable" : requestId, targetUrl);
            return response;

        } catch (Exception e) {
            log.error("AI analysis service request failed request_id={} at {} ({})",
                    requestId == null ? "unavailable" : requestId,
                    targetUrl, e.getClass().getSimpleName());
            return emptyAnalysis();
        }
    }

    public Map<String, Object> analyzeVoice(
            byte[] audioBytes,
            String filename,
            String contentType,
            String clientTranscript,
            Object previousConversation,
            Object previousEmotion) {
        return analyzeVoice(audioBytes, filename, contentType, clientTranscript,
                previousConversation, previousEmotion, null);
    }

    public Map<String, Object> analyzeVoice(
            byte[] audioBytes,
            String filename,
            String contentType,
            String clientTranscript,
            Object previousConversation,
            Object previousEmotion,
            String requestId) {

        checkInternalKeySecurity();

        String baseUrl = resolveAiServiceUrl();
        String targetUrl = baseUrl + "/analyze-voice";
        long analysisStarted = System.nanoTime();
        String correlationId = requestId == null || requestId.isBlank() ? "unavailable" : requestId;

        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource audioResource = new ByteArrayResource(audioBytes) {
                        @Override
                        public String getFilename() {
                            return (filename != null && !filename.isBlank())
                                    ? filename
                                    : "recording.webm";
                        }
                    };
            HttpHeaders audioHeaders = new HttpHeaders();
            audioHeaders.setContentType(parseAudioMediaType(contentType));
            body.add("audio", new HttpEntity<>(audioResource, audioHeaders));

            if (clientTranscript != null && !clientTranscript.isBlank()) {
                body.add("transcript", clientTranscript.trim());
            }

            String prevConvJson = objectMapper.writeValueAsString(
                    previousConversation != null ? previousConversation : List.of()
            );
            body.add("previous_conversation", prevConvJson);

            String prevEmotionJson = objectMapper.writeValueAsString(
                    previousEmotion != null ? previousEmotion : Map.of()
            );
            body.add("previous_emotion", prevEmotionJson);

            log.info("Sending voice analysis request request_id={} to: {} (payload: {} bytes)",
                    requestId == null ? "unavailable" : requestId,
                    targetUrl, audioBytes != null ? audioBytes.length : 0);

            RestClient.RequestBodySpec spec =
                    restClient
                            .post()
                            .uri(targetUrl)
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .accept(MediaType.APPLICATION_JSON);

            if (internalKey != null && !internalKey.isBlank()) {
                spec = spec.header("X-Internal-Key", internalKey);
            }
            if (requestId != null && !requestId.isBlank()) {
                spec = spec.header("X-Request-ID", requestId);
            }

            Map<String, Object> response =
                    spec
                            .body(body)
                            .retrieve()
                            .body(Map.class);

            if (response == null) {
                log.warn("AI voice service returned an empty response");
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI voice analysis service returned an empty response");
            }

            log.info("Voice AI analysis completed successfully request_id={} ai_service_round_trip_ms={} from {}",
                    correlationId, elapsedMs(analysisStarted), targetUrl);
            return response;

        } catch (RestClientResponseException exception) {
            String code = remoteErrorCode(exception);
            log.warn("AI voice service returned request_id={} status={} category={} ai_service_round_trip_ms={}",
                    correlationId, exception.getStatusCode().value(), code, elapsedMs(analysisStarted));
            throw new ApiException(statusFor(code), messageFor(code), code);
        } catch (ResourceAccessException exception) {
            boolean timeout = causedByTimeout(exception);
            String code = timeout ? "REQUEST_TIMEOUT" : "AI_SERVICE_UNAVAILABLE";
            log.error("AI voice service transport failed request_id={} at {} category={} cause={} ai_service_round_trip_ms={}",
                    correlationId, targetUrl, code, rootCause(exception).getClass().getSimpleName(),
                    elapsedMs(analysisStarted));
            throw new ApiException(statusFor(code), messageFor(code), code);
        } catch (Exception exception) {
            if (exception instanceof ApiException apiException) {
                throw apiException;
            }
            log.error("AI voice service request failed request_id={} at {} ({}) ai_service_round_trip_ms={}",
                    correlationId, targetUrl, exception.getClass().getSimpleName(), elapsedMs(analysisStarted));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    messageFor("AI_SERVICE_UNAVAILABLE"), "AI_SERVICE_UNAVAILABLE");
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private String remoteErrorCode(RestClientResponseException exception) {
        try {
            JsonNode root = objectMapper.readTree(exception.getResponseBodyAsString());
            JsonNode code = root.path("detail").path("code");
            if (!code.isTextual()) code = root.path("code");
            if (code.isTextual() && isKnownVoiceError(code.asText())) return code.asText();
        } catch (Exception ignored) {
            // Upstream body is intentionally not copied into logs or user messages.
        }
        return switch (exception.getStatusCode().value()) {
            case 401, 403 -> "INTERNAL_AUTHENTICATION_FAILURE";
            case 413 -> "RESOURCE_EXHAUSTION";
            case 400, 415 -> "AUDIO_DECODING_FAILURE";
            case 422 -> "TRANSCRIPTION_FAILURE";
            case 504 -> "REQUEST_TIMEOUT";
            default -> "AI_SERVICE_UNAVAILABLE";
        };
    }

    private MediaType parseAudioMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private boolean isKnownVoiceError(String code) {
        return switch (code) {
            case "AI_SERVICE_UNAVAILABLE", "INTERNAL_AUTHENTICATION_FAILURE",
                    "AUDIO_DECODING_FAILURE", "MODEL_LOADING_FAILURE",
                    "TRANSCRIPTION_FAILURE", "VOICE_ANALYSIS_FAILURE",
                    "REQUEST_TIMEOUT", "RESOURCE_EXHAUSTION" -> true;
            default -> false;
        };
    }

    private HttpStatus statusFor(String code) {
        return switch (code) {
            case "INTERNAL_AUTHENTICATION_FAILURE" -> HttpStatus.BAD_GATEWAY;
            case "AUDIO_DECODING_FAILURE", "TRANSCRIPTION_FAILURE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "MODEL_LOADING_FAILURE", "AI_SERVICE_UNAVAILABLE", "RESOURCE_EXHAUSTION" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "REQUEST_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "VOICE_ANALYSIS_FAILURE" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private String messageFor(String code) {
        return switch (code) {
            case "INTERNAL_AUTHENTICATION_FAILURE" -> "Voice analysis could not authenticate with its AI service. Contact support.";
            case "AUDIO_DECODING_FAILURE" -> "The audio recording could not be decoded. Please record it again.";
            case "MODEL_LOADING_FAILURE" -> "The transcription model is not available right now. Please try again shortly.";
            case "TRANSCRIPTION_FAILURE" -> "Speech could not be transcribed from the audio. Please try recording more clearly.";
            case "VOICE_ANALYSIS_FAILURE" -> "Acoustic voice analysis could not be completed. Please try again.";
            case "REQUEST_TIMEOUT" -> "Voice analysis took too long. Please try a shorter recording.";
            case "RESOURCE_EXHAUSTION" -> "The audio service is temporarily out of resources. Please try again later.";
            default -> "The voice analysis service is unavailable. Please try again shortly.";
        };
    }

    private boolean causedByTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }

    private Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
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
