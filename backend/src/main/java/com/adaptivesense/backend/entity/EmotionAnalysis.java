package com.adaptivesense.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "emotion_analysis")
public class EmotionAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long conversationId;

    private String emotion;

    private String distressLevel;

    private String riskLevel;

    private Double confidence;

    private LocalDateTime createdAt;

    public EmotionAnalysis() {
    }

    public EmotionAnalysis(
            Long conversationId,
            String emotion,
            String distressLevel,
            String riskLevel,
            Double confidence) {

        this.conversationId = conversationId;
        this.emotion = emotion;
        this.distressLevel = distressLevel;
        this.riskLevel = riskLevel;
        this.confidence = confidence;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public String getEmotion() {
        return emotion;
    }

    public String getDistressLevel() {
        return distressLevel;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public Double getConfidence() {
        return confidence;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}