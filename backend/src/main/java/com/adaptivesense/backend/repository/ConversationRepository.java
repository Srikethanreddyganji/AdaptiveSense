package com.adaptivesense.backend.repository;

import com.adaptivesense.backend.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository
        extends JpaRepository<Conversation, Long> {

    List<Conversation> findByUserIdOrderByCreatedAtAsc(
            Long userId
    );

    List<Conversation> findByUserIdOrderByCreatedAtDesc(
            Long userId
    );
}