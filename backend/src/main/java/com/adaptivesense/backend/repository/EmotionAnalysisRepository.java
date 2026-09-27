package com.adaptivesense.backend.repository;

import com.adaptivesense.backend.entity.EmotionAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmotionAnalysisRepository
        extends JpaRepository<EmotionAnalysis, Long> {

    List<EmotionAnalysis> findByConversationId(
            Long conversationId
    );

    List<EmotionAnalysis> findAllByOrderByIdDesc();
}