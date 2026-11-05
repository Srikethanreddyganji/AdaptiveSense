import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import api from "../services/api";
import { clearSession } from "../utils/session";
import { createRecordingEpoch, createRecordingSession, createSubmissionGate, mergeRecognizedTranscript } from "../services/voiceLifecycle";
import { consumeVoiceSse, VoiceStreamError, voiceHttpErrorMessage } from "../services/voiceSse";
import "./VoiceConversation.css";

const MAX_RECORDING_DURATION_MS = 60_000;

function VoiceConversation() {
    const navigate = useNavigate();

    // Conversation state
    const [transcript, setTranscript] = useState("");
    const [interimTranscript, setInterimTranscript] = useState("");
    const [response, setResponse] = useState("");

    // Voice analysis results
    const [acousticFeatures, setAcousticFeatures] = useState(null);
    const [voiceEmotion, setVoiceEmotion] = useState(null);
    const [analysisWarnings, setAnalysisWarnings] = useState([]);

    // Activity state
    const [listening, setListening] = useState(false);
    const [speaking, setSpeaking] = useState(false);
    const [loading, setLoading] = useState(false);
    const [processingStage, setProcessingStage] = useState("");
    const [processingTimings, setProcessingTimings] = useState(null);
    const [recording, setRecording] = useState(false);
    const [starting, setStarting] = useState(false);
    const [isMuted, setIsMuted] = useState(false);

    // Recording duration timer
    const [recordingDuration, setRecordingDuration] = useState(0);

    // Consent and recorded-audio state
    // Consent is deliberately false whenever this component is mounted.
    const [recordingConsent, setRecordingConsent] = useState(false);
    const [audioBlob, setAudioBlob] = useState(null);
    const [audioUrl, setAudioUrl] = useState("");
    const audioUrlRef = useRef("");

    // Error state
    const [error, setError] = useState("");

    // Refs
    const recognitionRef = useRef(null);
    const mediaRecorderRef = useRef(null);
    const mediaStreamRef = useRef(null);
    const mountedRef = useRef(false);
    const startingRef = useRef(false);
    const discardRecordingRef = useRef(false);
    const recordingSessionRef = useRef(null);
    // Fence late MediaRecorder/SpeechRecognition events from prior sessions.
    const recordingEpochRef = useRef(createRecordingEpoch());
    const recognitionRunningRef = useRef(false);
    const recognitionRestartEnabledRef = useRef(false);
    const recognitionRestartTimerRef = useRef(null);
    const submissionGateRef = useRef(createSubmissionGate());
    const abortControllerRef = useRef(null);
    const isMutedRef = useRef(false);

    const clearAudioPreview = () => {
        if (audioUrlRef.current) URL.revokeObjectURL(audioUrlRef.current);
        audioUrlRef.current = "";
        setAudioUrl("");
    };

    // Stop microphone and speech synthesis when leaving this page.
    useEffect(() => {
        mountedRef.current = true;

        return () => {
            mountedRef.current = false;
            if (audioUrlRef.current) URL.revokeObjectURL(audioUrlRef.current);
            audioUrlRef.current = "";
            recognitionRestartEnabledRef.current = false;
            if (recognitionRestartTimerRef.current) {
                clearTimeout(recognitionRestartTimerRef.current);
                recognitionRestartTimerRef.current = null;
            }
            abortControllerRef.current?.abort();

            if (recognitionRef.current) {
                try {
                    recognitionRef.current.onend = null;
                    recognitionRef.current.onerror = null;
                    recognitionRef.current.onstart = null;
                    recognitionRef.current.onresult = null;
                    recognitionRef.current.stop();
                } catch {
                    // Recognition may already be stopped.
                }
            }

            const recorder = mediaRecorderRef.current;

            recordingSessionRef.current?.dispose();

            if (recorder) {
                recorder.ondataavailable = null;
                recorder.onstop = null;
                recorder.onerror = null;

                if (recorder.state !== "inactive") {
                    try {
                        recorder.stop();
                    } catch {
                        // Recorder may already be stopped.
                    }
                }
            }

            if (mediaStreamRef.current) {
                mediaStreamRef.current
                    .getTracks()
                    .forEach((track) => track.stop());

                mediaStreamRef.current = null;
            }

            if ("speechSynthesis" in window) {
                window.speechSynthesis.cancel();
            }
        };
    }, []);

    // Create a SpeechRecognition instance and attach its event handlers.
    const createRecognition = () => {
        const SpeechRecognition =
            window.SpeechRecognition ||
            window.webkitSpeechRecognition;

        if (!SpeechRecognition) {
            throw new Error(
                "Speech recognition is not supported. Please use Google Chrome."
            );
        }

        const recognition = new SpeechRecognition();

        recognition.continuous = true;
        recognition.interimResults = true;
        recognition.lang = "en-IN";
        recognition.maxAlternatives = 1;

        recognition.onstart = () => {
            if (!mountedRef.current || recognitionRef.current !== recognition) return;

            recognitionRunningRef.current = true;
            setListening(true);
            setError("");
        };

        recognition.onresult = (event) => {
            if (recognitionRef.current !== recognition) return;
            let finalText = "";
            let temporaryText = "";

            for (
                let i = event.resultIndex;
                i < event.results.length;
                i += 1
            ) {
                const result = event.results[i];
                const spokenText = result[0].transcript;

                if (result.isFinal) {
                    finalText += spokenText;
                } else {
                    temporaryText += spokenText;
                }
            }

            if (!mountedRef.current) return;

            setInterimTranscript(temporaryText);

            if (finalText) {
                setTranscript((previousText) => mergeRecognizedTranscript(previousText, finalText));
            }
        };

        recognition.onerror = (event) => {
            if (recognitionRef.current !== recognition) return;
            recognitionRunningRef.current = false;
            if (mountedRef.current) {
                setListening(false);

                if (event.error === "not-allowed" || event.error === "service-not-allowed") {
                    recognitionRestartEnabledRef.current = false;
                    setError(
                        "Live transcription is unavailable. Recording continues and will be transcribed when you send it."
                    );
                } else if (event.error === "no-speech") {
                    setError("");
                } else if (event.error !== "aborted") {
                    setError("Live transcription paused; your recording is still active.");
                }
            }
        };

        recognition.onend = () => {
            if (recognitionRef.current !== recognition) return;
            recognitionRunningRef.current = false;
            if (!mountedRef.current) return;

            setListening(false);
            setInterimTranscript("");

            // Browser speech recognition often ends after silence. Restart it without
            // touching MediaRecorder, so pauses remain in the uploaded audio.
            const recorderStillActive = recordingSessionRef.current?.recognitionEnded() === true;
            if (recognitionRestartEnabledRef.current && recorderStillActive) {
                if (recognitionRestartTimerRef.current) {
                    clearTimeout(recognitionRestartTimerRef.current);
                }
                recognitionRestartTimerRef.current = setTimeout(() => {
                    recognitionRestartTimerRef.current = null;
                    if (recognitionRef.current !== recognition || !recognitionRestartEnabledRef.current || !recordingSessionRef.current?.isRecording()) return;
                    try {
                        recognition.start();
                    } catch {
                        // A browser may still be finalizing the prior recognition session.
                        recognitionRestartTimerRef.current = setTimeout(() => {
                            recognitionRestartTimerRef.current = null;
                            if (recognitionRef.current === recognition && recognitionRestartEnabledRef.current && recordingSessionRef.current?.isRecording()) {
                                try { recognition.start(); } catch { /* Retry on the next end event. */ }
                            }
                        }, 500);
                    }
                }, 250);
            }
        };

        return recognition;
    };

    // Start the actual microphone recording.
    const startAudioRecording = async (recordingId) => {
        if (
            !navigator.mediaDevices?.getUserMedia ||
            !window.MediaRecorder
        ) {
            throw new Error(
                "Audio recording is not supported in this browser. Please use Chrome."
            );
        }

        let permissionTimedOut = false;
        let permissionTimer;
        const streamRequest = navigator.mediaDevices.getUserMedia({
            audio: {
                echoCancellation: true,
                noiseSuppression: true,
                autoGainControl: true,
            },
        }).then((stream) => {
            if (permissionTimedOut || !mountedRef.current || !recordingEpochRef.current.isCurrent(recordingId)) {
                stream.getTracks().forEach((track) => track.stop());
                return null;
            }
            return stream;
        });

        let stream;
        try {
            stream = await Promise.race([
                streamRequest,
                new Promise((_, reject) => {
                    permissionTimer = setTimeout(() => {
                        permissionTimedOut = true;
                        reject(new Error("Microphone access took too long. Check the browser permission and try again."));
                    }, 15_000);
                }),
            ]);
        } finally {
            clearTimeout(permissionTimer);
        }

        if (!stream) return false;

        // A permission prompt can resolve after navigation or after this session
        // has already been superseded. Never let that stream replace a newer one.
        if (!mountedRef.current || !recordingEpochRef.current.isCurrent(recordingId)) {
            stream.getTracks().forEach((track) => track.stop());
            return false;
        }

        mediaStreamRef.current = stream;
        const audioChunks = [];
        discardRecordingRef.current = false;

        const supportedTypes = [
            "audio/webm;codecs=opus",
            "audio/webm",
            "audio/mp4",
        ];

        const mimeType =
            supportedTypes.find((type) =>
                MediaRecorder.isTypeSupported(type)
            ) || "";

        let recorder;

        try {
            recorder = mimeType
                ? new MediaRecorder(stream, { mimeType })
                : new MediaRecorder(stream);
        } catch (err) {
            stream.getTracks().forEach((track) => track.stop());
            mediaStreamRef.current = null;
            throw err;
        }

        mediaRecorderRef.current = recorder;

        recorder.ondataavailable = (event) => {
            if (event.data && event.data.size > 0) {
                audioChunks.push(event.data);
            }
        };

        recorder.onerror = () => {
            if (!recordingEpochRef.current.isCurrent(recordingId)) return;
            if (mountedRef.current) {
                setError("Audio recording failed. Please try again.");
            }

            stopAudioRecording();
        };

        recorder.onstop = () => {
            const actualMimeType = recorder.mimeType || "audio/webm";

            const isCurrentRecording = recordingEpochRef.current.isCurrent(recordingId);
            const blob = new Blob(audioChunks, {
                type: actualMimeType,
            });

            if (
                mountedRef.current &&
                isCurrentRecording &&
                !discardRecordingRef.current &&
                blob.size > 0
            ) {
                try {
                    setAudioBlob(blob);
                    if (audioUrlRef.current) URL.revokeObjectURL(audioUrlRef.current);
                    audioUrlRef.current = URL.createObjectURL(blob);
                    setAudioUrl(audioUrlRef.current);
                } catch (error) {
                    setError("The recording finished, but its preview could not be prepared. You can try recording again.");
                    console.error("Unable to prepare recorded audio preview", error);
                }
            }

            // Always release this recorder's own stream. Only clear shared refs
            // and React state if a newer recording has not taken ownership.
            stream.getTracks().forEach((track) => track.stop());
            if (recordingEpochRef.current.isCurrent(recordingId)) {
                if (mediaRecorderRef.current === recorder) mediaRecorderRef.current = null;
                if (recordingSessionRef.current?.recorder === recorder) recordingSessionRef.current = null;
                recognitionRunningRef.current = false;
                if (mountedRef.current) setRecording(false);
            }
            if (mediaStreamRef.current === stream) mediaStreamRef.current = null;
        };

        recordingSessionRef.current = createRecordingSession(recorder, {
            maxDurationMs: MAX_RECORDING_DURATION_MS,
            timesliceMs: 250,
            onDuration: (durationMs) => {
                if (mountedRef.current && recordingEpochRef.current.isCurrent(recordingId)) {
                    setRecordingDuration(Math.floor(durationMs / 1000));
                }
            },
            onStopRequested: () => {
                recognitionRestartEnabledRef.current = false;
                if (recognitionRestartTimerRef.current) {
                    clearTimeout(recognitionRestartTimerRef.current);
                    recognitionRestartTimerRef.current = null;
                }
                const recognition = recognitionRef.current;
                if (recognition && recognitionRunningRef.current) {
                    try { recognition.stop(); } catch { /* Recognition may already have ended. */ }
                }
            },
        });
        try {
            recordingSessionRef.current.start();
        } catch (error) {
            if (recordingSessionRef.current?.recorder === recorder) recordingSessionRef.current = null;
            stream.getTracks().forEach((track) => track.stop());
            mediaStreamRef.current = null;
            throw error;
        }
        setRecording(true);
        return true;
    };

    // Stop the recorder and release microphone tracks.
    const stopAudioRecording = (reason = "user") => {
        const session = recordingSessionRef.current;
        if (session?.stop(reason)) return;

        const recorder = mediaRecorderRef.current;

        if (recorder && recorder.state !== "inactive") {
            try {
                recorder.stop();
            } catch (error) {
                console.error("Unable to stop audio recording", error);
            }
            return;
        }

        if (mediaStreamRef.current) {
            mediaStreamRef.current
                .getTracks()
                .forEach((track) => track.stop());

            mediaStreamRef.current = null;
        }

        if (mountedRef.current) {
            setRecording(false);
        }
    };

    // Start speech recognition and audio recording.
    const startListening = async () => {
        // This check must happen before getUserMedia() or recognition.start().
        if (!recordingConsent) {
            setError(
                "Please tick the consent checkbox before starting voice recording."
            );
            return;
        }

        if (
            loading ||
            listening ||
            recording ||
            startingRef.current
        ) {
            return;
        }

        const SpeechRecognition =
            window.SpeechRecognition ||
            window.webkitSpeechRecognition;

        startingRef.current = true;
        setStarting(true);
        setError("");

        window.speechSynthesis?.cancel();
        setSpeaking(false);

        setTranscript("");
        setInterimTranscript("");
        setResponse("");
        setAcousticFeatures(null);
        setVoiceEmotion(null);
        setAnalysisWarnings([]);
        clearAudioPreview();
        setAudioBlob(null);

        let recognition = null;

        const recordingId = recordingEpochRef.current.begin();

        try {
            // Microphone access is requested only after consent.
            const recordingStarted = await startAudioRecording(recordingId);
            if (!recordingStarted || !recordingEpochRef.current.isCurrent(recordingId)) return;

            // The user may have navigated away while permission was pending.
            if (!mountedRef.current) {
                stopAudioRecording();
                return;
            }

            if (SpeechRecognition) {
                recognitionRestartEnabledRef.current = true;
                recognition = createRecognition();
                recognitionRef.current = recognition;
                recognition.start();
            } else {
                setError(
                    "Live transcription is unavailable in this browser. Your recorded audio will still be transcribed when you send it."
                );
            }
        } catch (err) {

            if (recognition) {
                try {
                    recognition.onend = null;
                    recognition.onerror = null;
                    recognition.stop();
                } catch {
                    // Recognition may not have started.
                }
            }

            if (recognitionRef.current === recognition) {
                recognitionRef.current = null;
            }

            stopAudioRecording();

            if (mountedRef.current) {
                setListening(false);
                setError(
                    err.message ||
                    "Could not start the microphone. Please try again."
                );
            }
        } finally {
            startingRef.current = false;

            if (mountedRef.current) {
                setStarting(false);
            }
        }
    };

    // The button stops the recorder. Speech recognition is stopped as a side effect,
    // and its onend handler never controls the recorder lifecycle.
    const stopRecording = () => {
        stopAudioRecording("user");
        setListening(false);
        setInterimTranscript("");
    };

    // Speak the AI response using the browser's speech synthesis.
    const speakResponse = (text) => {
        if (!text || !("speechSynthesis" in window)) {
            return;
        }

        window.speechSynthesis.cancel();

        const speech = new SpeechSynthesisUtterance(text);
        speech.lang = "en-IN";
        speech.rate = 0.95;
        speech.pitch = 1;

        speech.onstart = () => setSpeaking(true);
        speech.onend = () => setSpeaking(false);
        speech.onerror = () => setSpeaking(false);

        window.speechSynthesis.speak(speech);
    };

    // Send the ACTUAL recorded audio blob + transcript to the voice analysis endpoint.
    const sendToAdaptiveSense = async () => {
        if (!audioBlob || recordingSessionRef.current?.isRecording()) {
            setError("No recording found. Please record your voice first.");
            return;
        }

        if (!submissionGateRef.current.acquire()) return;

        setLoading(true);
        setProcessingStage("uploading");
        setProcessingTimings(null);
        setResponse("");
        setAcousticFeatures(null);
        setVoiceEmotion(null);
        setAnalysisWarnings([]);
        setError("");

        try {
            const formData = new FormData();
            const ext = audioBlob.type.includes("mp4") ? ".mp4"
                : audioBlob.type.includes("ogg") ? ".ogg"
                : ".webm";
            formData.append("audio", audioBlob, `recording${ext}`);

            if (transcript.trim()) {
                formData.append("transcript", transcript.trim());
            }

            const token = localStorage.getItem("token");
            const requestId = window.crypto?.randomUUID?.()
                || `voice-${Date.now()}-${Math.random().toString(16).slice(2)}`;
            const abortController = new AbortController();
            abortControllerRef.current = abortController;
            const baseURL = api.defaults.baseURL.replace(/\/$/, "");
            const uploadStarted = performance.now();
            let streamResponse;
            try {
                streamResponse = await fetch(`${baseURL}/chat/voice/stream`, {
                    method: "POST",
                    headers: {
                        Accept: "text/event-stream",
                        "X-Request-ID": requestId,
                        ...(token ? { Authorization: `Bearer ${token}` } : {}),
                    },
                    body: formData,
                    signal: abortController.signal,
                });
            } catch (fetchError) {
                if (fetchError.name === "AbortError") throw fetchError;
                throw new VoiceStreamError(
                    "Could not connect to AdaptiveSense. Check that the frontend proxy and backend are running.",
                    "VOICE_NETWORK_ERROR",
                );
            }
            const uploadMs = Math.round(performance.now() - uploadStarted);

            if (streamResponse.status === 401) {
                clearSession();
                window.location.assign("/login");
                throw new VoiceStreamError("Your session expired. Please sign in again.", "UNAUTHENTICATED");
            }
            if (!streamResponse.ok || !streamResponse.body) {
                const errorText = await streamResponse.text();
                let payload = {};
                try { payload = JSON.parse(errorText); } catch { /* Use the status message. */ }
                throw new VoiceStreamError(
                    voiceHttpErrorMessage(streamResponse.status, payload),
                    payload.code || `HTTP_${streamResponse.status}`,
                );
            }
            if (!streamResponse.headers.get("content-type")?.toLowerCase().includes("text/event-stream")) {
                const responseText = await streamResponse.text();
                let payload = {};
                try { payload = JSON.parse(responseText); } catch { /* Keep the safe fallback. */ }
                throw new VoiceStreamError(
                    voiceHttpErrorMessage(streamResponse.status, payload),
                    "INVALID_STREAM_CONTENT_TYPE",
                );
            }

            setProcessingStage("transcription");
            const applyAnalysis = (payload) => {
                setTranscript(payload.transcript || transcript);
                setAcousticFeatures(payload.acousticFeatures || null);
                setVoiceEmotion(payload.voiceEmotion || null);
                setAnalysisWarnings(Array.isArray(payload.analysisWarnings) ? payload.analysisWarnings : []);
                setProcessingTimings(payload.processingTimingsMs
                    ? { ...payload.processingTimingsMs, clientRequestToStreamHeadersMs: uploadMs }
                    : null);
            };
            let responseEventReceived = false;

            await consumeVoiceSse(streamResponse, (eventName, payload) => {
                if (eventName === "progress") {
                    setProcessingStage(payload.stage || "analyzing_voice");
                } else if (eventName === "analysis") {
                    applyAnalysis(payload);
                } else if (eventName === "response") {
                    responseEventReceived = true;
                    const aiResponse = payload.response || "";
                    setResponse(aiResponse);
                    if (aiResponse && !isMutedRef.current) speakResponse(aiResponse);
                } else if (eventName === "complete") {
                    applyAnalysis(payload);
                    const aiResponse = payload.response || "";
                    if (!responseEventReceived && aiResponse) {
                        setResponse(aiResponse);
                        if (!isMutedRef.current) speakResponse(aiResponse);
                    }
                    if (payload.responseError) {
                        setError(voiceHttpErrorMessage(
                            payload.responseError.status || 503,
                            payload.responseError,
                        ));
                    }
                }
            });

        } catch (err) {
            setError(
                err.name === "AbortError"
                    ? "Voice processing was cancelled. Please submit the recording again."
                    : err instanceof VoiceStreamError
                        ? err.message
                        : err.message ||
                "Sorry, something went wrong with the voice analysis. Please try again."
            );
        } finally {
            abortControllerRef.current = null;
            submissionGateRef.current.release();
            setLoading(false);
            setProcessingStage("");
        }
    };


    // Stop the spoken AI response.
    const stopSpeaking = () => {
        window.speechSynthesis.cancel();
        setSpeaking(false);
    };

    // Start a fresh voice conversation and discard the previous recording.
    const startNewVoiceConversation = () => {
        // Invalidate queued callbacks before disposing the previous session.
        recordingEpochRef.current.invalidate();
        window.speechSynthesis.cancel();
        abortControllerRef.current?.abort();

        discardRecordingRef.current = true;
        recognitionRestartEnabledRef.current = false;
        if (recognitionRestartTimerRef.current) {
            clearTimeout(recognitionRestartTimerRef.current);
            recognitionRestartTimerRef.current = null;
        }

        if (recognitionRef.current) {
            try {
                recognitionRef.current.onend = null;
                recognitionRef.current.onerror = null;
                recognitionRef.current.onstart = null;
                recognitionRef.current.onresult = null;
                recognitionRef.current.stop();
            } catch {
                // Recognition may already be stopped.
            }

            recognitionRef.current = null;
        }

        stopAudioRecording();

        setTranscript("");
        setInterimTranscript("");
        setResponse("");
        clearAudioPreview();
        setAudioBlob(null);
        setAcousticFeatures(null);
        setVoiceEmotion(null);
        setAnalysisWarnings([]);
        setProcessingTimings(null);
        setRecordingDuration(0);
        setError("");

        setListening(false);
        setSpeaking(false);
        setRecording(false);
        setLoading(false);
        setProcessingStage("");
        setStarting(false);
        startingRef.current = false;

        // Consent is requested again for the new conversation.
        setRecordingConsent(false);
    };

    const handleConsentChange = (event) => {
        setRecordingConsent(event.target.checked);
        setError("");
    };

    return (
        <div className="voice-page">
            <main className="voice-main">
                {/* Header */}
                <header className="voice-header">
                    <button
                        type="button"
                        className="voice-back-button"
                        onClick={() => navigate("/dashboard")}
                    >
                        Back
                    </button>

                    <div className="voice-brand">
                        <div className="voice-brand-mark">AS</div>
                        <span>AdaptiveSense</span>
                    </div>
                </header>

                {/* Main content */}
                <section className="voice-content">
                    <div className="voice-heading">
                        <p className="voice-eyebrow">VOICE CONVERSATION</p>
                        <h1>Talk naturally</h1>
                        <p>
                            Speak with AdaptiveSense using your microphone.
                        </p>
                    </div>

                    {/* Recording consent */}
                    <label className="voice-consent">
                        <input
                            type="checkbox"
                            checked={recordingConsent}
                            onChange={handleConsentChange}
                            disabled={recording || listening || starting}
                        />

                        <span>
                            I consent to temporarily recording my voice in
                            this browser for the voice analysis feature. The
                            recording stays in this browser until you choose
                            to send it for transcription and voice analysis.
                        </span>
                    </label>

                    {/* Voice controls */}
                    <section
                        className={`voice-control-card ${
                            listening ? "is-listening" : ""
                        }`}
                    >
                        <div
                            className={`microphone-circle ${
                                listening ? "microphone-active" : ""
                            }`}
                        >
                            <svg
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="1.7"
                                aria-hidden="true"
                            >
                                <rect
                                    x="8"
                                    y="3"
                                    width="8"
                                    height="13"
                                    rx="4"
                                />
                                <path d="M5 11a7 7 0 0 0 14 0" />
                                <path d="M12 18v3" />
                                <path d="M9 21h6" />
                            </svg>
                        </div>

                        <div className="voice-control-text">
                            <h2>
                                {starting
                                    ? "Starting microphone..."
                                    : listening
                                    ? "Listening..."
                                    : recording
                                    ? "Recording your voice..."
                                    : loading
                                    ? "Processing your message"
                                    : speaking
                                    ? "AdaptiveSense is speaking"
                                    : "Ready to listen"}
                            </h2>

                            <p>
                                {starting
                                    ? "Please wait for the microphone to start."
                                    : listening
                                    ? "Speak naturally. Your words will appear below."
                                    : recording
                                    ? "Your microphone audio is being recorded."
                                    : loading
                                    ? "Please wait while your response is prepared."
                                    : speaking
                                    ? "You can stop the response whenever you want."
                                    : !recordingConsent
                                    ? "Give recording consent before starting."
                                    : "Press Start speaking when you're ready."}
                            </p>
                        </div>

                        {!listening && !recording && !loading && (
                            <button
                                type="button"
                                className="voice-primary-button"
                                onClick={startListening}
                                disabled={
                                    !recordingConsent ||
                                    loading ||
                                    starting
                                }
                                aria-disabled={
                                    !recordingConsent ||
                                    loading ||
                                    starting
                                }
                            >
                                <span className="button-mic-icon">
                                    <svg
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        strokeWidth="1.8"
                                        aria-hidden="true"
                                    >
                                        <rect
                                            x="8"
                                            y="3"
                                            width="8"
                                            height="13"
                                            rx="4"
                                        />
                                        <path d="M5 11a7 7 0 0 0 14 0" />
                                        <path d="M12 18v3" />
                                    </svg>
                                </span>
                                {starting ? "Starting..." : "Start speaking"}
                            </button>
                        )}

                        {(listening || recording) && (
                            <button
                                type="button"
                                className="voice-stop-button"
                                onClick={stopRecording}
                            >
                                Stop Recording
                            </button>
                        )}
                    </section>

                    {/* Recording indicator with timer */}
                    {recording && (
                        <p className="recording-status">
                            <span className="recording-dot" />
                            Recording your voice...{" "}
                            <span className="recording-timer">
                                {String(Math.floor(recordingDuration / 60)).padStart(2, "0")}:
                                {String(recordingDuration % 60).padStart(2, "0")}
                                {" "}/ 01:00
                            </span>
                        </p>
                    )}

                    {/* Live transcript */}
                    {listening && (
                        <section className="voice-transcript-card">
                            <div className="voice-card-header">
                                <div>
                                    <span className="voice-card-label">
                                        LIVE TRANSCRIPT
                                    </span>
                                    <h2>Listening to you</h2>
                                </div>

                                <span className="listening-indicator">
                                    <span />
                                    Live
                                </span>
                            </div>

                            <div className="transcript-area">
                                {transcript && (
                                    <p className="final-transcript">
                                        {transcript}
                                    </p>
                                )}

                                {interimTranscript && (
                                    <p className="interim-transcript">
                                        {interimTranscript}
                                    </p>
                                )}

                                {!transcript && !interimTranscript && (
                                    <p className="transcript-placeholder">
                                        Start speaking...
                                    </p>
                                )}
                            </div>
                        </section>
                    )}

                    {/* Recorded audio preview */}
                    {audioUrl && !recording && (
                        <section className="voice-message-card">
                            <span className="voice-card-label">
                                RECORDED AUDIO
                            </span>

                            <audio
                                controls
                                src={audioUrl}
                                style={{
                                    width: "100%",
                                    marginTop: "15px",
                                }}
                            />

                            <p>
                                Recording size:{" "}
                                {(audioBlob.size / 1024).toFixed(1)} KB
                                {" · "}
                                Duration: {recordingDuration}s
                            </p>
                        </section>
                    )}

                    {/* User transcript and send action */}
                    {!recording && !listening && audioBlob && !response && (
                        <section className="voice-message-card">
                            <span className="voice-card-label">
                                YOUR MESSAGE
                            </span>

                            {transcript && <p>{transcript}</p>}

                            {loading ? (
                                <div className="voice-loading-text" role="status" aria-live="polite">
                                    <p>{processingStage === "uploading"
                                        ? "Uploading your recording…"
                                        : processingStage === "transcription"
                                            ? "Transcribing your recording…"
                                            : processingStage === "voice_analysis"
                                                ? "Transcribing speech and analyzing acoustic voice features…"
                                                : processingStage === "response"
                                                    ? "Generating your response…"
                                                    : "Preparing your voice response…"}</p>
                                    <p className="processing-step">1. Audio upload {processingStage === "uploading" ? "in progress" : "complete"}</p>
                                    <p className="processing-step">2. Transcription and voice analysis {processingStage === "transcription" || processingStage === "voice_analysis" ? "in progress" : processingStage === "uploading" ? "waiting" : "complete"}</p>
                                    <p className="processing-step">3. AI response {processingStage === "response" ? "in progress" : processingStage === "uploading" || processingStage === "transcription" || processingStage === "voice_analysis" ? "waiting" : "complete"}</p>
                                </div>
                            ) : (
                                <button
                                    type="button"
                                    className="voice-primary-button send-button"
                                    onClick={sendToAdaptiveSense}
                                    disabled={loading}
                                >
                                    Send to AdaptiveSense
                                </button>
                            )}
                        </section>
                    )}

                    {/* Voice Emotion card */}
                    {analysisWarnings.includes("VOICE_ANALYSIS_FAILURE") && (
                        <p className="voice-analysis-note" role="status">
                            Voice analysis could not be completed. Your transcript and AI response remain available; no acoustic or voice emotion result was produced.
                        </p>
                    )}
                    {voiceEmotion?.status === "unavailable" && acousticFeatures && Object.keys(acousticFeatures).length > 0 && (
                        <p className="voice-analysis-note" role="status">
                            Acoustic features were calculated, but the voice emotion estimate is unavailable for this recording.
                        </p>
                    )}
                    {voiceEmotion && Object.keys(voiceEmotion).length > 0 && voiceEmotion.status !== "unavailable" && (
                        <section className="voice-acoustic-card">
                            <span className="voice-card-label">VOICE EMOTION</span>
                            <h2>
                                {voiceEmotion.label
                                    ? voiceEmotion.label.charAt(0).toUpperCase() +
                                      voiceEmotion.label.slice(1)
                                    : "—"}
                                {voiceEmotion.score != null && (
                                    <span className="acoustic-sub">
                                        {" "}({(voiceEmotion.score * 100).toFixed(0)}% confidence)
                                    </span>
                                )}
                            </h2>
                        </section>
                    )}

                    {/* Acoustic Features card */}
                    {acousticFeatures && Object.keys(acousticFeatures).length > 0 && acousticFeatures.status !== "unavailable" && (
                        <section className="voice-acoustic-card">
                            <span className="voice-card-label">ACOUSTIC ANALYSIS</span>
                            <div className="acoustic-grid">
                                {acousticFeatures.mean_pitch_hz != null && (
                                    <div className="acoustic-item">
                                        <span className="acoustic-label">Average Pitch</span>
                                        <span className="acoustic-value">
                                            {Number(acousticFeatures.mean_pitch_hz).toFixed(1)} Hz
                                        </span>
                                    </div>
                                )}
                                {acousticFeatures.pitch_std_hz != null && (
                                    <div className="acoustic-item">
                                        <span className="acoustic-label">Pitch Variation</span>
                                        <span className="acoustic-value">
                                            ±{Number(acousticFeatures.pitch_std_hz).toFixed(1)} Hz
                                        </span>
                                    </div>
                                )}
                                {acousticFeatures.speaking_rate != null && (
                                    <div className="acoustic-item">
                                        <span className="acoustic-label">Speaking Rate</span>
                                        <span className="acoustic-value">
                                            {Number(acousticFeatures.speaking_rate).toFixed(2)} estimated peaks/s
                                        </span>
                                    </div>
                                )}
                                {acousticFeatures.pause_count != null && (
                                    <div className="acoustic-item">
                                        <span className="acoustic-label">Pauses</span>
                                        <span className="acoustic-value">
                                            {acousticFeatures.pause_count}
                                        </span>
                                    </div>
                                )}
                                {acousticFeatures.total_pause_duration_seconds != null && (
                                    <div className="acoustic-item">
                                        <span className="acoustic-label">Total Pause</span>
                                        <span className="acoustic-value">
                                            {Number(acousticFeatures.total_pause_duration_seconds).toFixed(2)}s
                                        </span>
                                    </div>
                                )}
                                {acousticFeatures.pause_frequency_per_minute != null && (
                                    <div className="acoustic-item">
                                        <span className="acoustic-label">Pause Freq.</span>
                                        <span className="acoustic-value">
                                            {Number(acousticFeatures.pause_frequency_per_minute).toFixed(1)}/min
                                        </span>
                                    </div>
                                )}
                                {acousticFeatures.rms_energy != null && (
                                    <div className="acoustic-item">
                                        <span className="acoustic-label">Voice Energy (RMS)</span>
                                        <span className="acoustic-value">
                                            {Number(acousticFeatures.rms_energy).toFixed(4)}
                                        </span>
                                    </div>
                                )}
                                {acousticFeatures.intensity_variation_db != null && (
                                    <div className="acoustic-item">
                                        <span className="acoustic-label">Intensity Variation</span>
                                        <span className="acoustic-value">
                                            {Number(acousticFeatures.intensity_variation_db).toFixed(1)} dB
                                        </span>
                                    </div>
                                )}
                                {acousticFeatures.zero_crossing_rate != null && (
                                    <div className="acoustic-item">
                                        <span className="acoustic-label">Zero-Crossing Rate</span>
                                        <span className="acoustic-value">
                                            {Number(acousticFeatures.zero_crossing_rate).toFixed(4)}
                                        </span>
                                    </div>
                                )}
                            </div>

                            {processingTimings && (
                                <p className="processing-timing">
                                    Measured: total {(processingTimings.total / 1000).toFixed(1)}s ·
                                    Local AI {(processingTimings.responseGeneration / 1000).toFixed(1)}s ·
                                    transcription {(processingTimings.aiPipeline?.transcription / 1000).toFixed(1)}s ·
                                    voice analysis {(processingTimings.aiPipeline?.voice_analysis / 1000).toFixed(1)}s ·
                                    model load {(processingTimings.aiPipeline?.voice_analysis_stages?.model_load / 1000).toFixed(1)}s ·
                                    inference {(processingTimings.aiPipeline?.voice_analysis_stages?.inference / 1000).toFixed(1)}s
                                </p>
                            )}
                            <p className="acoustic-disclaimer">
                                ℹ️ Acoustic signals are non-diagnostic context only — not a clinical assessment.
                            </p>
                        </section>
                    )}

                    {/* AI response */}
                    {response && (
                        <section className="voice-response-card">
                            <div className="voice-card-header">
                                <div>
                                    <span className="voice-card-label">
                                        ADAPTIVESENSE
                                    </span>
                                    <h2>Response</h2>
                                </div>

                                {speaking && (
                                    <span className="speaking-indicator">
                                        <span />
                                        Speaking
                                    </span>
                                )}
                            </div>

                            <p className="response-text">{response}</p>

                            <div className="response-actions">
                                {speaking ? (
                                    <button
                                        type="button"
                                        className="voice-secondary-button"
                                        onClick={stopSpeaking}
                                    >
                                        Stop speaking
                                    </button>
                                ) : (
                                    <button
                                        type="button"
                                        className="voice-secondary-button"
                                        onClick={() => speakResponse(response)}
                                        disabled={isMuted}
                                    >
                                        Hear again
                                    </button>
                                )}

                                <label className="voice-mute-toggle">
                                    <input
                                        type="checkbox"
                                        checked={isMuted}
                                        onChange={(e) => {
                                            isMutedRef.current = e.target.checked;
                                            setIsMuted(e.target.checked);
                                            if (e.target.checked) stopSpeaking();
                                        }}
                                    />
                                    <span>Mute AI voice</span>
                                </label>

                                <button
                                    type="button"
                                    className="voice-primary-button"
                                    onClick={startNewVoiceConversation}
                                >
                                    New voice conversation
                                </button>
                            </div>
                        </section>
                    )}

                    {/* Error message */}
                    {error && (
                        <div className="voice-error" role="alert">
                            {error}
                        </div>
                    )}
                </section>
            </main>
        </div>
    );
}

export default VoiceConversation;
