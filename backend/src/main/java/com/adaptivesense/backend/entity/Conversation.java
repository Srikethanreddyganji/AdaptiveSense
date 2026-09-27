package com.adaptivesense.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Column(columnDefinition = "TEXT")
    private String userMessage;

    @Column(columnDefinition = "TEXT")
    private String assistantResponse;

    private LocalDateTime createdAt;

    public Conversation() {
    }

    public Conversation(
            Long userId,
            String userMessage,
            String assistantResponse) {

        this.userId = userId;
        this.userMessage = userMessage;
        this.assistantResponse = assistantResponse;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getAssistantResponse() {
        return assistantResponse;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}