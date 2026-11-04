package com.adaptivesense.backend.service;

import com.adaptivesense.backend.entity.UserMemory;
import com.adaptivesense.backend.repository.UserMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(MockitoExtension.class)
class UserMemoryServiceTest {

    @Mock
    private UserMemoryRepository userMemoryRepository;

    @BeforeEach
    void clearQuotaCooldown() {
        ReflectionTestUtils.setField(GeminiService.class, "quotaBlockedUntilEpochMs", 0L);
    }

    @Test
    void memoryServiceUnavailableIsSwallowedAndDoesNotSave() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserMemoryService memoryService = new UserMemoryService(userMemoryRepository, builder);
        ReflectionTestUtils.setField(memoryService, "apiKey", "test-secret");
        ReflectionTestUtils.setField(memoryService, "modelName", "gemini-3.8-flash");
        when(userMemoryRepository.findByUserId(5L)).thenReturn(Optional.empty());
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"))
                .andExpect(header("x-goog-api-key", "test-secret"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("{\"error\":{\"message\":\"private provider response\"}}"));

        assertDoesNotThrow(() -> memoryService.updateMemoryAsync(5L, "Private message content"));

        server.verify();
        verify(userMemoryRepository, never()).save(any(UserMemory.class));
    }
}
