package com.adaptivesense.backend.service;

import com.adaptivesense.backend.entity.Conversation;
import com.adaptivesense.backend.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatResponseServiceTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private UserMemoryService userMemoryService;
    @Mock private OllamaProvider ollamaProvider;

    @Test
    void sendsSystemPromptUserScopedHistoryAndCurrentMessageToOllama() {
        Conversation previous = new Conversation(42L, "I have been anxious about work.", "That sounds stressful.");
        when(conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(previous));
        when(userMemoryService.getMemory(42L)).thenReturn("Prefers practical suggestions.");
        when(ollamaProvider.chat(anyList(), eq("req-42"))).thenReturn("Let's focus on one manageable step.");

        ChatResponseService service = new ChatResponseService(conversationRepository, userMemoryService, ollamaProvider);
        Map<String, Object> analysis = Map.of("input_mode", "voice", "voice_emotion", Map.of("label", "sadness"));
        String response = service.generateResponse(42L, "I am worried about tomorrow.", analysis, "req-42");

        assertEquals("Let's focus on one manageable step.", response);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(ollamaProvider).chat(captor.capture(), eq("req-42"));
        List<Map<String, String>> messages = captor.getValue();
        assertEquals("system", messages.getFirst().get("role"));
        org.junit.jupiter.api.Assertions.assertTrue(messages.getFirst().get("content").contains("Never diagnose"));
        org.junit.jupiter.api.Assertions.assertTrue(messages.getFirst().get("content").contains("Prefers practical suggestions."));
        assertEquals(Map.of("role", "user", "content", previous.getUserMessage()), messages.get(1));
        assertEquals(Map.of("role", "assistant", "content", previous.getAssistantResponse()), messages.get(2));
        assertEquals(Map.of("role", "user", "content", "I am worried about tomorrow."), messages.get(3));
        verify(conversationRepository).findTop10ByUserIdOrderByCreatedAtDesc(42L);
    }
}
