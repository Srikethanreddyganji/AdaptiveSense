package com.adaptivesense.backend.service;

import com.adaptivesense.backend.entity.Conversation;
import com.adaptivesense.backend.entity.EmotionAnalysis;
import com.adaptivesense.backend.repository.ConversationRepository;
import com.adaptivesense.backend.repository.EmotionAnalysisRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class EmotionAnalysisService {

    @Value("${gemini.api.key}")
    private String apiKey;

    private final RestClient restClient;
    private final EmotionAnalysisRepository emotionAnalysisRepository;
    private final ConversationRepository conversationRepository;

    public EmotionAnalysisService(
            RestClient.Builder builder,
            EmotionAnalysisRepository emotionAnalysisRepository,
            ConversationRepository conversationRepository) {

        this.restClient = builder.build();

        this.emotionAnalysisRepository =
                emotionAnalysisRepository;

        this.conversationRepository =
                conversationRepository;
    }

    public EmotionAnalysis analyzeEmotion(
            Long conversationId,
            String userMessage) {

        String url =
                "https://generativelanguage.googleapis.com/v1beta/models/"
                + "gemini-3.5-flash-lite:generateContent";

        String prompt = """
                Analyze the emotional signals in the following user message.

                This is NOT a medical diagnosis.

                Identify:
                1. Primary emotion
                2. Distress level
                3. Risk level
                4. Confidence

                Allowed emotion values:
                Positive, Neutral, Stress, Anxiety-like,
                Sadness, Anger, Fear, Confusion

                Allowed distress levels:
                Low, Moderate, High

                Allowed risk levels:
                Low, Moderate, Elevated

                Confidence must be a number between 0.0 and 1.0.

                Return ONLY valid JSON.

                Use exactly this structure:

                {
                  "emotion": "Stress",
                  "distressLevel": "Moderate",
                  "riskLevel": "Low",
                  "confidence": 0.85
                }

                Do not add markdown.
                Do not add explanations.
                Do not add extra fields.

                User message:
                """ + userMessage;

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

        try {

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

                System.out.println(
                        "Emotion analysis: Empty Gemini response."
                );

                return null;
            }

            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>)
                            response.get("candidates");

            if (candidates == null ||
                    candidates.isEmpty()) {

                System.out.println(
                        "Emotion analysis: No candidates returned."
                );

                return null;
            }

            Map<String, Object> candidate =
                    candidates.get(0);

            Map<String, Object> content =
                    (Map<String, Object>)
                            candidate.get("content");

            if (content == null) {

                System.out.println(
                        "Emotion analysis: No content returned."
                );

                return null;
            }

            List<Map<String, Object>> parts =
                    (List<Map<String, Object>>)
                            content.get("parts");

            if (parts == null ||
                    parts.isEmpty()) {

                System.out.println(
                        "Emotion analysis: No parts returned."
                );

                return null;
            }

            Object textObject =
                    parts.get(0).get("text");

            if (textObject == null) {

                System.out.println(
                        "Emotion analysis: No text returned."
                );

                return null;
            }

            String jsonText =
                    textObject.toString();

            System.out.println(
                    "Raw emotion analysis: "
                            + jsonText
            );

            return parseAndSave(
                    conversationId,
                    jsonText
            );

        } catch (Exception e) {

            System.out.println(
                    "Emotion analysis failed: "
                            + e.getMessage()
            );

            return null;
        }
    }

    private EmotionAnalysis parseAndSave(
            Long conversationId,
            String jsonText) {

        try {

            /*
             * Remove markdown code blocks if Gemini
             * accidentally returns them.
             */
            jsonText = cleanJson(jsonText);

            /*
             * Extract values.
             */
            String emotion =
                    extractStringValue(
                            jsonText,
                            "emotion"
                    );

            String distressLevel =
                    extractStringValue(
                            jsonText,
                            "distressLevel"
                    );

            String riskLevel =
                    extractStringValue(
                            jsonText,
                            "riskLevel"
                    );

            String confidenceValue =
                    extractRawValue(
                            jsonText,
                            "confidence"
                    );

            /*
             * Validate emotion.
             */
            if (!isValidEmotion(emotion)) {

                System.out.println(
                        "Invalid emotion: "
                                + emotion
                );

                return null;
            }

            /*
             * Validate distress level.
             */
            if (!isValidDistressLevel(
                    distressLevel)) {

                System.out.println(
                        "Invalid distress level: "
                                + distressLevel
                );

                return null;
            }

            /*
             * Validate risk level.
             */
            if (!isValidRiskLevel(
                    riskLevel)) {

                System.out.println(
                        "Invalid risk level: "
                                + riskLevel
                );

                return null;
            }

            /*
             * Convert confidence.
             */
            Double confidence;

            try {

                confidence =
                        Double.parseDouble(
                                confidenceValue
                        );

            } catch (Exception e) {

                System.out.println(
                        "Invalid confidence value: "
                                + confidenceValue
                );

                return null;
            }

            /*
             * Make sure confidence stays
             * between 0 and 1.
             */
            if (confidence < 0.0 ||
                    confidence > 1.0) {

                System.out.println(
                        "Confidence outside valid range: "
                                + confidence
                );

                return null;
            }

            /*
             * Create analysis entity.
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
             * Save valid analysis.
             */
            return emotionAnalysisRepository.save(
                    analysis
            );

        } catch (Exception e) {

            System.out.println(
                    "Could not parse emotion analysis: "
                            + e.getMessage()
            );

            return null;
        }
    }

    private String cleanJson(
            String jsonText) {

        jsonText =
                jsonText
                        .replace(
                                "```json",
                                ""
                        )
                        .replace(
                                "```JSON",
                                ""
                        )
                        .replace(
                                "```",
                                ""
                        )
                        .trim();

        /*
         * If Gemini accidentally places text before
         * or after the JSON, keep only the JSON object.
         */
        int start =
                jsonText.indexOf("{");

        int end =
                jsonText.lastIndexOf("}");

        if (start >= 0 &&
                end >= start) {

            jsonText =
                    jsonText.substring(
                            start,
                            end + 1
                    );
        }

        return jsonText.trim();
    }

    private String extractStringValue(
            String json,
            String key) {

        String value =
                extractRawValue(
                        json,
                        key
                );

        return value
                .replace("\"", "")
                .trim();
    }

    private String extractRawValue(
            String json,
            String key) {

        String search =
                "\"" + key + "\"";

        int keyIndex =
                json.indexOf(search);

        if (keyIndex == -1) {

            return "";
        }

        int colonIndex =
                json.indexOf(
                        ":",
                        keyIndex
                );

        if (colonIndex == -1) {

            return "";
        }

        int commaIndex =
                json.indexOf(
                        ",",
                        colonIndex
                );

        int closingBraceIndex =
                json.indexOf(
                        "}",
                        colonIndex
                );

        int endIndex;

        if (commaIndex == -1) {

            endIndex =
                    closingBraceIndex;

        } else if (closingBraceIndex == -1) {

            endIndex =
                    commaIndex;

        } else {

            endIndex =
                    Math.min(
                            commaIndex,
                            closingBraceIndex
                    );
        }

        if (endIndex == -1) {

            return "";
        }

        return json.substring(
                        colonIndex + 1,
                        endIndex
                )
                .trim();
    }

    private boolean isValidEmotion(
            String emotion) {

        return emotion.equals("Positive")
                || emotion.equals("Neutral")
                || emotion.equals("Stress")
                || emotion.equals("Anxiety-like")
                || emotion.equals("Sadness")
                || emotion.equals("Anger")
                || emotion.equals("Fear")
                || emotion.equals("Confusion");
    }

    private boolean isValidDistressLevel(
            String distressLevel) {

        return distressLevel.equals("Low")
                || distressLevel.equals("Moderate")
                || distressLevel.equals("High");
    }

    private boolean isValidRiskLevel(
            String riskLevel) {

        return riskLevel.equals("Low")
                || riskLevel.equals("Moderate")
                || riskLevel.equals("Elevated");
    }

    public List<EmotionAnalysis> getByConversationId(
            Long conversationId) {

        return emotionAnalysisRepository
                .findByConversationId(
                        conversationId
                );
    }

    public List<EmotionAnalysis> getRecentAnalyses(
            Long userId,
            int limit) {

        List<Conversation> conversations =
                conversationRepository
                        .findByUserIdOrderByCreatedAtDesc(
                                userId
                        );

        List<EmotionAnalysis> results =
                new ArrayList<>();

        for (Conversation conversation :
                conversations) {

            List<EmotionAnalysis> analyses =
                    emotionAnalysisRepository
                            .findByConversationId(
                                    conversation.getId()
                            );

            for (EmotionAnalysis analysis :
                    analyses) {

                results.add(analysis);

                if (results.size() >= limit) {

                    return results;
                }
            }
        }

        return results;
    }
}