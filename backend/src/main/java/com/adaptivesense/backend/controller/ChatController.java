package com.adaptivesense.backend.controller;

import com.adaptivesense.backend.entity.Conversation;
import com.adaptivesense.backend.entity.EmotionAnalysis;
import com.adaptivesense.backend.repository.ConversationRepository;
import com.adaptivesense.backend.service.EmotionAnalysisService;
import com.adaptivesense.backend.service.GeminiService;
import com.adaptivesense.backend.service.UserMemoryService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "http://localhost:5173")
public class ChatController {

    private final ConversationRepository conversationRepository;
    private final GeminiService geminiService;
    private final EmotionAnalysisService emotionAnalysisService;
    private final UserMemoryService userMemoryService;

    public ChatController(
            ConversationRepository conversationRepository,
            GeminiService geminiService,
            EmotionAnalysisService emotionAnalysisService,
            UserMemoryService userMemoryService) {

        this.conversationRepository =
                conversationRepository;

        this.geminiService =
                geminiService;

        this.emotionAnalysisService =
                emotionAnalysisService;

        this.userMemoryService =
                userMemoryService;
    }


    /*
     * ================================
     * CHAT
     * ================================
     */

    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(
            @RequestBody Map<String, Object> request) {

        String message =
                (String) request.get("message");

        Long userId =
                Long.valueOf(
                        request.get("userId").toString()
                );

        System.out.println(
                "User ID: " + userId
        );

        System.out.println(
                "User message: " + message
        );


        /*
         * Generate AI response.
         */

        String response =
                geminiService.generateResponse(
                        userId,
                        message
                );


        /*
         * Save conversation.
         */

        Conversation conversation =
                new Conversation(
                        userId,
                        message,
                        response
                );

        conversationRepository.save(
                conversation
        );


        /*
         * Analyze emotional signals.
         */

        EmotionAnalysis analysis =
                emotionAnalysisService.analyzeEmotion(
                        conversation.getId(),
                        message
                );


        /*
         * Update long-term memory.
         */

        userMemoryService.updateMemory(
                userId,
                message
        );


        /*
         * Return response.
         */

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
                                : 0.0
                )
        );
    }


    /*
     * ================================
     * CONVERSATION HISTORY
     * ================================
     */

    @GetMapping("/history/{userId}")
    public ResponseEntity<List<Conversation>>
    getConversationHistory(
            @PathVariable Long userId) {

        List<Conversation> conversations =
                conversationRepository
                        .findByUserIdOrderByCreatedAtDesc(
                                userId
                        );

        return ResponseEntity.ok(
                conversations
        );
    }


    /*
     * ================================
     * LATEST EMOTION
     * ================================
     */

    @GetMapping("/emotion/latest/{userId}")
    public ResponseEntity<?> getLatestEmotion(
            @PathVariable Long userId) {

        List<Conversation> conversations =
                conversationRepository
                        .findByUserIdOrderByCreatedAtDesc(
                                userId
                        );

        if (conversations.isEmpty()) {

            return ResponseEntity
                    .notFound()
                    .build();
        }


        for (Conversation conversation :
                conversations) {

            List<EmotionAnalysis> analyses =
                    emotionAnalysisService
                            .getByConversationId(
                                    conversation.getId()
                            );

            if (!analyses.isEmpty()) {

                EmotionAnalysis latest =
                        analyses.get(
                                analyses.size() - 1
                        );

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
        }


        return ResponseEntity
                .notFound()
                .build();
    }


    /*
     * ================================
     * EMOTION HISTORY
     * ================================
     */

    @GetMapping("/emotion/history/{userId}")
    public ResponseEntity<List<EmotionAnalysis>>
    getEmotionHistory(
            @PathVariable Long userId) {

        List<EmotionAnalysis> history =
                emotionAnalysisService
                        .getRecentAnalyses(
                                userId,
                                10
                        );

        return ResponseEntity.ok(
                history
        );
    }
}