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
            LoggerFactory.getLogger(
                    UserMemoryService.class
            );

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.model:gemini-1.5-flash}")
    private String modelName;

    private final UserMemoryRepository userMemoryRepository;
    private final RestClient restClient;

    public UserMemoryService(
            UserMemoryRepository userMemoryRepository,
            RestClient.Builder builder) {

        this.userMemoryRepository =
                userMemoryRepository;

        this.restClient =
                builder.build();
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

            String currentMemory =
                    getMemory(userId);

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
                    + modelName
                    + ":generateContent";

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

            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>)
                            response.get("candidates");

            if (candidates == null ||
                    candidates.isEmpty()) {

                return;
            }

            Map<String, Object> candidate =
                    candidates.get(0);

            Map<String, Object> content =
                    (Map<String, Object>)
                            candidate.get("content");

            if (content == null) {
                return;
            }

            List<Map<String, Object>> parts =
                    (List<Map<String, Object>>)
                            content.get("parts");

            if (parts == null ||
                    parts.isEmpty()) {

                return;
            }

            String updatedMemory =
                    (String)
                            parts.get(0).get("text");

            if (updatedMemory == null ||
                    updatedMemory.isBlank()) {

                return;
            }

            updatedMemory =
                    updatedMemory.trim();

            if (updatedMemory.length() > 3000) {

                updatedMemory =
                        updatedMemory.substring(
                                0,
                                3000
                        );
            }

            saveMemory(
                    userId,
                    updatedMemory
            );

        } catch (Exception e) {

            log.warn(
                    "Memory update failed for user {}: {}",
                    userId,
                    e.getMessage()
            );
        }
    }

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

            memory.setMemory(
                    memoryText
            );

            userMemoryRepository.save(
                    memory
            );
        } catch (Exception ex) {
            log.debug("Concurrent save memory conflict for user {}, retrying update: {}", userId, ex.getMessage());
            userMemoryRepository.findByUserId(userId).ifPresent(existing -> {
                existing.setMemory(memoryText);
                userMemoryRepository.save(existing);
            });
        }
    }
}
