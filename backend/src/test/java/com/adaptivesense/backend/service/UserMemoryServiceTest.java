package com.adaptivesense.backend.service;

import com.adaptivesense.backend.entity.UserMemory;
import com.adaptivesense.backend.exception.ApiException;
import com.adaptivesense.backend.repository.UserMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserMemoryServiceTest {

    @Mock private UserMemoryRepository userMemoryRepository;
    @Mock private OllamaProvider ollamaProvider;

    @Test
    void memoryUpdateUsesLocalProviderAndPersistsReturnedContent() {
        UserMemoryService memoryService = new UserMemoryService(userMemoryRepository, ollamaProvider);
        when(userMemoryRepository.findByUserId(5L)).thenReturn(Optional.empty());
        when(ollamaProvider.chatBackground(anyList())).thenReturn("Prefers short check-ins.");
        when(userMemoryRepository.save(any(UserMemory.class))).thenAnswer(call -> call.getArgument(0));

        memoryService.updateMemoryAsync(5L, "I prefer short check-ins");

        ArgumentCaptor<UserMemory> saved = ArgumentCaptor.forClass(UserMemory.class);
        verify(userMemoryRepository).save(saved.capture());
        verify(ollamaProvider).chatBackground(anyList());
        org.junit.jupiter.api.Assertions.assertEquals("Prefers short check-ins.", saved.getValue().getMemory());
    }

    @Test
    void localModelFailureDoesNotEscapeOrWriteMemory() {
        UserMemoryService memoryService = new UserMemoryService(userMemoryRepository, ollamaProvider);
        when(userMemoryRepository.findByUserId(5L)).thenReturn(Optional.empty());
        when(ollamaProvider.chatBackground(anyList())).thenThrow(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "Local model unavailable", "OLLAMA_UNAVAILABLE"));

        assertDoesNotThrow(() -> memoryService.updateMemoryAsync(5L, "Private message"));
        verify(userMemoryRepository, never()).save(any(UserMemory.class));
    }

    @Test
    void memoryUpdateIsSkippedWhenForegroundModelWorkOwnsTheProvider() {
        UserMemoryService memoryService = new UserMemoryService(userMemoryRepository, ollamaProvider);
        when(userMemoryRepository.findByUserId(5L)).thenReturn(Optional.empty());
        when(ollamaProvider.chatBackground(anyList())).thenReturn(null);

        assertDoesNotThrow(() -> memoryService.updateMemoryAsync(5L, "I prefer short check-ins"));
        verify(userMemoryRepository, never()).save(any(UserMemory.class));
    }
}
