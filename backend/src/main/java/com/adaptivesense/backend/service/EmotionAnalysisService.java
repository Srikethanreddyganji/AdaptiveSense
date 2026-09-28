package com.adaptivesense.backend.service;

import com.adaptivesense.backend.entity.Conversation;
import com.adaptivesense.backend.entity.EmotionAnalysis;
import com.adaptivesense.backend.repository.ConversationRepository;
import com.adaptivesense.backend.repository.EmotionAnalysisRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class EmotionAnalysisService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    EmotionAnalysisService.class
            );

    private final EmotionAnalysisRepository emotionAnalysisRepository;
    private final ConversationRepository conversationRepository;

    public EmotionAnalysisService(
            EmotionAnalysisRepository emotionAnalysisRepository,
            ConversationRepository conversationRepository) {

        this.emotionAnalysisRepository =
                emotionAnalysisRepository;

        this.conversationRepository =
                conversationRepository;
    }

    /*
     * ============================================================
     * SAVE PYTHON AI ANALYSIS
     * ============================================================
     *
     * Python AI service has already performed:
     *
     * User message
     *      ↓
     * RoBERTa sentiment
     *      ↓
     * Emotion model
     *      ↓
     * Adaptive Social Cue Engine
     *
     * This method DOES NOT call Python again.
     * This method DOES NOT call Gemini.
     *
     * It simply converts the Python result into our
     * EmotionAnalysis database entity and saves it.
     */
    public EmotionAnalysis saveAnalysis(
            Long conversationId,
            Map<String, Object> aiAnalysis) {

        try {

            if (aiAnalysis == null ||
                    aiAnalysis.isEmpty()) {

                return null;
            }

            /*
             * ----------------------------------------------------
             * Get NLP result
             * ----------------------------------------------------
             */

            Object nlpObject =
                    aiAnalysis.get("nlp");

            Map<String, Object> nlp =
                    nlpObject instanceof Map
                            ? (Map<String, Object>) nlpObject
                            : Map.of();

            /*
             * ----------------------------------------------------
             * Get social-cue result
             * ----------------------------------------------------
             */

            Object socialCuesObject =
                    aiAnalysis.get("social_cues");

            Map<String, Object> socialCues =
                    socialCuesObject instanceof Map
                            ? (Map<String, Object>) socialCuesObject
                            : Map.of();

            /*
             * ----------------------------------------------------
             * Extract emotion
             * ----------------------------------------------------
             */

            String emotion =
                    extractEmotion(
                            nlp,
                            socialCues
                    );

            /*
             * ----------------------------------------------------
             * Extract distress
             * ----------------------------------------------------
             */

            String distressLevel =
                    extractDistressLevel(
                            socialCues
                    );

            /*
             * ----------------------------------------------------
             * Extract risk
             * ----------------------------------------------------
             */

            String riskLevel =
                    extractRiskLevel(
                            socialCues
                    );

            /*
             * ----------------------------------------------------
             * Extract confidence
             * ----------------------------------------------------
             */

            Double confidence =
                    extractConfidence(
                            nlp,
                            socialCues
                    );

            /*
             * ----------------------------------------------------
             * Normalize
             * ----------------------------------------------------
             */

            emotion =
                    normalizeEmotion(
                            emotion
                    );

            distressLevel =
                    normalizeDistressLevel(
                            distressLevel
                    );

            riskLevel =
                    normalizeRiskLevel(
                            riskLevel
                    );

            /*
             * ----------------------------------------------------
             * Defaults
             * ----------------------------------------------------
             */

            if (emotion == null ||
                    emotion.isBlank()) {

                emotion = "Unknown";
            }

            if (distressLevel == null ||
                    distressLevel.isBlank()) {

                distressLevel = "Low";
            }

            if (riskLevel == null ||
                    riskLevel.isBlank()) {

                riskLevel = "Low";
            }

            if (confidence == null) {

                confidence = 0.0;
            }

            /*
             * Keep confidence between 0 and 1.
             */

            confidence =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    confidence
                            )
                    );

            /*
             * ----------------------------------------------------
             * Create database entity
             * ----------------------------------------------------
             */

            EmotionAnalysis analysis =
                    new EmotionAnalysis(
                            conversationId,
                            emotion,
                            distressLevel,
                            riskLevel,
                            confidence
                    );

            /*
             * ----------------------------------------------------
             * Save
             * ----------------------------------------------------
             */

            return emotionAnalysisRepository.save(
                    analysis
            );

        } catch (Exception e) {

            log.warn(
                    "Failed to save emotion analysis: {}",
                    e.getMessage()
            );

            return null;
        }
    }

    /*
     * ============================================================
     * EXTRACT EMOTION
     * ============================================================
     */

    private String extractEmotion(
            Map<String, Object> nlp,
            Map<String, Object> socialCues) {

        /*
         * Example Python result:
         *
         * "primary_emotion": {
         *     "label": "sadness",
         *     "score": 0.9871
         * }
         */

        Object primaryEmotion =
                nlp.get("primary_emotion");

        if (primaryEmotion instanceof Map) {

            Map<?, ?> emotionMap =
                    (Map<?, ?>) primaryEmotion;

            Object label =
                    emotionMap.get("label");

            if (label != null) {

                return label.toString();
            }
        }

        /*
         * Fallback:
         *
         * social_cues.dominant_emotion
         */

        Object dominantEmotion =
                socialCues.get(
                        "dominant_emotion"
                );

        if (dominantEmotion != null) {

            return dominantEmotion.toString();
        }

        return null;
    }

    /*
     * ============================================================
     * EXTRACT DISTRESS
     * ============================================================
     */

    private String extractDistressLevel(
            Map<String, Object> socialCues) {

        /*
         * If the Python service directly provides
         * distressLevel, use it.
         */

        Object value =
                socialCues.get(
                        "distressLevel"
                );

        if (value != null) {

            return value.toString();
        }

        /*
         * Your current Python service provides:
         *
         * distress_score
         *
         * Current example:
         *
         * distress_score = 2
         *
         * Therefore:
         *
         * 0-1 -> Low
         * 2-3 -> Moderate
         * 4+  -> High
         */

        Double distressScore =
                convertToDouble(
                        socialCues.get(
                                "distress_score"
                        )
                );

        if (distressScore != null) {

            if (distressScore >= 4) {

                return "High";

            } else if (distressScore >= 2) {

                return "Moderate";

            } else {

                return "Low";
            }
        }

        /*
         * Boolean fallback.
         */

        Object distress =
                socialCues.get(
                        "distress"
                );

        if (Boolean.TRUE.equals(distress)) {

            return "Moderate";
        }

        return "Low";
    }

    /*
     * ============================================================
     * EXTRACT RISK
     * ============================================================
     */

    private String extractRiskLevel(
            Map<String, Object> socialCues) {

        /*
         * If Python provides riskLevel directly,
         * use it.
         */

        Object value =
                socialCues.get(
                        "riskLevel"
                );

        if (value != null) {

            return value.toString();
        }

        /*
         * Current Python engine does not return
         * a riskLevel directly.
         *
         * We derive it from the available signals.
         */

        Object severeDistress =
                socialCues.get(
                        "severe_distress"
                );

        if (Boolean.TRUE.equals(
                severeDistress)) {

            return "Elevated";
        }

        Double distressScore =
                convertToDouble(
                        socialCues.get(
                                "distress_score"
                        )
                );

        if (distressScore != null) {

            if (distressScore >= 4) {

                return "Elevated";

            } else if (distressScore >= 2) {

                return "Moderate";

            } else {

                return "Low";
            }
        }

        return "Low";
    }

    /*
     * ============================================================
     * EXTRACT CONFIDENCE
     * ============================================================
     */

    private Double extractConfidence(
            Map<String, Object> nlp,
            Map<String, Object> socialCues) {

        /*
         * Try explicit confidence.
         */

        Double confidence =
                convertToDouble(
                        nlp.get("confidence")
                );

        if (confidence != null) {

            return confidence;
        }

        confidence =
                convertToDouble(
                        socialCues.get(
                                "confidence"
                        )
                );

        if (confidence != null) {

            return confidence;
        }

        /*
         * Use primary emotion score.
         */

        Object primaryEmotion =
                nlp.get(
                        "primary_emotion"
                );

        if (primaryEmotion instanceof Map) {

            Map<?, ?> emotionMap =
                    (Map<?, ?>)
                            primaryEmotion;

            confidence =
                    convertToDouble(
                            emotionMap.get(
                                    "score"
                            )
                    );

            if (confidence != null) {

                return confidence;
            }
        }

        /*
         * Fallback to social cue emotion score.
         */

        confidence =
                convertToDouble(
                        socialCues.get(
                                "emotion_score"
                        )
                );

        if (confidence != null) {

            return confidence;
        }

        /*
         * Final fallback to sentiment score.
         */

        return convertToDouble(
                socialCues.get(
                        "sentiment_score"
                )
        );
    }

    /*
     * ============================================================
     * CONVERT TO DOUBLE
     * ============================================================
     */

    private Double convertToDouble(
            Object value) {

        if (value == null) {

            return null;
        }

        try {

            return Double.parseDouble(
                    value.toString()
            );

        } catch (Exception e) {

            return null;
        }
    }

    /*
     * ============================================================
     * NORMALIZE EMOTION
     * ============================================================
     */

    private String normalizeEmotion(
            String emotion) {

        if (emotion == null) {

            return null;
        }

        String value =
                emotion.trim();

        if (value.equalsIgnoreCase("positive")) {
            return "Positive";
        }

        if (value.equalsIgnoreCase("neutral")) {
            return "Neutral";
        }

        if (value.equalsIgnoreCase("stress")) {
            return "Stress";
        }

        if (value.equalsIgnoreCase("anxiety") ||
                value.equalsIgnoreCase("anxiety-like")) {

            return "Anxiety-like";
        }

        if (value.equalsIgnoreCase("sad") ||
                value.equalsIgnoreCase("sadness")) {

            return "Sadness";
        }

        if (value.equalsIgnoreCase("anger") ||
                value.equalsIgnoreCase("angry")) {

            return "Anger";
        }

        if (value.equalsIgnoreCase("fear")) {
            return "Fear";
        }

        if (value.equalsIgnoreCase("confusion") ||
                value.equalsIgnoreCase("confused")) {

            return "Confusion";
        }

        return value;
    }

    /*
     * ============================================================
     * NORMALIZE DISTRESS
     * ============================================================
     */

    private String normalizeDistressLevel(
            String distressLevel) {

        if (distressLevel == null) {

            return null;
        }

        String value =
                distressLevel.trim();

        if (value.equalsIgnoreCase("low")) {
            return "Low";
        }

        if (value.equalsIgnoreCase("moderate")) {
            return "Moderate";
        }

        if (value.equalsIgnoreCase("high")) {
            return "High";
        }

        return value;
    }

    /*
     * ============================================================
     * NORMALIZE RISK
     * ============================================================
     */

    private String normalizeRiskLevel(
            String riskLevel) {

        if (riskLevel == null) {

            return null;
        }

        String value =
                riskLevel.trim();

        if (value.equalsIgnoreCase("low")) {
            return "Low";
        }

        if (value.equalsIgnoreCase("moderate")) {
            return "Moderate";
        }

        if (value.equalsIgnoreCase("elevated")) {
            return "Elevated";
        }

        return value;
    }

    /*
     * ============================================================
     * GET ANALYSIS FOR CONVERSATION
     * ============================================================
     */

    public List<EmotionAnalysis> getByConversationId(
            Long conversationId) {

        return emotionAnalysisRepository
                .findByConversationId(
                        conversationId
                );
    }

    /*
     * ============================================================
     * GET RECENT ANALYSES
     * ============================================================
     */

    public List<EmotionAnalysis> getRecentAnalyses(
            Long userId,
            int limit) {

        int max = Math.max(1, Math.min(limit, 100));
        return emotionAnalysisRepository.findRecentByUserId(
                userId,
                PageRequest.of(0, max)
        );
    }

    /*
     * ============================================================
     * GET LATEST ANALYSIS FOR USER
     * ============================================================
     */

    public EmotionAnalysis getLatestByUserId(Long userId) {

        List<EmotionAnalysis> analyses =
                emotionAnalysisRepository.findRecentByUserId(
                        userId,
                        PageRequest.of(0, 1)
                );

        return analyses.isEmpty() ? null : analyses.get(0);
    }
}