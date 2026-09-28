package com.adaptivesense.backend.repository;

import com.adaptivesense.backend.entity.EmotionAnalysis;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EmotionAnalysisRepository
        extends JpaRepository<EmotionAnalysis, Long> {

    List<EmotionAnalysis> findByConversationId(
            Long conversationId
    );

    @Query("SELECT ea FROM EmotionAnalysis ea, Conversation c WHERE ea.conversationId = c.id AND c.userId = :userId ORDER BY ea.id DESC")
    List<EmotionAnalysis> findRecentByUserId(
            @Param("userId") Long userId,
            Pageable pageable
    );
}