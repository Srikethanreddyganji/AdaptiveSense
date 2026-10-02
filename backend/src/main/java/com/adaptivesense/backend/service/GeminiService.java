package com.adaptivesense.backend.service;

import com.adaptivesense.backend.entity.Conversation;
import com.adaptivesense.backend.repository.ConversationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    private static final Logger log =
            LoggerFactory.getLogger(GeminiService.class);

    private static final String PRIMARY_MODEL = "gemini-3.8-flash";
    private static final String FALLBACK_MODEL = "gemini-3.6-flash";

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

        if (apiKey == null
                || apiKey.trim().isEmpty()
                || "your-gemini-api-key".equalsIgnoreCase(apiKey.trim())) {

            log.error("Gemini API key is not configured.");

            return "I'm having trouble connecting to my response service right now. "
                    + "Please try again in a moment.";
        }

        String configuredModel =
                modelName == null || modelName.isBlank()
                        ? PRIMARY_MODEL
                        : modelName.trim();

        log.info("Using Gemini model: {}", configuredModel);

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

        String userMemory = userMemoryService.getMemory(userId);

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
                "maxOutputTokens", 300
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

        String response =
                callGemini(
                        configuredModel,
                        request
                );

        if (response != null && !response.isBlank()) {
            return response;
        }

        /*
         * ---------------------------------------------------------
         * FALLBACK MODEL
         * ---------------------------------------------------------
         */

        if (!FALLBACK_MODEL.equals(configuredModel)) {

            log.warn(
                    "Primary Gemini model unavailable. Trying fallback model: {}",
                    FALLBACK_MODEL
            );

            response =
                    callGemini(
                            FALLBACK_MODEL,
                            request
                    );

            if (response != null && !response.isBlank()) {
                return response;
            }
        }

        /*
         * ---------------------------------------------------------
         * FINAL ERROR
         * ---------------------------------------------------------
         */

        log.error(
                "All Gemini response generation attempts failed."
        );

        return "I'm having trouble generating a response right now. "
                + "Please try sending your message again.";
    }

    /*
     * -------------------------------------------------------------
     * GEMINI API CALL
     * -------------------------------------------------------------
     */

    private String callGemini(
            String selectedModel,
            Map<String, Object> request) {

        String targetUrl =
                "https://generativelanguage.googleapis.com/v1beta/models/"
                        + selectedModel
                        + ":generateContent?key="
                        + apiKey.trim();

        int maxAttempts = 2;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {

            try {

                log.info(
                        "Sending request to Gemini model {} (attempt {}/{})",
                        selectedModel,
                        attempt,
                        maxAttempts
                );

                Map<String, Object> response =
                        restClient
                                .post()
                                .uri(targetUrl)
                                .header(
                                        "Content-Type",
                                        "application/json"
                                )
                                .header(
                                        "x-goog-api-key",
                                        apiKey.trim()
                                )
                                .body(request)
                                .retrieve()
                                .body(Map.class);

                if (response == null) {

                    log.error(
                            "Gemini returned an empty response."
                    );

                    continue;
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

                    log.error(
                            "Gemini returned no candidates: {}",
                            response
                    );

                    continue;
                }

                Object firstCandidateObject =
                        candidates.get(0);

                if (!(firstCandidateObject instanceof Map<?, ?> firstCandidate)) {

                    log.error(
                            "Invalid Gemini candidate."
                    );

                    continue;
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

                    continue;
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

                    continue;
                }

                Object firstPartObject =
                        parts.get(0);

                if (!(firstPartObject instanceof Map<?, ?> firstPart)) {

                    log.error(
                            "Invalid Gemini response part."
                    );

                    continue;
                }

                Object textObject =
                        firstPart.get("text");

                if (textObject == null) {

                    log.error(
                            "Gemini returned no response text."
                    );

                    continue;
                }

                String generatedText =
                        textObject.toString().trim();

                if (generatedText.isBlank()) {

                    log.error(
                            "Gemini returned empty response text."
                    );

                    continue;
                }

                log.info(
                        "Gemini response generated successfully using {}.",
                        selectedModel
                );

                return generatedText;

            } catch (HttpServerErrorException.ServiceUnavailable e) {

                /*
                 * 503 means Gemini is temporarily unavailable.
                 */

                log.warn(
                        "Gemini model {} returned 503 Service Unavailable.",
                        selectedModel
                );

                if (attempt < maxAttempts) {

                    try {

                        long waitTime = 1500L * attempt;

                        log.info(
                                "Waiting {} ms before retrying {}.",
                                waitTime,
                                selectedModel
                        );

                        Thread.sleep(waitTime);

                    } catch (InterruptedException interruptedException) {

                        Thread.currentThread().interrupt();

                        return null;
                    }
                }

            } catch (Exception e) {

                log.error(
                        "Gemini API call failed for model {}: {}",
                        selectedModel,
                        e.getMessage()
                );

                /*
                 * Do not retry obvious configuration or request errors
                 * multiple times.
                 */

                return null;
            }
        }

        return null;
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