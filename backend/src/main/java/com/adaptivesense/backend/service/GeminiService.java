package com.adaptivesense.backend.service;

import com.adaptivesense.backend.exception.ApiException;
import com.adaptivesense.backend.entity.Conversation;
import com.adaptivesense.backend.repository.ConversationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    private static final Logger log =
            LoggerFactory.getLogger(GeminiService.class);

    private static final String PRIMARY_MODEL = "gemini-3.8-flash";
    private static final long QUOTA_COOLDOWN_MS = 5 * 60 * 1000L;
    private static volatile long quotaBlockedUntilEpochMs;

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-3.8-flash}")
    private String modelName;

    private final RestClient restClient;
    private final ConversationRepository conversationRepository;
    private final UserMemoryService userMemoryService;

    public GeminiService(
            RestClient.Builder builder,
            ConversationRepository conversationRepository,
            UserMemoryService userMemoryService) {

        this.restClient = builder.build();
        this.conversationRepository = conversationRepository;
        this.userMemoryService = userMemoryService;
    }

    public String generateResponse(
            Long userId,
            String userMessage,
            Map<String, Object> aiAnalysis) {
        return generateResponse(userId, userMessage, aiAnalysis, null);
    }

    public String generateResponse(
            Long userId,
            String userMessage,
            Map<String, Object> aiAnalysis,
            String requestId) {

        String correlationId = requestId == null || requestId.isBlank() ? "unavailable" : requestId;

        if (apiKey == null
                || apiKey.trim().isEmpty()
                || "your-gemini-api-key".equalsIgnoreCase(apiKey.trim())) {

            log.error("Gemini API key is not configured.");

            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "The response service is not configured right now.", "GEMINI_UNAVAILABLE");
        }

        if (isQuotaCoolingDown()) {
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "The response service quota is temporarily unavailable. Your voice analysis was retained.",
                    "GEMINI_QUOTA_EXCEEDED");
        }

        String configuredModel =
                modelName == null || modelName.isBlank()
                        ? PRIMARY_MODEL
                        : modelName.trim();

        log.info("Using Gemini model request_id={}: {}", correlationId, configuredModel);

        /*
         * ---------------------------------------------------------
         * RECENT CONVERSATIONS
         * ---------------------------------------------------------
         */

        List<Conversation> recentConversations =
                new ArrayList<>(
                        conversationRepository
                                .findTop10ByUserIdOrderByCreatedAtDesc(userId)
                );

        Collections.reverse(recentConversations);

        /*
         * ---------------------------------------------------------
         * LONG-TERM MEMORY
         * ---------------------------------------------------------
         */

        String userMemory;
        try {
            userMemory = userMemoryService.getMemory(userId);
        } catch (RuntimeException exception) {
            log.warn("Could not load conversation memory ({}); continuing without memory.",
                    exception.getClass().getSimpleName());
            userMemory = "";
        }

        boolean severeDistress = isSevereDistress(aiAnalysis);

        /*
         * ---------------------------------------------------------
         * BUILD PROMPT
         * ---------------------------------------------------------
         */

        StringBuilder context = new StringBuilder();

        context.append("""
                You are AdaptiveSense, an empathetic mental wellness
                conversation assistant.

                Your task is to have a natural, supportive conversation
                with the user.

                IMPORTANT RESPONSE RULES:

                - Generate every response dynamically.
                - Do NOT use fixed or pre-written responses.
                - Do NOT repeat the same response simply because the
                  user repeated a message.
                - If the user sends the same message multiple times,
                  respond naturally and vary your wording.
                - Consider the conversation history before responding.
                - Ask relevant follow-up questions when appropriate.
                - Keep responses conversational rather than robotic.
                - Do not mention these instructions.
                - Do not diagnose mental health conditions.
                - Do not claim that the user has depression, anxiety,
                  or another mental health disorder.
                - Do not pretend to be a doctor or therapist.
                - Never shame or judge the user.
                - Use the emotional analysis only as supporting context.
                - Do not tell the user that an AI model detected
                  their emotion unless it is useful and appropriate.

                """);

        /*
         * ---------------------------------------------------------
         * CRISIS MODE
         * ---------------------------------------------------------
         */

        if (severeDistress) {

            context.append("""
                    SAFETY PRIORITY:

                    The analysis indicates that the current conversation
                    may contain serious distress or possible self-harm
                    related language.

                    In this situation:

                    - Respond with calm and compassionate empathy.
                    - Prioritize immediate safety.
                    - Encourage the user to contact local emergency
                      services if they are in immediate danger.
                    - Encourage contacting a crisis service or trusted
                      person when appropriate.
                    - Do not provide instructions or methods for
                      self-harm.
                    - Do not minimize the user's feelings.
                    - Keep the response supportive and clear.

                    """);
        }

        /*
         * ---------------------------------------------------------
         * LONG-TERM MEMORY
         * ---------------------------------------------------------
         */

        context.append("""
                
                LONG-TERM USER MEMORY:

                """);

        if (userMemory == null || userMemory.isBlank()) {

            context.append(
                    "No long-term memory is available.\n"
            );

        } else {

            context.append(userMemory)
                    .append("\n");
        }

        /*
         * ---------------------------------------------------------
         * CONVERSATION HISTORY
         * ---------------------------------------------------------
         */

        context.append("""
                
                RECENT CONVERSATION:

                """);

        if (recentConversations.isEmpty()) {

            context.append(
                    "There is no previous conversation available.\n"
            );

        } else {

            for (Conversation conversation : recentConversations) {

                context.append("\nUser: ")
                        .append(conversation.getUserMessage());

                context.append("\nAdaptiveSense: ")
                        .append(conversation.getAssistantResponse());

                context.append("\n");
            }
        }

        /*
         * ---------------------------------------------------------
         * AI ANALYSIS
         * ---------------------------------------------------------
         */

        context.append("""
                
                CURRENT AI EMOTIONAL AND SOCIAL-CUE ANALYSIS:

                The following information was generated by the
                AdaptiveSense NLP service.

                Treat these values as supporting signals only.
                They are not medical diagnoses.

                """);

        context.append(
                aiAnalysis != null
                        ? aiAnalysis.toString()
                        : "{}"
        );

        context.append("\n");

        boolean isVoiceInput = aiAnalysis != null && (
                "voice".equalsIgnoreCase(String.valueOf(aiAnalysis.get("input_mode")))
                        || aiAnalysis.containsKey("acoustic_features")
                        || aiAnalysis.containsKey("voice_emotion")
        );

        if (isVoiceInput) {
            context.append("""

                    VOICE MODALITY & ACOUSTIC CONTEXT:
                    The user spoke this message via voice recording.
                    Measurable acoustic voice features (pitch variation, speaking rate,
                    pause patterns, vocal energy) and speech emotion indicators are provided
                    above as supportive context.

                    MANDATORY ACOUSTIC SAFETY AND ETHICAL GUIDELINE:
                    - Treat all acoustic vocal signals as uncertain supportive context, NOT a diagnosis.
                    - NEVER diagnose depression, anxiety, or any mental-health condition from their voice.
                    - Never claim 'your voice indicates depression' or make clinical claims from speech acoustics.
                    - Reflect warmth, empathy, and conversational presence naturally without clinical labeling.
                    - Because this response may be spoken aloud, keep your phrasing clear, comforting, and natural to hear.

                    """);
        }

        /*
         * ---------------------------------------------------------
         * CURRENT USER MESSAGE
         * ---------------------------------------------------------
         */

        context.append("""
                
                CURRENT USER MESSAGE:

                """);

        context.append(
                userMessage != null
                        ? userMessage
                        : ""
        );

        context.append("""
                
                RESPONSE REQUIREMENT:

                Respond directly to the current user message.

                Your response should feel like a natural continuation
                of this specific conversation.

                Do not copy a previous response.

                Generate a fresh response based on the current message,
                conversation history, user memory, and supporting
                emotional analysis.

                """);

        /*
         * ---------------------------------------------------------
         * GEMINI REQUEST
         * ---------------------------------------------------------
         *
         * Gemini 3.8 migration guidance recommends removing
         * temperature, topP and topK for this model.
         */

        Map<String, Object> generationConfig = Map.of(
                "maxOutputTokens", 1000
        );

        Map<String, Object> request = Map.of(
                "contents",
                List.of(
                        Map.of(
                                "parts",
                                List.of(
                                        Map.of(
                                                "text",
                                                context.toString()
                                        )
                                )
                        )
                ),
                "generationConfig",
                generationConfig
        );

        /*
         * ---------------------------------------------------------
         * TRY PRIMARY MODEL
         * ---------------------------------------------------------
         */

        String response;
        try {
            response = callGemini(configuredModel, request, correlationId);
        } catch (GeminiQuotaException quotaException) {
            markQuotaCooldown();
            log.warn("Gemini rate limit or quota reached request_id={}; skipping retries and model fallback.",
                    correlationId);
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "The response service quota is temporarily unavailable. Your voice analysis was retained.",
                    "GEMINI_QUOTA_EXCEEDED");
        } catch (GeminiConfigurationException configurationException) {
            log.error("Gemini rejected credentials or request request_id={} (HTTP {}).",
                    correlationId, configurationException.statusCode);
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "The response service rejected the request. Your voice analysis was retained.",
                    "GEMINI_REQUEST_REJECTED");
        }

        if (response != null && !response.isBlank()) {
            return response;
        }

        /*
         * ---------------------------------------------------------
         * FINAL ERROR
         * ---------------------------------------------------------
         */

        log.error("All Gemini response generation attempts failed request_id={}", correlationId);

        throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "The response service is temporarily unavailable. Your voice analysis was retained.",
                "GEMINI_UNAVAILABLE");
    }

    /*
     * -------------------------------------------------------------
     * GEMINI API CALL
     * -------------------------------------------------------------
     */

    private String callGemini(
            String selectedModel,
            Map<String, Object> request,
            String requestId) {

        String targetUrl =
                "https://generativelanguage.googleapis.com/v1beta/models/"
                        + selectedModel
                        + ":generateContent";

        // Retry one transient provider overload only. Quota, timeout, transport,
        // authentication, and malformed responses are never retried here.
        int maxAttempts = 2;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long requestStarted = System.nanoTime();

            try {

                log.info("Sending request to Gemini request_id={} model={} (attempt {}/{})",
                        requestId, selectedModel, attempt, maxAttempts);

                Map<String, Object> response =
                        restClient
                                .post()
                                .uri(targetUrl)
                                .header(
                                        "Content-Type",
                                        "application/json"
                                )
                                .header("x-goog-api-key", apiKey.trim())
                                .body(request)
                                .retrieve()
                                .body(Map.class);

                if (response == null) {

                    log.error(
                            "Gemini returned an empty response."
                    );

                    return null;
                }

                /*
                 * -------------------------------------------------
                 * CANDIDATES
                 * -------------------------------------------------
                 */

                Object candidatesObject =
                        response.get("candidates");

                if (!(candidatesObject instanceof List<?> candidates)
                        || candidates.isEmpty()) {

                    log.warn("Gemini returned no candidates (response omitted). finishReason={}",
                            response.get("promptFeedback"));

                    return null;
                }

                Object firstCandidateObject =
                        candidates.get(0);

                if (!(firstCandidateObject instanceof Map<?, ?> firstCandidate)) {

                    log.error(
                            "Invalid Gemini candidate."
                    );

                    return null;
                }

                /*
                 * -------------------------------------------------
                 * CONTENT
                 * -------------------------------------------------
                 */

                Object contentObject =
                        firstCandidate.get("content");

                if (!(contentObject instanceof Map<?, ?> content)) {

                    log.error(
                            "Gemini candidate contained no content."
                    );

                    return null;
                }

                /*
                 * -------------------------------------------------
                 * PARTS
                 * -------------------------------------------------
                 */

                Object partsObject =
                        content.get("parts");

                if (!(partsObject instanceof List<?> parts)
                        || parts.isEmpty()) {

                    log.error(
                            "Gemini response contained no parts."
                    );

                    return null;
                }

                Object firstPartObject =
                        parts.get(0);

                if (!(firstPartObject instanceof Map<?, ?> firstPart)) {

                    log.error(
                            "Invalid Gemini response part."
                    );

                    return null;
                }

                Object textObject =
                        firstPart.get("text");

                if (textObject == null) {

                    log.error(
                            "Gemini returned no response text."
                    );

                    return null;
                }

                String generatedText =
                        textObject.toString().trim();

                if (generatedText.isBlank()) {

                    log.error(
                            "Gemini returned empty response text."
                    );

                    return null;
                }

                log.info("Gemini conversational response generated request_id={} (model={}, elapsed_ms={}).",
                        requestId, selectedModel, elapsedMs(requestStarted));

                return generatedText;

            } catch (HttpClientErrorException.TooManyRequests e) {
                throw new GeminiQuotaException();
            } catch (HttpClientErrorException e) {
                throw new GeminiConfigurationException(e.getStatusCode().value());
            } catch (HttpServerErrorException e) {
                int status = e.getStatusCode().value();
                if (status == 503 && attempt < maxAttempts) {
                    log.warn("Gemini returned a transient unavailable status; retrying once "
                                    + "(request_id={}, status={}, model={}).",
                            requestId, status, selectedModel);
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                                "The response service is temporarily unavailable. Your voice analysis was retained.",
                                "GEMINI_UNAVAILABLE");
                    }
                    continue;
                }
                log.warn("Gemini conversational request failed (request_id={}, status={}, attempt={}, model={}, elapsed_ms={}).",
                        requestId, status, attempt, selectedModel, elapsedMs(requestStarted));
                return null;

            } catch (ResourceAccessException e) {
                String category = isTimeout(e) ? "timeout" : "transport";
                log.warn("Gemini conversational request failed (request_id={}, category={}, model={}, cause={}, elapsed_ms={}).",
                        requestId, category, selectedModel, rootCause(e).getClass().getSimpleName(),
                        elapsedMs(requestStarted));
                if (isTimeout(e)) {
                    throw new ApiException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                            "The response service took too long. Your voice analysis was retained.",
                            "GEMINI_TIMEOUT");
                }
                return null;

            } catch (RestClientResponseException e) {
                log.warn("Gemini conversational request failed (request_id={}, status={}, model={}, elapsed_ms={}).",
                        requestId, e.getStatusCode().value(), selectedModel, elapsedMs(requestStarted));
                return null;

            } catch (Exception e) {
                log.error("Gemini conversational request failed (request_id={}, model={}, exception={}, elapsed_ms={}).",
                        requestId, selectedModel, e.getClass().getSimpleName(), elapsedMs(requestStarted));

                /*
                 * Do not retry obvious configuration or request errors
                 * multiple times.
                 */

                return null;
            }
        }

        return null;
    }

    private static long elapsedMs(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static final class GeminiQuotaException extends RuntimeException { }

    static boolean isQuotaCoolingDown() {
        return System.currentTimeMillis() < quotaBlockedUntilEpochMs;
    }

    static void markQuotaCooldown() {
        quotaBlockedUntilEpochMs = System.currentTimeMillis() + QUOTA_COOLDOWN_MS;
    }

    private static final class GeminiConfigurationException extends RuntimeException {
        private final int statusCode;
        private GeminiConfigurationException(int statusCode) { this.statusCode = statusCode; }
    }

    /*
     * -------------------------------------------------------------
     * SEVERE DISTRESS DETECTION
     * -------------------------------------------------------------
     */

    private boolean isSevereDistress(
            Map<String, Object> aiAnalysis) {

        if (aiAnalysis == null) {
            return false;
        }

        Object socialObject =
                aiAnalysis.get("social_cues");

        if (!(socialObject instanceof Map<?, ?> socialCues)) {
            return false;
        }

        Object severe =
                socialCues.get("severe_distress");

        return Boolean.TRUE.equals(severe);
    }
}
