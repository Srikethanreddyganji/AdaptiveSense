package com.adaptivesense.backend.controller;

import com.adaptivesense.backend.dto.ChatRequest;
import com.adaptivesense.backend.entity.Conversation;
import com.adaptivesense.backend.entity.EmotionAnalysis;
import com.adaptivesense.backend.repository.ConversationRepository;
import com.adaptivesense.backend.security.AuthenticatedUser;
import com.adaptivesense.backend.service.AIAnalysisService;
import com.adaptivesense.backend.service.EmotionAnalysisService;
import com.adaptivesense.backend.service.GeminiService;
import com.adaptivesense.backend.service.UserMemoryService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ConversationRepository conversationRepository;
    private final GeminiService geminiService;
    private final EmotionAnalysisService emotionAnalysisService;
    private final UserMemoryService userMemoryService;
    private final AIAnalysisService aiAnalysisService;

    public ChatController(
            ConversationRepository conversationRepository,
            GeminiService geminiService,
            EmotionAnalysisService emotionAnalysisService,
            UserMemoryService userMemoryService,
            AIAnalysisService aiAnalysisService) {

        this.conversationRepository =
                conversationRepository;

        this.geminiService =
                geminiService;

        this.emotionAnalysisService =
                emotionAnalysisService;

        this.userMemoryService =
                userMemoryService;

        this.aiAnalysisService =
                aiAnalysisService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ChatRequest request) {

        Long userId = user.getUserId();
        String message = request.getMessage();

        List<Conversation> recentConversations =
                new ArrayList<>(
                        conversationRepository
                                .findTop10ByUserIdOrderByCreatedAtDesc(
                                        userId
                                )
                );
        java.util.Collections.reverse(recentConversations);

        List<Map<String, String>> conversationContext =
                new ArrayList<>();

        for (Conversation conversation :
                recentConversations) {

            conversationContext.add(
                    Map.of(
                            "user",
                            conversation.getUserMessage(),

                            "assistant",
                            conversation.getAssistantResponse()
                    )
            );
        }

        Map<String, Object> previousEmotion =
                Map.of();

        if (!recentConversations.isEmpty()) {

            Conversation latestConversation =
                    recentConversations.get(
                            recentConversations.size() - 1
                    );

            List<EmotionAnalysis> previousAnalyses =
                    emotionAnalysisService
                            .getByConversationId(
                                    latestConversation.getId()
                            );

            if (!previousAnalyses.isEmpty()) {

                EmotionAnalysis latest =
                        previousAnalyses.get(
                                previousAnalyses.size() - 1
                        );

                previousEmotion =
                        Map.of(
                                "emotion",
                                latest.getEmotion(),

                                "distressLevel",
                                latest.getDistressLevel(),

                                "riskLevel",
                                latest.getRiskLevel(),

                                "confidence",
                                latest.getConfidence()
                        );
            }
        }

        Map<String, Object> aiAnalysis =
                aiAnalysisService.analyze(
                        message,
                        conversationContext,
                        previousEmotion
                );

        String response =
                geminiService.generateResponse(
                        userId,
                        message,
                        aiAnalysis
                );

        Conversation conversation =
                new Conversation(
                        userId,
                        message,
                        response
                );

        conversationRepository.save(
                conversation
        );

        EmotionAnalysis analysis =
                emotionAnalysisService.saveAnalysis(
                        conversation.getId(),
                        aiAnalysis
                );

        userMemoryService.updateMemoryAsync(
                userId,
                message
        );

        return ResponseEntity.ok(
                Map.of(
                        "response",
                        response,

                        "emotion",
                        analysis != null
                                ? analysis.getEmotion()
                                : "Unknown",

                        "distressLevel",
                        analysis != null
                                ? analysis.getDistressLevel()
                                : "Unknown",

                        "riskLevel",
                        analysis != null
                                ? analysis.getRiskLevel()
                                : "Unknown",

                        "confidence",
                        analysis != null
                                ? analysis.getConfidence()
                                : 0.0,

                        "safetyEscalation",
                        analysis != null
                                && "Elevated".equalsIgnoreCase(
                                        analysis.getRiskLevel()
                                )
                )
        );
    }

    @GetMapping("/history")
    public ResponseEntity<List<Conversation>>
    getConversationHistory(
            @AuthenticationPrincipal AuthenticatedUser user) {

        List<Conversation> conversations =
                conversationRepository
                        .findByUserIdOrderByCreatedAtDesc(
                                user.getUserId()
                        );

        return ResponseEntity.ok(
                conversations
        );
    }

    @GetMapping("/emotion/latest")
    public ResponseEntity<?> getLatestEmotion(
            @AuthenticationPrincipal AuthenticatedUser user) {

        EmotionAnalysis latest =
                emotionAnalysisService.getLatestByUserId(
                        user.getUserId()
                );

        if (latest == null) {
            return ResponseEntity
                    .notFound()
                    .build();
        }

        return ResponseEntity.ok(
                Map.of(
                        "emotion",
                        latest.getEmotion(),

                        "distressLevel",
                        latest.getDistressLevel(),

                        "riskLevel",
                        latest.getRiskLevel(),

                        "confidence",
                        latest.getConfidence()
                )
        );
    }

    @GetMapping("/emotion/history")
    public ResponseEntity<List<EmotionAnalysis>>
    getEmotionHistory(
            @AuthenticationPrincipal AuthenticatedUser user) {

        List<EmotionAnalysis> history =
                emotionAnalysisService
                        .getRecentAnalyses(
                                user.getUserId(),
                                10
                        );

        return ResponseEntity.ok(
                history
        );
    }
}
