package com.adaptivesense.backend.controller;

import com.adaptivesense.backend.dto.ChatRequest;
import com.adaptivesense.backend.entity.Conversation;
import com.adaptivesense.backend.entity.EmotionAnalysis;
import com.adaptivesense.backend.exception.ApiException;
import com.adaptivesense.backend.repository.ConversationRepository;
import com.adaptivesense.backend.security.AuthenticatedUser;
import com.adaptivesense.backend.service.AIAnalysisService;
import com.adaptivesense.backend.service.EmotionAnalysisService;
import com.adaptivesense.backend.service.ChatResponseService;
import com.adaptivesense.backend.service.UserMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.core.task.TaskRejectedException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ChatResponseService chatResponseService;

    @Mock
    private EmotionAnalysisService emotionAnalysisService;

    @Mock
    private UserMemoryService userMemoryService;

    @Mock
    private AIAnalysisService aiAnalysisService;

    @InjectMocks
    private ChatController chatController;

    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        user = new AuthenticatedUser(1L, "user@test.com");
    }

    @Test
    void voiceChat_localResponseAndMemoryQueueRejectionPreserveVoiceAnalysis() {
        MockMultipartFile audio = new MockMultipartFile(
                "audio",
                "recording.webm",
                "audio/webm",
                new byte[]{1, 2, 3, 4}
        );

        Map<String, Object> mockAiResult = Map.of(
                "text", "I felt overwhelmed today but I am holding on.",
                "acoustic_features", Map.of(
                        "mean_pitch_hz", 210.5,
                        "pitch_std_hz", 18.2,
                        "pause_count", 3,
                        "speaking_rate", 2.4,
                        "rms_energy", 0.08
                ),
                "voice_emotion", Map.of("label", "sadness", "score", 0.72),
                "social_cues", Map.of("distressLevel", "Moderate", "riskLevel", "Moderate"),
                "input_mode", "voice"
        );

        when(conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of());

        when(aiAnalysisService.analyzeVoice(any(), any(), any(), any(), any(), any()))
                .thenReturn(mockAiResult);

        when(chatResponseService.generateResponse(eq(1L), eq("I felt overwhelmed today but I am holding on."), eq(mockAiResult)))
                .thenReturn("I hear that it has been a difficult day. What feels most important right now?");

        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(emotionAnalysisService.saveAnalysis(any(), eq(mockAiResult)))
                .thenReturn(new EmotionAnalysis(10L, "Sadness", "Moderate", "Moderate", 0.85));
        doThrow(new TaskRejectedException("queue full"))
                .when(userMemoryService).updateMemoryAsync(1L, "I felt overwhelmed today but I am holding on.");

        ResponseEntity<Map<String, Object>> response = chatController.voiceChat(
                user,
                audio,
                "I felt overwhelmed today but I am holding on.",
                null
        );

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("I hear that it has been a difficult day. What feels most important right now?", body.get("response"));
        assertEquals("I felt overwhelmed today but I am holding on.", body.get("transcript"));
        assertEquals("Sadness", body.get("emotion"));
        assertEquals("Moderate", body.get("distressLevel"));
        assertEquals("voice", body.get("inputMode"));

        assertNotNull(body.get("acousticFeatures"));
        Map<?, ?> acoustic = (Map<?, ?>) body.get("acousticFeatures");
        assertEquals(210.5, acoustic.get("mean_pitch_hz"));
        assertEquals(3, acoustic.get("pause_count"));

        verify(conversationRepository, times(2)).save(any(Conversation.class));
        verify(emotionAnalysisService).saveAnalysis(any(), eq(mockAiResult));
    }

    @Test
    void voiceStreamReportsAnalysisAndResponseStagesAndCompletes() throws Exception {
        ReflectionTestUtils.setField(chatController, "objectMapper", new ObjectMapper());
        MockMultipartFile audio = new MockMultipartFile(
                "audio", "recording.webm", "audio/webm", new byte[]{1, 2, 3, 4}
        );
        Map<String, Object> analysis = Map.of(
                "text", "I feel ready to talk.",
                "acoustic_features", Map.of("status", "ok", "pause_count", 2),
                "voice_emotion", Map.of("status", "ok", "label", "neutral"),
                "input_mode", "voice"
        );
        when(conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(aiAnalysisService.analyzeVoice(any(), any(), any(), any(), any(), any())).thenReturn(analysis);
        when(chatResponseService.generateResponse(1L, "I feel ready to talk.", analysis)).thenReturn("I am listening.");
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(emotionAnalysisService.saveAnalysis(any(), eq(analysis))).thenReturn(null);

        StreamingResponseBody stream = chatController.voiceChatStream(user, audio, "I feel ready to talk.", null).getBody();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertNotNull(stream);
        stream.writeTo(output);

        String events = output.toString(StandardCharsets.UTF_8);
        assertTrue(events.contains("\"stage\":\"transcription\""));
        assertTrue(events.contains("\"stage\":\"response\""));
        assertTrue(events.contains("event: analysis"));
        assertTrue(events.contains("event: response"));
        assertTrue(events.contains("event: complete"));
        assertTrue(events.contains("\"response\":\"I am listening.\""));
        assertEquals(1, events.split("event: complete", -1).length - 1);
        assertTrue(events.indexOf("event: analysis") < events.indexOf("\"stage\":\"response\""));
        assertTrue(events.indexOf("event: response") < events.indexOf("event: complete"));
    }

    @Test
    void voiceStreamClientDisconnectAfterCommitDoesNotEscapeForErrorPageDispatch() {
        ReflectionTestUtils.setField(chatController, "objectMapper", new ObjectMapper());
        MockMultipartFile audio = new MockMultipartFile(
                "audio", "recording.webm", "audio/webm", new byte[]{1, 2, 3, 4}
        );
        Map<String, Object> analysis = Map.of(
                "text", "I feel ready to talk.",
                "acoustic_features", Map.of("status", "ok"),
                "voice_emotion", Map.of("status", "ok", "label", "neutral"),
                "input_mode", "voice"
        );
        when(conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(aiAnalysisService.analyzeVoice(any(), any(), any(), any(), any(), any())).thenReturn(analysis);
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(emotionAnalysisService.saveAnalysis(any(), eq(analysis))).thenReturn(null);

        AtomicInteger writes = new AtomicInteger();
        ByteArrayOutputStream committedBytes = new ByteArrayOutputStream();
        OutputStream disconnectOnComplete = new OutputStream() {
            @Override
            public void write(int value) {
                committedBytes.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (writes.incrementAndGet() == 4) {
                    throw new IOException("simulated client disconnect");
                }
                committedBytes.write(bytes, offset, length);
            }
        };

        StreamingResponseBody stream = chatController
                .voiceChatStream(user, audio, "I feel ready to talk.", null).getBody();

        assertNotNull(stream);
        assertDoesNotThrow(() -> stream.writeTo(disconnectOnComplete));
        assertTrue(committedBytes.toString(StandardCharsets.UTF_8).contains("event: progress"));
        verify(conversationRepository, times(1)).save(any(Conversation.class));
    }

    @Test
    void voiceStreamWritesSafeErrorEventWhenProcessingFailsAfterCommit() throws Exception {
        ReflectionTestUtils.setField(chatController, "objectMapper", new ObjectMapper());
        MockMultipartFile audio = new MockMultipartFile(
                "audio", "recording.webm", "audio/webm", new byte[]{1, 2, 3}
        );
        when(conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(aiAnalysisService.analyzeVoice(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The audio service is temporarily unavailable.", "AI_SERVICE_UNAVAILABLE"));

        StreamingResponseBody stream = chatController
                .voiceChatStream(user, audio, null, null).getBody();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertNotNull(stream);
        assertDoesNotThrow(() -> stream.writeTo(output));

        String events = output.toString(StandardCharsets.UTF_8);
        assertTrue(events.contains("event: progress"));
        assertTrue(events.contains("event: error"));
        assertTrue(events.contains("AI_SERVICE_UNAVAILABLE"));
        assertFalse(events.contains("event: complete"));
    }

    @Test
    void voiceAnalysisEventAndPersistenceSurviveLocalResponseUnavailable() throws Exception {
        ReflectionTestUtils.setField(chatController, "objectMapper", new ObjectMapper());
        MockMultipartFile audio = new MockMultipartFile(
                "audio", "recording.webm", "audio/webm", new byte[]{1, 2, 3}
        );
        Map<String, Object> analysis = Map.of(
                "text", "I feel tired today.",
                "acoustic_features", Map.of("status", "ok", "mean_pitch_hz", 185.0),
                "voice_emotion", Map.of("status", "ok", "label", "sadness"),
                "analysis_status", "partial",
                "analysis_warnings", List.of("VOICE_ANALYSIS_FAILURE")
        );
        when(conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(aiAnalysisService.analyzeVoice(any(), any(), any(), any(), any(), any())).thenReturn(analysis);
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(emotionAnalysisService.saveAnalysis(any(), eq(analysis))).thenReturn(null);
        when(chatResponseService.generateResponse(1L, "I feel tired today.", analysis))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The response service is temporarily unavailable.", "OLLAMA_UNAVAILABLE"));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StreamingResponseBody stream = chatController
                .voiceChatStream(user, audio, "I feel tired today.", null).getBody();
        assertNotNull(stream);
        stream.writeTo(output);

        String events = output.toString(StandardCharsets.UTF_8);
        assertTrue(events.contains("event: analysis"));
        assertTrue(events.contains("mean_pitch_hz"));
        assertTrue(events.contains("VOICE_ANALYSIS_FAILURE"));
        assertTrue(events.contains("event: error"));
        assertFalse(events.contains("event: complete"));
        verify(conversationRepository).save(any(Conversation.class));
        verify(emotionAnalysisService).saveAnalysis(any(), eq(analysis));
    }

    @Test
    void voiceChat_empty_audio_throws_bad_request() {
        MockMultipartFile emptyAudio = new MockMultipartFile(
                "audio",
                "empty.wav",
                "audio/wav",
                new byte[0]
        );

        ApiException exc = assertThrows(ApiException.class, () ->
                chatController.voiceChat(user, emptyAudio, null, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exc.getStatus());
        assertTrue(exc.getMessage().contains("empty"));
    }

    @Test
    void voiceChat_oversized_audio_throws_payload_too_large() {
        byte[] oversizedBytes = new byte[11 * 1024 * 1024]; // 11MB
        MockMultipartFile largeAudio = new MockMultipartFile(
                "audio",
                "large.wav",
                "audio/wav",
                oversizedBytes
        );

        ApiException exc = assertThrows(ApiException.class, () ->
                chatController.voiceChat(user, largeAudio, null, null)
        );

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exc.getStatus());
    }

    @Test
    void voiceChat_unsupported_audio_format_throws_bad_request() {
        MockMultipartFile invalidFormat = new MockMultipartFile(
                "audio",
                "malicious.exe",
                "application/x-dosexec",
                new byte[]{1, 2, 3}
        );

        ApiException exc = assertThrows(ApiException.class, () ->
                chatController.voiceChat(user, invalidFormat, null, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exc.getStatus());
        assertTrue(exc.getMessage().contains("Unsupported audio format"));
    }

    @Test
    void voiceChat_unauthorized_conversation_ownership_throws_forbidden() {
        MockMultipartFile audio = new MockMultipartFile(
                "audio",
                "recording.webm",
                "audio/webm",
                new byte[]{1, 2, 3}
        );

        // Conversation owned by user 99L, authenticated user is 1L
        Conversation otherUserConv = new Conversation(99L, "hello", "hi");
        when(conversationRepository.findById(123L)).thenReturn(Optional.of(otherUserConv));

        ApiException exc = assertThrows(ApiException.class, () ->
                chatController.voiceChat(user, audio, null, 123L)
        );

        assertEquals(HttpStatus.FORBIDDEN, exc.getStatus());
        assertTrue(exc.getMessage().contains("Access denied"));
    }

    @Test
    void text_chat_regression_test() {
        ChatRequest request = new ChatRequest("Hello, I am feeling good today.");

        when(conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of());

        Map<String, Object> mockAiResult = Map.of(
                "nlp", Map.of("primary_emotion", Map.of("label", "joy", "score", 0.95)),
                "social_cues", Map.of("dominant_emotion", "joy", "distressLevel", "Low", "riskLevel", "Low")
        );

        when(aiAnalysisService.analyze(eq("Hello, I am feeling good today."), any(), any()))
                .thenReturn(mockAiResult);

        when(chatResponseService.generateResponse(eq(1L), eq("Hello, I am feeling good today."), eq(mockAiResult)))
                .thenReturn("Hello! It is wonderful to hear you are feeling good.");

        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(emotionAnalysisService.saveAnalysis(any(), eq(mockAiResult)))
                .thenReturn(new EmotionAnalysis(1L, "Positive", "Low", "Low", 0.95));

        ResponseEntity<Map<String, Object>> response = chatController.chat(user, request);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Hello! It is wonderful to hear you are feeling good.", response.getBody().get("response"));
        assertEquals("Positive", response.getBody().get("emotion"));
    }
}
