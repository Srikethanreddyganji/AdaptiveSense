import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import api from "../services/api";
import "./VoiceConversation.css";

function VoiceConversation() {
    const navigate = useNavigate();

    // Conversation state
    const [transcript, setTranscript] = useState("");
    const [interimTranscript, setInterimTranscript] = useState("");
    const [response, setResponse] = useState("");

    // Activity state
    const [listening, setListening] = useState(false);
    const [speaking, setSpeaking] = useState(false);
    const [loading, setLoading] = useState(false);
    const [recording, setRecording] = useState(false);
    const [starting, setStarting] = useState(false);

    // Consent and recorded-audio state
    // Consent is deliberately false whenever this component is mounted.
    const [recordingConsent, setRecordingConsent] = useState(false);
    const [audioBlob, setAudioBlob] = useState(null);
    const [audioUrl, setAudioUrl] = useState("");

    // Error state
    const [error, setError] = useState(() => {
        if (typeof window === "undefined") return "";

        const SpeechRecognition =
            window.SpeechRecognition ||
            window.webkitSpeechRecognition;

        return SpeechRecognition
            ? ""
            : "Speech recognition is not supported in this browser. Please use Google Chrome.";
    });

    // Refs
    const recognitionRef = useRef(null);
    const mediaRecorderRef = useRef(null);
    const mediaStreamRef = useRef(null);
    const audioChunksRef = useRef([]);
    const mountedRef = useRef(false);
    const startingRef = useRef(false);
    const discardRecordingRef = useRef(false);

    // Create and clean up the local audio preview URL.
    useEffect(() => {
        if (!audioBlob) {
            setAudioUrl("");
            return undefined;
        }

        const url = URL.createObjectURL(audioBlob);
        setAudioUrl(url);

        return () => {
            URL.revokeObjectURL(url);
        };
    }, [audioBlob]);

    // Stop microphone and speech synthesis when leaving this page.
    useEffect(() => {
        mountedRef.current = true;

        return () => {
            mountedRef.current = false;

            if (recognitionRef.current) {
                try {
                    recognitionRef.current.onend = null;
                    recognitionRef.current.onerror = null;
                    recognitionRef.current.stop();
                } catch {
                    // Recognition may already be stopped.
                }
            }

            const recorder = mediaRecorderRef.current;

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

        recognition.continuous = false;
        recognition.interimResults = true;
        recognition.lang = "en-IN";
        recognition.maxAlternatives = 1;

        recognition.onstart = () => {
            if (!mountedRef.current) return;

            setListening(true);
            setError("");
        };

        recognition.onresult = (event) => {
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
                setTranscript((previousText) =>
                    previousText
                        ? `${previousText} ${finalText}`
                        : finalText
                );
            }
        };

        recognition.onerror = (event) => {
            console.error("Speech recognition error:", event.error);

            if (mountedRef.current) {
                setListening(false);

                if (event.error === "not-allowed") {
                    setError(
                        "Microphone permission was denied. Allow microphone access in Chrome and try again."
                    );
                } else if (event.error === "no-speech") {
                    setError("I couldn't hear you. Please speak and try again.");
                } else if (event.error !== "aborted") {
                    setError("Speech recognition failed. Please try again.");
                }
            }

            stopAudioRecording();
        };

        recognition.onend = () => {
            if (!mountedRef.current) return;

            setListening(false);
            setInterimTranscript("");

            if (recognitionRef.current === recognition) {
                recognitionRef.current = null;
            }

            // Keep the audio recording synchronized with speech recognition.
            stopAudioRecording();
        };

        return recognition;
    };

    // Start the actual microphone recording.
    const startAudioRecording = async () => {
        if (
            !navigator.mediaDevices?.getUserMedia ||
            !window.MediaRecorder
        ) {
            throw new Error(
                "Audio recording is not supported in this browser. Please use Chrome."
            );
        }

        const stream = await navigator.mediaDevices.getUserMedia({
            audio: {
                echoCancellation: true,
                noiseSuppression: true,
                autoGainControl: true,
            },
        });

        mediaStreamRef.current = stream;
        audioChunksRef.current = [];
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
                audioChunksRef.current.push(event.data);
            }
        };

        recorder.onerror = (event) => {
            console.error("Audio recording error:", event.error);

            if (mountedRef.current) {
                setError("Audio recording failed. Please try again.");
            }

            stopAudioRecording();
        };

        recorder.onstop = () => {
            const actualMimeType = recorder.mimeType || "audio/webm";

            const blob = new Blob(audioChunksRef.current, {
                type: actualMimeType,
            });

            if (
                mountedRef.current &&
                !discardRecordingRef.current &&
                blob.size > 0
            ) {
                setAudioBlob(blob);
            }

            audioChunksRef.current = [];

            if (mediaRecorderRef.current === recorder) {
                mediaRecorderRef.current = null;
            }

            if (mountedRef.current) {
                setRecording(false);
            }

            if (mediaStreamRef.current === stream) {
                stream.getTracks().forEach((track) => track.stop());
                mediaStreamRef.current = null;
            }
        };

        recorder.start(250);
        setRecording(true);
    };

    // Stop the recorder and release microphone tracks.
    const stopAudioRecording = () => {
        const recorder = mediaRecorderRef.current;

        if (recorder && recorder.state !== "inactive") {
            try {
                recorder.stop();
            } catch (err) {
                console.warn("Could not stop recorder:", err);
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

        // Check browser support before requesting microphone access.
        const SpeechRecognition =
            window.SpeechRecognition ||
            window.webkitSpeechRecognition;

        if (!SpeechRecognition) {
            setError(
                "Speech recognition is not supported. Please use Google Chrome."
            );
            return;
        }

        startingRef.current = true;
        setStarting(true);
        setError("");

        window.speechSynthesis?.cancel();
        setSpeaking(false);

        setTranscript("");
        setInterimTranscript("");
        setResponse("");
        setAudioBlob(null);

        let recognition = null;

        try {
            // Microphone access is requested only after consent.
            await startAudioRecording();

            // The user may have navigated away while permission was pending.
            if (!mountedRef.current) {
                stopAudioRecording();
                return;
            }

            recognition = createRecognition();
            recognitionRef.current = recognition;
            recognition.start();
        } catch (err) {
            console.error("Could not start voice recording:", err);

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

    // Stop speech recognition and audio recording.
    const stopListening = () => {
        const recognition = recognitionRef.current;

        if (recognition) {
            try {
                recognition.stop();
            } catch {
                // Recognition may already be stopped.
            }
        }

        stopAudioRecording();
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

    // Send the transcript to the existing AdaptiveSense chat API.
    const sendToAdaptiveSense = async () => {
        if (!transcript.trim() || loading) {
            return;
        }

        stopListening();
        setLoading(true);
        setResponse("");
        setError("");

        try {
            const result = await api.post("/chat", {
                message: transcript.trim(),
            });

            const aiResponse = result.data.response;

            setResponse(aiResponse);
            speakResponse(aiResponse);
        } catch (err) {
            console.error("Voice conversation error:", err);

            setError(
                err.response?.data?.message ||
                "Sorry, something went wrong. Please try again."
            );
        } finally {
            setLoading(false);
        }
    };

    // Stop the spoken AI response.
    const stopSpeaking = () => {
        window.speechSynthesis.cancel();
        setSpeaking(false);
    };

    // Start a fresh voice conversation and discard the previous recording.
    const startNewVoiceConversation = () => {
        window.speechSynthesis.cancel();

        discardRecordingRef.current = true;

        if (recognitionRef.current) {
            try {
                recognitionRef.current.onend = null;
                recognitionRef.current.onerror = null;
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
        setAudioBlob(null);
        setError("");

        setListening(false);
        setSpeaking(false);
        setLoading(false);
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
                            this browser for the voice analysis feature. At
                            this stage, the recording is not uploaded to the
                            server.
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
                                onClick={stopListening}
                            >
                                Stop listening
                            </button>
                        )}
                    </section>

                    {/* Recording indicator */}
                    {recording && (
                        <p className="recording-status">
                            <span className="recording-dot" />
                            Recording your voice...
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
                            </p>
                        </section>
                    )}

                    {/* User transcript and send action */}
                    {!listening && transcript && !response && (
                        <section className="voice-message-card">
                            <span className="voice-card-label">
                                YOUR MESSAGE
                            </span>

                            <p>{transcript}</p>

                            <button
                                type="button"
                                className="voice-primary-button send-button"
                                onClick={sendToAdaptiveSense}
                                disabled={loading}
                            >
                                {loading
                                    ? "Processing..."
                                    : "Send to AdaptiveSense"}
                            </button>
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
                                    >
                                        Hear again
                                    </button>
                                )}

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
