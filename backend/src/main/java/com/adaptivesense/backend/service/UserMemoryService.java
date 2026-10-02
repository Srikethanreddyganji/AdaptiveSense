package com.adaptivesense.backend.service;

import com.adaptivesense.backend.entity.UserMemory;
import com.adaptivesense.backend.repository.UserMemoryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class UserMemoryService {

    private static final Logger log =
            LoggerFactory.getLogger(UserMemoryService.class);

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.model:gemini-3.8-flash}")
    private String modelName;

    private final UserMemoryRepository userMemoryRepository;
    private final RestClient restClient;

    public UserMemoryService(
            UserMemoryRepository userMemoryRepository,
            RestClient.Builder builder) {

        this.userMemoryRepository = userMemoryRepository;
        this.restClient = builder.build();
    }

    public String getMemory(Long userId) {

        return userMemoryRepository
                .findByUserId(userId)
                .map(UserMemory::getMemory)
                .orElse("");
    }

    @Async("memoryTaskExecutor")
    public void updateMemoryAsync(
            Long userId,
            String userMessage) {

        try {

            String currentMemory = getMemory(userId);

            String prompt = """
                    You are the memory system for AdaptiveSense,
                    a supportive mental wellness chatbot.

                    Your job is to identify useful LONG-TERM information
                    from the user's message.

                    Only remember information that could genuinely help
                    AdaptiveSense understand the user in future conversations.

                    Examples of useful memory:
                    - Important ongoing goals
                    - Projects or studies
                    - Presentation or exam preparation
                    - User preferences
                    - Important recurring situations
                    - Things the user explicitly wants AdaptiveSense to remember

                    Do NOT store:
                    - Passwords
                    - API keys
                    - Financial account information
                    - Private authentication information
                    - Temporary casual statements
                    - Sensitive medical diagnoses

                    Keep the memory short and factual.

                    Existing memory:
                    """
                    + currentMemory
                    + """

                    New user message:
                    """
                    + userMessage
                    + """

                    Update the memory if necessary.

                    Return ONLY the updated memory as plain text.
                    If there is nothing useful to remember, return the
                    existing memory unchanged.
                    """;

            String primaryModel =
                    modelName == null || modelName.isBlank()
                            ? "gemini-3.8-flash"
                            : modelName.trim();

            String fallbackModel = "gemini-3.6-flash";

            /*
             * ---------------------------------------------------------
             * PRIMARY MODEL
             * ---------------------------------------------------------
             */

            log.info(
                    "Updating memory using Gemini model: {}",
                    primaryModel
            );

            String updatedMemory =
                    generateMemoryWithRetry(
                            primaryModel,
                            prompt,
                            2
                    );

            /*
             * ---------------------------------------------------------
             * FALLBACK MODEL
             * ---------------------------------------------------------
             */

            if (updatedMemory == null
                    && !primaryModel.equals(fallbackModel)) {

                log.warn(
                        "Primary Gemini model unavailable for memory update. "
                                + "Trying fallback model: {}",
                        fallbackModel
                );

                updatedMemory =
                        generateMemoryWithRetry(
                                fallbackModel,
                                prompt,
                                2
                        );
            }

            /*
             * ---------------------------------------------------------
             * SAVE MEMORY
             * ---------------------------------------------------------
             */

            if (updatedMemory == null
                    || updatedMemory.isBlank()) {

                log.warn(
                        "No memory update generated for user {}.",
                        userId
                );

                return;
            }

            updatedMemory = updatedMemory.trim();

            if (updatedMemory.length() > 3000) {

                updatedMemory =
                        updatedMemory.substring(0, 3000);
            }

            saveMemory(userId, updatedMemory);

            log.info(
                    "Memory updated successfully for user {}.",
                    userId
            );

        } catch (Exception e) {

            log.warn(
                    "Memory update failed for user {}: {}",
                    userId,
                    e.getMessage()
            );
        }
    }

    /*
     * -------------------------------------------------------------
     * GEMINI MEMORY GENERATION
     * -------------------------------------------------------------
     */

    private String generateMemoryWithRetry(
            String model,
            String prompt,
            int maxAttempts) {

        Map<String, Object> request =
                Map.of(
                        "contents",
                        List.of(
                                Map.of(
                                        "parts",
                                        List.of(
                                                Map.of(
                                                        "text",
                                                        prompt
                                                )
                                        )
                                )
                        )
                );

        String url =
                "https://generativelanguage.googleapis.com/v1beta/models/"
                        + model
                        + ":generateContent";

        for (int attempt = 1;
             attempt <= maxAttempts;
             attempt++) {

            try {

                log.info(
                        "Sending memory request to Gemini model {} "
                                + "(attempt {}/{})",
                        model,
                        attempt,
                        maxAttempts
                );

                Map<String, Object> response =
                        restClient
                                .post()
                                .uri(url)
                                .header(
                                        "x-goog-api-key",
                                        apiKey
                                )
                                .header(
                                        "Content-Type",
                                        "application/json"
                                )
                                .body(request)
                                .retrieve()
                                .body(Map.class);

                if (response == null) {

                    log.warn(
                            "Gemini returned an empty memory response "
                                    + "from model {}.",
                            model
                    );

                    continue;
                }

                /*
                 * -----------------------------------------------------
                 * CANDIDATES
                 * -----------------------------------------------------
                 */

                Object candidatesObject =
                        response.get("candidates");

                if (!(candidatesObject instanceof List<?> candidates)
                        || candidates.isEmpty()) {

                    log.warn(
                            "Gemini returned no candidates for memory "
                                    + "using model {}.",
                            model
                    );

                    continue;
                }

                /*
                 * -----------------------------------------------------
                 * FIRST CANDIDATE
                 * -----------------------------------------------------
                 */

                Object candidateObject =
                        candidates.get(0);

                if (!(candidateObject instanceof Map<?, ?> candidate)) {

                    log.warn(
                            "Invalid Gemini memory candidate from model {}.",
                            model
                    );

                    continue;
                }

                /*
                 * -----------------------------------------------------
                 * CONTENT
                 * -----------------------------------------------------
                 */

                Object contentObject =
                        candidate.get("content");

                if (!(contentObject instanceof Map<?, ?> content)) {

                    log.warn(
                            "Gemini memory response contained no content "
                                    + "from model {}.",
                            model
                    );

                    continue;
                }

                /*
                 * -----------------------------------------------------
                 * PARTS
                 * -----------------------------------------------------
                 */

                Object partsObject =
                        content.get("parts");

                if (!(partsObject instanceof List<?> parts)
                        || parts.isEmpty()) {

                    log.warn(
                            "Gemini memory response contained no parts "
                                    + "from model {}.",
                            model
                    );

                    continue;
                }

                Object firstPartObject =
                        parts.get(0);

                if (!(firstPartObject instanceof Map<?, ?> firstPart)) {

                    log.warn(
                            "Invalid Gemini memory response part "
                                    + "from model {}.",
                            model
                    );

                    continue;
                }

                Object textObject =
                        firstPart.get("text");

                if (textObject == null) {

                    log.warn(
                            "Gemini memory response contained no text "
                                    + "from model {}.",
                            model
                    );

                    continue;
                }

                String updatedMemory =
                        textObject.toString().trim();

                if (updatedMemory.isBlank()) {

                    log.warn(
                            "Gemini returned blank memory from model {}.",
                            model
                    );

                    continue;
                }

                log.info(
                        "Memory response generated successfully "
                                + "using {}.",
                        model
                );

                return updatedMemory;

            } catch (Exception e) {

                log.warn(
                        "Gemini memory request failed for model {} "
                                + "on attempt {}/{}: {}",
                        model,
                        attempt,
                        maxAttempts,
                        e.getMessage()
                );

                if (attempt < maxAttempts) {

                    try {

                        long waitTime =
                                1500L * attempt;

                        log.info(
                                "Waiting {} ms before retrying memory "
                                        + "request to {}.",
                                waitTime,
                                model
                        );

                        Thread.sleep(waitTime);

                    } catch (InterruptedException interruptedException) {

                        Thread.currentThread().interrupt();

                        return null;
                    }
                }
            }
        }

        log.warn(
                "Gemini memory generation failed for model {} "
                        + "after {} attempts.",
                model,
                maxAttempts
        );

        return null;
    }

    /*
     * -------------------------------------------------------------
     * SAVE MEMORY
     * -------------------------------------------------------------
     */

    private synchronized void saveMemory(
            Long userId,
            String memoryText) {

        try {

            UserMemory memory =
                    userMemoryRepository
                            .findByUserId(userId)
                            .orElseGet(
                                    () ->
                                            new UserMemory(
                                                    userId,
                                                    memoryText
                                            )
                            );

            memory.setMemory(memoryText);

            userMemoryRepository.save(memory);

        } catch (Exception ex) {

            log.debug(
                    "Concurrent save memory conflict for user {}, "
                            + "retrying update: {}",
                    userId,
                    ex.getMessage()
            );

            userMemoryRepository
                    .findByUserId(userId)
                    .ifPresent(existing -> {

                        existing.setMemory(memoryText);

                        userMemoryRepository.save(existing);
                    });
        }
    }
}