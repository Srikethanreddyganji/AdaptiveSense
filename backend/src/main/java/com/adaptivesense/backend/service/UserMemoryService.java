package com.adaptivesense.backend.service;

import com.adaptivesense.backend.entity.UserMemory;
import com.adaptivesense.backend.repository.UserMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class UserMemoryService {

    private static final Logger log = LoggerFactory.getLogger(UserMemoryService.class);
    private final UserMemoryRepository userMemoryRepository;
    private final OllamaProvider ollamaProvider;

    public UserMemoryService(UserMemoryRepository userMemoryRepository, OllamaProvider ollamaProvider) {
        this.userMemoryRepository = userMemoryRepository;
        this.ollamaProvider = ollamaProvider;
    }

    public String getMemory(Long userId) {
        return userMemoryRepository.findByUserId(userId)
                .map(UserMemory::getMemory)
                .orElse("");
    }

    @Async("memoryTaskExecutor")
    public void updateMemoryAsync(Long userId, String userMessage) {
        try {
            String currentMemory = getMemory(userId);
            String system = """
                    You update brief, factual user memory for a supportive assistant. Keep only
                    information that may help future conversations, such as ongoing goals,
                    preferences, recurring situations, or information the person asks to remember.
                    Never store passwords, authentication details, financial account data, or medical
                    diagnoses. Do not infer facts. Return only the updated memory in plain text. If
                    nothing should change, return the existing memory unchanged.
                    """;
            String prompt = "Existing memory:\n" + (currentMemory == null ? "" : currentMemory)
                    + "\n\nLatest user message:\n" + (userMessage == null ? "" : userMessage)
                    + "\n\nReturn the concise updated memory only.";
            String updatedMemory = ollamaProvider.chatBackground(List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", prompt)
            ));
            if (updatedMemory == null) {
                log.debug("Optional local memory update deferred while foreground generation is active.");
                return;
            }
            if (updatedMemory == null || updatedMemory.isBlank()) {
                log.debug("Local memory model returned no update.");
                return;
            }
            updatedMemory = updatedMemory.trim();
            if (updatedMemory.length() > 3000) updatedMemory = updatedMemory.substring(0, 3000);
            saveMemory(userId, updatedMemory);
            log.info("User memory updated using local model for user {}.", userId);
        } catch (Exception exception) {
            log.warn("Local memory update failed for user {} ({}).",
                    userId, exception.getClass().getSimpleName());
        }
    }

    private synchronized void saveMemory(Long userId, String memoryText) {
        try {
            UserMemory memory = userMemoryRepository.findByUserId(userId)
                    .orElseGet(() -> new UserMemory(userId, memoryText));
            memory.setMemory(memoryText);
            userMemoryRepository.save(memory);
        } catch (Exception exception) {
            log.debug("Concurrent local memory save for user {} ({}).",
                    userId, exception.getClass().getSimpleName());
            userMemoryRepository.findByUserId(userId).ifPresent(existing -> {
                existing.setMemory(memoryText);
                userMemoryRepository.save(existing);
            });
        }
    }
}
