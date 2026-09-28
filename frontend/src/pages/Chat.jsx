import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import api from "../services/api";
import { clearSession } from "../utils/session";
import "./Chat.css";

function Chat() {
    const navigate = useNavigate();

    const userName =
        localStorage.getItem("userName") || "User";

    const [messages, setMessages] = useState([
        {
            id: "welcome",
            role: "assistant",
            text:
                "Hello, I’m AdaptiveSense.\n\n" +
                "How are you feeling today?\n\n" +
                "I’m here to listen and support you. " +
                "Feel free to share what’s on your mind.",
            time: new Date()
        }
    ]);
    const [message, setMessage] = useState("");
    const [loading, setLoading] = useState(false);
    const [safetyNotice, setSafetyNotice] =
        useState(false);

    const [listening, setListening] = useState(false);
    const [speechSupported] = useState(() => {
        if (typeof window === "undefined") return false;
        return Boolean(
            window.SpeechRecognition ||
            window.webkitSpeechRecognition
        );
    });

    const messagesContainerRef = useRef(null);
    const recognitionRef = useRef(null);

    /*
     * Speech recognition
     */
    useEffect(() => {
        const SpeechRecognition =
            window.SpeechRecognition ||
            window.webkitSpeechRecognition;

        if (!SpeechRecognition) {
            return;
        }

        const recognition =
            new SpeechRecognition();

        recognition.continuous = false;
        recognition.interimResults = false;
        recognition.lang = "en-US";

        recognition.onstart = () => {
            setListening(true);
        };

        recognition.onend = () => {
            setListening(false);
        };

        recognition.onerror = (event) => {
            console.error(
                "Speech recognition error:",
                event.error
            );

            setListening(false);
        };

        recognition.onresult = (event) => {
            const transcript =
                event.results[0][0].transcript;

            setMessage((previous) => {
                if (!previous.trim()) {
                    return transcript;
                }

                return (
                    previous +
                    " " +
                    transcript
                );
            });
        };

        recognitionRef.current = recognition;

        return () => {
            try {
                recognition.stop();
            } catch {
                // Recognition was already stopped.
            }

            recognitionRef.current = null;
        };
    }, []);

    /*
     * Scroll only when the assistant response
     * has actually appeared.
     *
     * This prevents the page from jumping to the
     * bottom immediately when the user's message
     * is added.
     */
    useEffect(() => {
        if (messages.length === 0) {
            return;
        }

        const lastMessage =
            messages[messages.length - 1];

        if (lastMessage.role !== "assistant") {
            return;
        }

        requestAnimationFrame(() => {
            const container =
                messagesContainerRef.current;

            if (!container) {
                return;
            }

            container.scrollTo({
                top: container.scrollHeight,
                behavior: "smooth"
            });
        });
    }, [messages]);

    /*
     * While AdaptiveSense is generating a response,
     * keep the conversation positioned at the bottom.
     *
     * This allows the typing indicator to remain visible.
     */
    useEffect(() => {
        if (!loading) {
            return;
        }

        requestAnimationFrame(() => {
            const container =
                messagesContainerRef.current;

            if (!container) {
                return;
            }

            container.scrollTo({
                top: container.scrollHeight,
                behavior: "smooth"
            });
        });
    }, [loading]);

    /*
     * Microphone
     */
    const handleMicrophone = () => {
        if (!speechSupported) {
            alert(
                "Voice input is not supported in this browser."
            );

            return;
        }

        if (!recognitionRef.current) {
            return;
        }

        if (listening) {
            recognitionRef.current.stop();
            return;
        }

        try {
            recognitionRef.current.start();
        } catch (error) {
            console.error(
                "Microphone error:",
                error
            );
        }
    };

    /*
     * Send message
     */
    const handleSendMessage = async () => {
        const trimmedMessage =
            message.trim();

        if (!trimmedMessage || loading) {
            return;
        }

        if (
            listening &&
            recognitionRef.current
        ) {
            recognitionRef.current.stop();
        }

        const userMessage = {
            id: `user-${Date.now()}`,
            role: "user",
            text: trimmedMessage,
            time: new Date()
        };

        /*
         * Add the user's message immediately.
         */
        setMessages((previous) => [
            ...previous,
            userMessage
        ]);

        setMessage("");
        setLoading(true);

        try {
            const response =
                await api.post(
                    "/chat",
                    {
                        message: trimmedMessage
                    }
                );

            setSafetyNotice(
                Boolean(
                    response.data?.safetyEscalation
                )
            );

            const assistantText =
                response.data?.response ||
                "I'm here with you. Please try again.";

            const assistantMessage = {
                id: `assistant-${Date.now()}`,
                role: "assistant",
                text: assistantText,
                time: new Date()
            };

            /*
             * Add the actual assistant response.
             *
             * The scroll effect above will now move
             * the conversation to this response.
             */
            setMessages((previous) => [
                ...previous,
                assistantMessage
            ]);

        } catch (error) {
            console.error(
                "Chat error:",
                error
            );

            const errorMessage = {
                id: `error-${Date.now()}`,
                role: "assistant",
                text:
                    "I'm having trouble connecting right now. Please try again in a moment.",
                time: new Date()
            };

            setMessages((previous) => [
                ...previous,
                errorMessage
            ]);

        } finally {
            setLoading(false);
        }
    };

    /*
     * Enter sends the message.
     * Shift + Enter creates a new line.
     */
    const handleKeyDown = (event) => {
        if (
            event.key === "Enter" &&
            !event.shiftKey
        ) {
            event.preventDefault();

            handleSendMessage();
        }
    };

    /*
     * Start a new chat
     */
    const handleNewChat = () => {
        setMessages([
            {
                id:
                    "welcome-" +
                    Date.now(),
                role: "assistant",
                text:
                    "Hello, I’m AdaptiveSense.\n\n" +
                    "How are you feeling today?\n\n" +
                    "I’m here to listen and support you. " +
                    "Feel free to share what’s on your mind.",
                time: new Date()
            }
        ]);

        setMessage("");
        setLoading(false);
    };

    /*
     * Format message time
     */
    const formatTime = (time) => {
        return new Date(
            time
        ).toLocaleTimeString(
            [],
            {
                hour: "2-digit",
                minute: "2-digit"
            }
        );
    };

    /*
     * Logout
     */
    const handleLogout = () => {
        clearSession();
        navigate("/login");
    };

    /*
     * User initial
     */
    const userInitial =
        userName
            .charAt(0)
            .toUpperCase();

    return (
        <div className="chat-page">

            {/* =========================
                SIDEBAR
            ========================== */}

            <aside className="chat-sidebar">

                <div className="sidebar-brand">

                    <div className="brand-icon">
                        AS
                    </div>

                    <span>
                        AdaptiveSense
                    </span>

                </div>


                <button
                    className="new-chat-button"
                    onClick={handleNewChat}
                >

                    <span className="new-chat-plus">
                        +
                    </span>

                    <span>
                        New Chat
                    </span>

                </button>


                <nav className="sidebar-navigation">

                    <button
                        className="sidebar-item"
                        onClick={() =>
                            navigate("/dashboard")
                        }
                    >

                        <span className="sidebar-icon">

                            <svg
                                viewBox="0 0 24 24"
                                aria-hidden="true"
                            >

                                <path
                                    d="M4 10.5L12 4l8 6.5V20a1 1 0 0 1-1 1h-5v-6H10v6H5a1 1 0 0 1-1-1z"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                    strokeLinejoin="round"
                                />

                            </svg>

                        </span>

                        <span>
                            Home
                        </span>

                    </button>


                    <button
                        className="sidebar-item"
                        onClick={() =>
                            navigate("/history")
                        }
                    >

                        <span className="sidebar-icon">

                            <svg
                                viewBox="0 0 24 24"
                                aria-hidden="true"
                            >

                                <circle
                                    cx="12"
                                    cy="12"
                                    r="8"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                />

                                <path
                                    d="M12 7v5l3 2"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                    strokeLinecap="round"
                                />

                            </svg>

                        </span>

                        <span>
                            Conversation History
                        </span>

                    </button>


                    <button
                        className="sidebar-item"
                        onClick={() =>
                            navigate("/emotion")
                        }
                    >

                        <span className="sidebar-icon">

                            <svg
                                viewBox="0 0 24 24"
                                aria-hidden="true"
                            >

                                <path
                                    d="M5 19V10M12 19V5M19 19v-8"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                    strokeLinecap="round"
                                />

                            </svg>

                        </span>

                        <span>
                            Emotional Insights
                        </span>

                    </button>

                </nav>


                <div className="sidebar-spacer" />


                <div className="sidebar-profile">

                    <div className="profile-avatar">
                        {userInitial}
                    </div>

                    <div className="profile-details">

                        <strong>
                            {userName}
                        </strong>

                        <span>
                            AdaptiveSense User
                        </span>

                    </div>

                </div>


                <button
                    className="logout-button"
                    onClick={handleLogout}
                >
                    Logout
                </button>

            </aside>


            {/* =========================
                MAIN CHAT
            ========================== */}

            <main className="chat-main">

                <header className="chat-header">

                    <div className="chat-header-title">

                        <h1>
                            New Chat
                        </h1>

                        <span>
                            Your personal emotional support space
                        </span>

                    </div>


                    <div className="chat-header-right">

                        <div className="user-profile">

                            <div className="user-avatar">
                                {userInitial}
                            </div>

                            <span>
                                Welcome, {userName}
                            </span>

                        </div>

                    </div>

                </header>


                {/* =========================
                    MESSAGES
                ========================== */}

                <section
                    className="messages-area"
                    ref={messagesContainerRef}
                >

                    <div className="messages-container">

                        {messages.map(
                            (item) => (

                                <div
                                    key={item.id}
                                    className={
                                        item.role === "user"
                                            ? "message-row user-message-row"
                                            : "message-row assistant-message-row"
                                    }
                                >

                                    {item.role === "assistant" && (

                                        <div className="assistant-avatar">
                                            AS
                                        </div>

                                    )}


                                    <div
                                        className={
                                            item.role === "user"
                                                ? "message-wrapper user-wrapper"
                                                : "message-wrapper"
                                        }
                                    >

                                        <div
                                            className={
                                                item.role === "user"
                                                    ? "message-bubble user-bubble"
                                                    : "message-bubble assistant-bubble"
                                            }
                                        >
                                            {item.text}
                                        </div>


                                        <div
                                            className={
                                                item.role === "user"
                                                    ? "message-time user-time"
                                                    : "message-time"
                                            }
                                        >
                                            {formatTime(
                                                item.time
                                            )}
                                        </div>

                                    </div>

                                </div>
                            )
                        )}


                        {loading && (

                            <div className="message-row assistant-message-row">

                                <div className="assistant-avatar">
                                    AS
                                </div>

                                <div className="message-wrapper">

                                    <div className="message-bubble assistant-bubble typing-bubble">

                                        <span />
                                        <span />
                                        <span />

                                    </div>

                                </div>

                            </div>

                        )}

                    </div>

                </section>


                {/* =========================
                    MESSAGE COMPOSER
                ========================== */}

                <div className="composer-area">

                    <div className="composer">

                        <textarea
                            value={message}
                            onChange={(event) =>
                                setMessage(
                                    event.target.value
                                )
                            }
                            onKeyDown={
                                handleKeyDown
                            }
                            placeholder="Type a message..."
                            rows={1}
                            disabled={loading}
                        />


                        <button
                            type="button"
                            className={
                                listening
                                    ? "composer-button microphone-button listening"
                                    : "composer-button microphone-button"
                            }
                            onClick={
                                handleMicrophone
                            }
                            disabled={loading}
                            title={
                                listening
                                    ? "Stop listening"
                                    : "Speak"
                            }
                        >

                            <svg
                                viewBox="0 0 24 24"
                                aria-hidden="true"
                            >

                                <rect
                                    x="9"
                                    y="3"
                                    width="6"
                                    height="11"
                                    rx="3"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                />

                                <path
                                    d="M5.5 11a6.5 6.5 0 0 0 13 0"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                    strokeLinecap="round"
                                />

                                <path
                                    d="M12 17.5V21"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                    strokeLinecap="round"
                                />

                                <path
                                    d="M9 21h6"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                    strokeLinecap="round"
                                />

                            </svg>

                        </button>


                        <button
                            type="button"
                            className="send-button"
                            onClick={
                                handleSendMessage
                            }
                            disabled={
                                loading ||
                                !message.trim()
                            }
                            title="Send message"
                        >

                            <svg
                                viewBox="0 0 24 24"
                                aria-hidden="true"
                            >

                                <path
                                    d="M21 3L10 14"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                    strokeLinecap="round"
                                />

                                <path
                                    d="M21 3l-7 18-4-7-7-4z"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                    strokeLinejoin="round"
                                />

                            </svg>

                        </button>

                    </div>


                    {safetyNotice && (

                        <p className="composer-note safety-notice">
                            If you are in crisis, contact local emergency
                            services or a crisis helpline (e.g. 988 in the US).
                            AdaptiveSense is not a substitute for professional care.
                        </p>

                    )}

                    <p className="composer-note">
                        AdaptiveSense provides emotional support,
                        not medical diagnosis.
                    </p>

                </div>

            </main>

        </div>
    );
}

export default Chat;