package com.adaptivesense.backend.controller;

import com.adaptivesense.backend.dto.ChatRequest;
import com.adaptivesense.backend.entity.Conversation;
import com.adaptivesense.backend.entity.EmotionAnalysis;
import com.adaptivesense.backend.exception.ApiException;
import com.adaptivesense.backend.repository.ConversationRepository;
import com.adaptivesense.backend.security.AuthenticatedUser;
import com.adaptivesense.backend.service.AIAnalysisService;
import com.adaptivesense.backend.service.EmotionAnalysisService;
import com.adaptivesense.backend.service.GeminiService;
import com.adaptivesense.backend.service.UserMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${audio.max-size-bytes:10485760}")
    private long maxAudioSizeBytes = 10L * 1024 * 1024;

    private final ConversationRepository conversationRepository;
    private final GeminiService geminiService;
    private final EmotionAnalysisService emotionAnalysisService;
    private final UserMemoryService userMemoryService;
    private final AIAnalysisService aiAnalysisService;

    public ChatController(
            ConversationRepository conversationRepository,
            GeminiService geminiService,
            EmotionAnalysisService emotionAnalysisService,
            UserMemoryService userMemoryService,
            AIAnalysisService aiAnalysisService) {

        this.conversationRepository =
                conversationRepository;

        this.geminiService =
                geminiService;

        this.emotionAnalysisService =
                emotionAnalysisService;

        this.userMemoryService =
                userMemoryService;

        this.aiAnalysisService =
                aiAnalysisService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ChatRequest request) {

        Long userId = user.getUserId();
        String message = request.getMessage();

        List<Map<String, String>> conversationContext =
                buildConversationContext(userId);

        Map<String, Object> previousEmotion =
                buildPreviousEmotion(userId);

        Map<String, Object> aiAnalysis =
                aiAnalysisService.analyze(
                        message,
                        conversationContext,
                        previousEmotion
                );

        String response =
                geminiService.generateResponse(
                        userId,
                        message,
                        aiAnalysis
                );

        Conversation conversation =
                new Conversation(
                        userId,
                        message,
                        response
                );

        conversationRepository.save(
                conversation
        );

        EmotionAnalysis analysis =
                emotionAnalysisService.saveAnalysis(
                        conversation.getId(),
                        aiAnalysis
                );

        scheduleMemoryUpdate(userId, message);

        return ResponseEntity.ok(
                Map.of(
                        "response",
                        response,

                        "emotion",
                        analysis != null
                                ? analysis.getEmotion()
                                : "Unknown",

                        "distressLevel",
                        analysis != null
                                ? analysis.getDistressLevel()
                                : "Unknown",

                        "riskLevel",
                        analysis != null
                                ? analysis.getRiskLevel()
                                : "Unknown",

                        "confidence",
                        analysis != null
                                ? analysis.getConfidence()
                                : 0.0,

                        "safetyEscalation",
                        analysis != null
                                && "Elevated".equalsIgnoreCase(
                                        analysis.getRiskLevel()
                                )
                )
        );
    }

    @PostMapping(value = "/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> voiceChat(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "transcript", required = false) String clientTranscript,
            @RequestParam(value = "conversationId", required = false) Long conversationId) {

        return ResponseEntity.ok(processVoiceChat(user, audio, clientTranscript, conversationId,
                currentRequestId(), stage -> {}, analysis -> {}));
    }

    @PostMapping(value = "/voice/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> voiceChatStream(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "transcript", required = false) String clientTranscript,
            @RequestParam(value = "conversationId", required = false) Long conversationId) {

        String requestId = currentRequestId();
        StreamingResponseBody stream = output -> {
            long streamStarted = System.nanoTime();
            AtomicReference<String> streamStage = new AtomicReference<>("transcription");
            java.util.concurrent.atomic.AtomicBoolean streamFinished = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicBoolean completionSent = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.ScheduledExecutorService keepAlive =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "voice-sse-keepalive");
                        thread.setDaemon(true);
                        return thread;
                    });
            keepAlive.scheduleAtFixedRate(() -> {
                if (!streamFinished.get()) {
                    try {
                        synchronized (output) {
                            output.write(": keepalive\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            output.flush();
                        }
                    } catch (java.io.IOException exception) {
                        streamFinished.set(true);
                    }
                }
            }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
            try {
                writeSse(output, "progress", Map.of("stage", "accepted"));
                writeSse(output, "progress", Map.of("stage", "transcription"));
                streamStage.set("voice_analysis");
                writeSse(output, "progress", Map.of("stage", "voice_analysis"));
                Map<String, Object> result = processVoiceChat(
                        user, audio, clientTranscript, conversationId, requestId,
                        stage -> {
                            streamStage.set(stage);
                            writeSse(output, "progress", Map.of("stage", stage));
                        },
                        analysis -> writeSse(output, "analysis", analysis)
                );
                writeSse(output, "response", Map.of("response", result.getOrDefault("response", "")));
                Map<String, Object> completion = new LinkedHashMap<>(result);
                completion.remove("response");
                writeSse(output, "complete", completion);
                completionSent.set(true);
            } catch (SseClientDisconnectedException exception) {
                log.debug("Voice SSE client disconnected at stage={}.", streamStage.get());
            } catch (Exception exception) {
                String message = exception instanceof ApiException apiException
                        ? apiException.getMessage()
                        : "Voice processing failed. Please try again.";
                String code = exception instanceof ApiException apiException
                        ? apiException.getCode()
                        : "VOICE_PROCESSING_FAILURE";
                log.error("Voice SSE processing failed request_id={} stage={} category={} exception={}",
                        requestId == null ? "unavailable" : requestId,
                        streamStage.get(), code, exception.getClass().getSimpleName());
                try {
                    writeSse(output, "error", Map.of("code", code, "message", message));
                } catch (Exception writeException) {
                    log.debug("Unable to write voice SSE error event ({}).",
                            writeException.getClass().getSimpleName());
                }
            } finally {
                streamFinished.set(true);
                keepAlive.shutdownNow();
                log.info("Voice SSE lifecycle request_id={} final_stage={} complete={} total_ms={}",
                        requestId, streamStage.get(), completionSent.get(), elapsedMs(streamStarted));
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    private void writeSse(java.io.OutputStream output, String event, Object payload) {
        final String data;
        try {
            data = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize voice stream event.", exception);
        }
        byte[] eventBytes = ("event: " + event + "\ndata: " + data + "\n\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            synchronized (output) {
                output.write(eventBytes);
                output.flush();
            }
        } catch (java.io.IOException exception) {
            throw new SseClientDisconnectedException(exception);
        }
    }

    private void scheduleMemoryUpdate(Long userId, String message) {
        try {
            userMemoryService.updateMemoryAsync(userId, message);
        } catch (TaskRejectedException exception) {
            log.warn("Deferred memory update was not scheduled (executor rejected task).");
        } catch (RuntimeException exception) {
            log.warn("Deferred memory update was not scheduled ({}).",
                    exception.getClass().getSimpleName());
        }
    }

    private static final class SseClientDisconnectedException extends RuntimeException {
        private SseClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }

    private Map<String, Object> processVoiceChat(
            AuthenticatedUser user,
            MultipartFile audio,
            String clientTranscript,
            Long conversationId,
            String requestId,
            java.util.function.Consumer<String> onStage,
            java.util.function.Consumer<Map<String, Object>> onAnalysis) {
        long requestStarted = System.nanoTime();
        Long userId = user.getUserId();

        if (conversationId != null) {
            Conversation existing = conversationRepository.findById(conversationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));
            if (!existing.getUserId().equals(userId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Access denied: You do not own this conversation");
            }
        }
        if (audio == null || audio.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Audio file is empty");
        }
        if (audio.getSize() > maxAudioSizeBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Audio file exceeds maximum size limit of 10MB");
        }

        String filename = audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "recording.webm";
        String contentType = audio.getContentType() != null ? audio.getContentType() : "audio/webm";
        validateAudioMimeAndExtension(filename, contentType);

        byte[] audioBytes;
        try {
            audioBytes = audio.getBytes();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Failed to read audio file data");
        }

        List<Map<String, String>> conversationContext = buildConversationContext(userId);
        Map<String, Object> previousEmotion = buildPreviousEmotion(userId);

        long aiStarted = System.nanoTime();
        Map<String, Object> aiAnalysis = requestId == null
                ? aiAnalysisService.analyzeVoice(
                        audioBytes, filename, contentType, clientTranscript, conversationContext, previousEmotion)
                : aiAnalysisService.analyzeVoice(
                        audioBytes, filename, contentType, clientTranscript, conversationContext, previousEmotion, requestId);
        long aiServiceMs = elapsedMs(aiStarted);

        String transcribedText = aiAnalysis.get("text") == null ? "" : String.valueOf(aiAnalysis.get("text")).trim();
        if (transcribedText.isBlank() && clientTranscript != null) transcribedText = clientTranscript.trim();
        if (transcribedText.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Could not transcribe speech from audio recording");
        }

        // Persist and stream completed analysis before contacting Gemini so a
        // provider outage cannot discard the real transcript/acoustic results.
        Conversation conversation = conversationRepository.save(
                new Conversation(userId, transcribedText, null));
        EmotionAnalysis analysis = emotionAnalysisService.saveAnalysis(conversation.getId(), aiAnalysis);
        Map<String, Object> analysisEvent = new LinkedHashMap<>();
        analysisEvent.put("transcript", transcribedText);
        analysisEvent.put("conversationId", conversation.getId());
        analysisEvent.put("acousticFeatures", aiAnalysis.getOrDefault("acoustic_features", Map.of()));
        analysisEvent.put("voiceEmotion", aiAnalysis.getOrDefault("voice_emotion", Map.of()));
        analysisEvent.put("processingTimingsMs", Map.of("aiServiceRoundTrip", aiServiceMs,
                "aiPipeline", aiAnalysis.getOrDefault("timings_ms", Map.of())));
        onAnalysis.accept(analysisEvent);

        onStage.accept("response");
        long geminiStarted = System.nanoTime();
        String response = requestId == null
                ? geminiService.generateResponse(userId, transcribedText, aiAnalysis)
                : geminiService.generateResponse(userId, transcribedText, aiAnalysis, requestId);
        long geminiMs = elapsedMs(geminiStarted);

        long persistenceStarted = System.nanoTime();
        conversation.setAssistantResponse(response);
        conversationRepository.save(conversation);
        scheduleMemoryUpdate(userId, transcribedText);
        long persistenceMs = elapsedMs(persistenceStarted);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("response", response);
        body.put("transcript", transcribedText);
        body.put("conversationId", conversation.getId());
        body.put("emotion", analysis != null ? analysis.getEmotion() : "Unknown");
        body.put("distressLevel", analysis != null ? analysis.getDistressLevel() : "Low");
        body.put("riskLevel", analysis != null ? analysis.getRiskLevel() : "Low");
        body.put("confidence", analysis != null ? analysis.getConfidence() : 0.0);
        body.put("safetyEscalation", analysis != null && "Elevated".equalsIgnoreCase(analysis.getRiskLevel()));
        body.put("acousticFeatures", aiAnalysis.getOrDefault("acoustic_features", Map.of()));
        body.put("voiceEmotion", aiAnalysis.getOrDefault("voice_emotion", Map.of()));
        body.put("inputMode", "voice");

        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("aiServiceRoundTrip", aiServiceMs);
        timings.put("aiPipeline", aiAnalysis.getOrDefault("timings_ms", Map.of()));
        timings.put("gemini", geminiMs);
        timings.put("persistence", persistenceMs);
        timings.put("total", elapsedMs(requestStarted));
        body.put("processingTimingsMs", timings);

        log.info("Voice request timings (request_id={}, ms): aiServiceRoundTrip={}, gemini={}, persistence={}, total={}",
                requestId == null ? "unavailable" : requestId,
                aiServiceMs, geminiMs, persistenceMs, timings.get("total"));
        return body;
    }

    private String currentRequestId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        String candidate = attributes.getRequest().getHeader("X-Request-ID");
        if (candidate == null || !candidate.matches("[A-Za-z0-9_-]{1,80}")) {
            return java.util.UUID.randomUUID().toString();
        }
        return candidate;
    }

    private static long elapsedMs(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static final Set<String> ALLOWED_AUDIO_EXTENSIONS = Set.of(
            ".webm", ".wav", ".mp3", ".mp4", ".m4a", ".ogg", ".oga", ".flac"
    );

    private static final Set<String> ALLOWED_AUDIO_TYPES = Set.of(
            "audio/webm", "video/webm", "audio/wav", "audio/x-wav", "audio/wave",
            "audio/mp3", "audio/mpeg", "audio/mp4", "audio/m4a", "audio/x-m4a",
            "audio/ogg", "audio/flac", "application/octet-stream"
    );

    private void validateAudioMimeAndExtension(String filename, String contentType) {
        String ext = "";
        if (filename != null && filename.contains(".")) {
            ext = filename.substring(filename.lastIndexOf('.')).toLowerCase();
        }

        String normalizedType = contentType != null ? contentType.split(";")[0].trim().toLowerCase() : "";

        boolean typeOk = normalizedType.isEmpty()
                || ALLOWED_AUDIO_TYPES.contains(normalizedType)
                || normalizedType.startsWith("audio/");
        boolean extOk = ext.isEmpty() || ALLOWED_AUDIO_EXTENSIONS.contains(ext);

        if (!typeOk || !extOk) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported audio format. Use webm, wav, mp3, m4a, ogg, or flac.");
        }
    }

    private List<Map<String, String>> buildConversationContext(Long userId) {
        List<Conversation> recentConversations = new ArrayList<>(
                conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId)
        );
        Collections.reverse(recentConversations);

        List<Map<String, String>> conversationContext = new ArrayList<>();
        for (Conversation conversation : recentConversations) {
            conversationContext.add(Map.of(
                    "user", conversation.getUserMessage() != null ? conversation.getUserMessage() : "",
                    "assistant", conversation.getAssistantResponse() != null ? conversation.getAssistantResponse() : ""
            ));
        }
        return conversationContext;
    }

    private Map<String, Object> buildPreviousEmotion(Long userId) {
        List<Conversation> recentConversations =
                conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId);
        if (recentConversations.isEmpty()) {
            return Map.of();
        }

        Conversation latestConversation = recentConversations.get(0);
        List<EmotionAnalysis> previousAnalyses =
                emotionAnalysisService.getByConversationId(latestConversation.getId());

        if (previousAnalyses.isEmpty()) {
            return Map.of();
        }

        EmotionAnalysis latest = previousAnalyses.get(previousAnalyses.size() - 1);
        return Map.of(
                "emotion", latest.getEmotion() != null ? latest.getEmotion() : "Unknown",
                "distressLevel", latest.getDistressLevel() != null ? latest.getDistressLevel() : "Low",
                "riskLevel", latest.getRiskLevel() != null ? latest.getRiskLevel() : "Low",
                "confidence", latest.getConfidence() != null ? latest.getConfidence() : 0.0
        );
    }

    @GetMapping("/history")
    public ResponseEntity<List<Conversation>>
    getConversationHistory(
            @AuthenticationPrincipal AuthenticatedUser user) {

        List<Conversation> conversations =
                conversationRepository
                        .findByUserIdOrderByCreatedAtDesc(
                                user.getUserId()
                        );

        return ResponseEntity.ok(
                conversations
        );
    }

    @GetMapping("/emotion/latest")
    public ResponseEntity<?> getLatestEmotion(
            @AuthenticationPrincipal AuthenticatedUser user) {

        EmotionAnalysis latest =
                emotionAnalysisService.getLatestByUserId(
                        user.getUserId()
                );

        if (latest == null) {
            return ResponseEntity
                    .notFound()
                    .build();
        }

        return ResponseEntity.ok(
                Map.of(
                        "emotion",
                        latest.getEmotion(),

                        "distressLevel",
                        latest.getDistressLevel(),

                        "riskLevel",
                        latest.getRiskLevel(),

                        "confidence",
                        latest.getConfidence()
                )
        );
    }

    @GetMapping("/emotion/history")
    public ResponseEntity<List<EmotionAnalysis>>
    getEmotionHistory(
            @AuthenticationPrincipal AuthenticatedUser user) {

        List<EmotionAnalysis> history =
                emotionAnalysisService
                        .getRecentAnalyses(
                                user.getUserId(),
                                10
                        );

        return ResponseEntity.ok(
                history
        );
    }
}
