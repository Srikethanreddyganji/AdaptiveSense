import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import api from "../services/api";
import "./VoiceConversation.css";

function VoiceConversation() {
    const navigate = useNavigate();

    const [transcript, setTranscript] = useState("");
    const [interimTranscript, setInterimTranscript] = useState("");
    const [response, setResponse] = useState("");

    const [listening, setListening] = useState(false);
    const [speaking, setSpeaking] = useState(false);
    const [loading, setLoading] = useState(false);

    const [error, setError] = useState(() => {
        if (typeof window === "undefined") return "";
        const SpeechRecognition =
            window.SpeechRecognition ||
            window.webkitSpeechRecognition;
        return SpeechRecognition
            ? ""
            : "Speech recognition is not supported in this browser. Please use Google Chrome.";
    });

    const recognitionRef = useRef(null);

    const createRecognition = () => {
        const SpeechRecognition =
            window.SpeechRecognition ||
            window.webkitSpeechRecognition;

        if (!SpeechRecognition) {
            setError(
                "Speech recognition is not supported in this browser. Please use Google Chrome."
            );

            return null;
        }

        const recognition = new SpeechRecognition();

        recognition.continuous = false;
        recognition.interimResults = true;
        recognition.lang = "en-IN";
        recognition.maxAlternatives = 1;

        recognition.onstart = () => {
            setListening(true);
            setError("");
        };

        recognition.onresult = (event) => {
            let finalText = "";
            let temporaryText = "";

            for (
                let i = event.resultIndex;
                i < event.results.length;
                i++
            ) {
                const result = event.results[i];
                const text = result[0].transcript;

                if (result.isFinal) {
                    finalText += text;
                } else {
                    temporaryText += text;
                }
            }

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
            console.error(
                "Speech recognition error:",
                event.error
            );

            setListening(false);

            if (event.error === "not-allowed") {
                setError(
                    "Microphone permission was denied. Please allow microphone access in Chrome."
                );
            } else if (event.error === "no-speech") {
                setError(
                    "I couldn't hear you. Please speak again."
                );
            } else if (event.error === "aborted") {
                return;
            } else {
                setError(
                    "Speech recognition failed. Please try again."
                );
            }
        };

        recognition.onend = () => {
            setListening(false);
            setInterimTranscript("");
            recognitionRef.current = null;
        };

        return recognition;
    };

    useEffect(() => {
        return () => {
            if (recognitionRef.current) {
                try {
                    recognitionRef.current.stop();
                } catch {
                    // Recognition already stopped.
                }
            }

            window.speechSynthesis.cancel();
        };
    }, []);

    const startListening = () => {
        if (loading || listening) {
            return;
        }

        window.speechSynthesis.cancel();
        setSpeaking(false);

        setTranscript("");
        setInterimTranscript("");
        setResponse("");
        setError("");

        const recognition = createRecognition();

        if (!recognition) {
            return;
        }

        recognitionRef.current = recognition;

        try {
            recognition.start();
        } catch (error) {
            console.error(
                "Could not start recognition:",
                error
            );

            setListening(false);

            setError(
                "Could not start the microphone. Please try again."
            );

            recognitionRef.current = null;
        }
    };

    const stopListening = () => {
        const recognition = recognitionRef.current;

        if (!recognition) {
            return;
        }

        try {
            recognition.stop();
        } catch {
            console.log("Recognition already stopped.");
        }

        setListening(false);
        setInterimTranscript("");
    };

    const speakResponse = (text) => {
        if (!text) {
            return;
        }

        window.speechSynthesis.cancel();

        const speech =
            new SpeechSynthesisUtterance(text);

        speech.lang = "en-IN";
        speech.rate = 0.95;
        speech.pitch = 1;

        speech.onstart = () => {
            setSpeaking(true);
        };

        speech.onend = () => {
            setSpeaking(false);
        };

        speech.onerror = () => {
            setSpeaking(false);
        };

        window.speechSynthesis.speak(speech);
    };

    const sendToAdaptiveSense = async () => {
        if (!transcript.trim() || loading) {
            return;
        }

        stopListening();

        setLoading(true);
        setResponse("");
        setError("");

        try {
            const result = await api.post(
                "/chat",
                {
                    message: transcript.trim()
                }
            );

            const aiResponse =
                result.data.response;

            setResponse(aiResponse);

            speakResponse(aiResponse);

        } catch (error) {
            console.error(
                "Voice conversation error:",
                error
            );

            setError(
                "Sorry, something went wrong. Please try again."
            );

        } finally {
            setLoading(false);
        }
    };

    const stopSpeaking = () => {
        window.speechSynthesis.cancel();
        setSpeaking(false);
    };

    const startNewVoiceConversation = () => {
        window.speechSynthesis.cancel();

        if (recognitionRef.current) {
            try {
                recognitionRef.current.stop();
            } catch {
                console.log("Recognition already stopped.");
            }
        }

        setTranscript("");
        setInterimTranscript("");
        setResponse("");
        setError("");
        setListening(false);
        setSpeaking(false);
        setLoading(false);
    };

    return (
        <div className="voice-page">

            <main className="voice-main">

                {/* Header */}

                <header className="voice-header">

                    <button
                        className="voice-back-button"
                        onClick={() =>
                            navigate("/dashboard")
                        }
                    >
                        Back
                    </button>

                    <div className="voice-brand">
                        <div className="voice-brand-mark">
                            AS
                        </div>

                        <span>
                            AdaptiveSense
                        </span>
                    </div>

                </header>


                {/* Main Content */}

                <section className="voice-content">

                    <div className="voice-heading">

                        <p className="voice-eyebrow">
                            VOICE CONVERSATION
                        </p>

                        <h1>
                            Talk naturally
                        </h1>

                        <p>
                            Speak with AdaptiveSense using
                            your microphone.
                        </p>

                    </div>


                    {/* Voice Control */}

                    <section
                        className={`voice-control-card ${
                            listening
                                ? "is-listening"
                                : ""
                        }`}
                    >

                        <div
                            className={`microphone-circle ${
                                listening
                                    ? "microphone-active"
                                    : ""
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

                                <path
                                    d="M5 11a7 7 0 0 0 14 0"
                                />

                                <path
                                    d="M12 18v3"
                                />

                                <path
                                    d="M9 21h6"
                                />
                            </svg>

                        </div>


                        <div className="voice-control-text">

                            <h2>
                                {listening
                                    ? "Listening..."
                                    : loading
                                    ? "Processing your message"
                                    : speaking
                                    ? "AdaptiveSense is speaking"
                                    : "Ready to listen"}
                            </h2>

                            <p>
                                {listening
                                    ? "Speak naturally. Your words will appear below."
                                    : loading
                                    ? "Please wait while your response is prepared."
                                    : speaking
                                    ? "You can stop the response whenever you want."
                                    : "Press the microphone button when you're ready."}
                            </p>

                        </div>


                        {!listening && !loading && (
                            <button
                                className="voice-primary-button"
                                onClick={startListening}
                            >

                                <span className="button-mic-icon">

                                    <svg
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        strokeWidth="1.8"
                                    >
                                        <rect
                                            x="8"
                                            y="3"
                                            width="8"
                                            height="13"
                                            rx="4"
                                        />

                                        <path
                                            d="M5 11a7 7 0 0 0 14 0"
                                        />

                                        <path
                                            d="M12 18v3"
                                        />
                                    </svg>

                                </span>

                                Start speaking

                            </button>
                        )}


                        {listening && (
                            <button
                                className="voice-stop-button"
                                onClick={stopListening}
                            >
                                Stop listening
                            </button>
                        )}

                    </section>


                    {/* Live Transcript */}

                    {listening && (
                        <section className="voice-transcript-card">

                            <div className="voice-card-header">

                                <div>
                                    <span className="voice-card-label">
                                        LIVE TRANSCRIPT
                                    </span>

                                    <h2>
                                        Listening to you
                                    </h2>
                                </div>

                                <span className="listening-indicator">
                                    <span></span>
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

                                {!transcript &&
                                    !interimTranscript && (
                                        <p className="transcript-placeholder">
                                            Start speaking...
                                        </p>
                                    )}

                            </div>

                        </section>
                    )}


                    {/* User Transcript */}

                    {!listening && transcript && !response && (
                        <section className="voice-message-card">

                            <span className="voice-card-label">
                                YOUR MESSAGE
                            </span>

                            <p>
                                {transcript}
                            </p>

                            <button
                                className="voice-primary-button send-button"
                                onClick={
                                    sendToAdaptiveSense
                                }
                                disabled={loading}
                            >
                                {loading
                                    ? "Processing..."
                                    : "Send to AdaptiveSense"}
                            </button>

                        </section>
                    )}


                    {/* AI Response */}

                    {response && (
                        <section className="voice-response-card">

                            <div className="voice-card-header">

                                <div>
                                    <span className="voice-card-label">
                                        ADAPTIVESENSE
                                    </span>

                                    <h2>
                                        Response
                                    </h2>
                                </div>

                                {speaking && (
                                    <span className="speaking-indicator">
                                        <span></span>
                                        Speaking
                                    </span>
                                )}

                            </div>


                            <p className="response-text">
                                {response}
                            </p>


                            <div className="response-actions">

                                {speaking ? (
                                    <button
                                        className="voice-secondary-button"
                                        onClick={
                                            stopSpeaking
                                        }
                                    >
                                        Stop speaking
                                    </button>
                                ) : (
                                    <button
                                        className="voice-secondary-button"
                                        onClick={() =>
                                            speakResponse(
                                                response
                                            )
                                        }
                                    >
                                        Hear again
                                    </button>
                                )}

                                <button
                                    className="voice-primary-button"
                                    onClick={
                                        startNewVoiceConversation
                                    }
                                >
                                    New voice conversation
                                </button>

                            </div>

                        </section>
                    )}


                    {/* Error */}

                    {error && (
                        <div className="voice-error">
                            {error}
                        </div>
                    )}

                </section>

            </main>

        </div>
    );
}

export default VoiceConversation;