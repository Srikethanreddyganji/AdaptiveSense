package com.adaptivesense.backend.service;

import com.adaptivesense.backend.entity.EmotionAnalysis;
import com.adaptivesense.backend.repository.ConversationRepository;
import com.adaptivesense.backend.repository.EmotionAnalysisRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmotionAnalysisServiceTest {

    @Mock
    private EmotionAnalysisRepository emotionAnalysisRepository;

    @Mock
    private ConversationRepository conversationRepository;

    @InjectMocks
    private EmotionAnalysisService emotionAnalysisService;

    @Test
    void mapsSevereDistressToElevatedRisk() {

        when(emotionAnalysisRepository.save(any()))
                .thenAnswer(
                        invocation ->
                                invocation.getArgument(0)
                );

        Map<String, Object> aiAnalysis =
                Map.of(
                        "nlp",
                        Map.of(
                                "primary_emotion",
                                Map.of(
                                        "label",
                                        "sadness",
                                        "score",
                                        0.91
                                )
                        ),
                        "social_cues",
                        Map.of(
                                "severe_distress",
                                true,
                                "distressLevel",
                                "High",
                                "riskLevel",
                                "Elevated",
                                "dominant_emotion",
                                "sadness"
                        )
                );

        EmotionAnalysis saved =
                emotionAnalysisService.saveAnalysis(
                        1L,
                        aiAnalysis
                );

        assertEquals("Sadness", saved.getEmotion());
        assertEquals("High", saved.getDistressLevel());
        assertEquals("Elevated", saved.getRiskLevel());

        ArgumentCaptor<EmotionAnalysis> captor =
                ArgumentCaptor.forClass(
                        EmotionAnalysis.class
                );

        verify(emotionAnalysisRepository).save(
                captor.capture()
        );

        assertEquals(
                "Elevated",
                captor.getValue().getRiskLevel()
        );
    }
}
