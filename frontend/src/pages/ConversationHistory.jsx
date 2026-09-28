import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import api from "../services/api";
import { clearSession } from "../utils/session";
import "./ConversationHistory.css";

function ConversationHistory() {

    const navigate = useNavigate();

    const [conversations, setConversations] =
        useState([]);

    const [loading, setLoading] =
        useState(true);

    const [error, setError] =
        useState("");

    const userName =
        localStorage.getItem("userName") || "User";

    const userEmail =
        localStorage.getItem("userEmail") || "";

    const userInitial =
        userName.charAt(0).toUpperCase();


    /*
     * Load conversation history
     */
    const loadHistory = async () => {
        setLoading(true);
        setError("");

        try {
            const response =
                await api.get(
                    "/chat/history"
                );

            const history =
                Array.isArray(response.data)
                    ? response.data
                    : [];

            /*
             * Newest conversations first.
             */
            const sortedHistory =
                [...history].sort(
                    (a, b) =>
                        new Date(b.createdAt) -
                        new Date(a.createdAt)
                );

            setConversations(
                sortedHistory
            );

        } catch (err) {
            console.error(
                "Conversation history error:",
                err
            );

            setError(
                err.response?.data?.message ||
                "Unable to load your conversation history."
            );

        } finally {
            setLoading(false);
        }
    };


    /*
     * Load when page opens
     */
    useEffect(() => {
        let isMounted = true;

        api.get("/chat/history")
            .then((response) => {
                if (!isMounted) return;
                const history = Array.isArray(response.data) ? response.data : [];
                const sortedHistory = [...history].sort(
                    (a, b) => new Date(b.createdAt) - new Date(a.createdAt)
                );
                setConversations(sortedHistory);
            })
            .catch((err) => {
                if (!isMounted) return;
                console.error("Conversation history error:", err);
                setError(
                    err.response?.data?.message ||
                    "Unable to load your conversation history."
                );
            })
            .finally(() => {
                if (isMounted) {
                    setLoading(false);
                }
            });

        return () => {
            isMounted = false;
        };
    }, []);


    /*
     * Logout
     */
    const handleLogout = () => {
        clearSession();
        navigate("/login");
    };


    /*
     * Format date
     */
    const formatDate = (date) => {

        if (!date) {
            return "Unknown date";
        }

        const parsedDate =
            new Date(date);

        if (Number.isNaN(
            parsedDate.getTime()
        )) {
            return "Unknown date";
        }

        return parsedDate.toLocaleString(
            [],
            {
                dateStyle: "medium",
                timeStyle: "short"
            }
        );
    };


    return (

        <div className="history-page">

            {/* =========================
                SIDEBAR
            ========================== */}

            <aside className="history-sidebar">

                {/* Brand */}

                <div className="history-brand">

                    <div className="history-brand-icon">
                        AS
                    </div>

                    <span>
                        AdaptiveSense
                    </span>

                </div>


                {/* New Chat */}

                <button
                    className="history-new-chat"
                    onClick={() =>
                        navigate("/chat")
                    }
                >

                    <span className="history-new-chat-plus">
                        +
                    </span>

                    <span>
                        New Chat
                    </span>

                </button>


                {/* Navigation */}

                <nav className="history-navigation">

                    <button
                        className="history-nav-item"
                        onClick={() =>
                            navigate("/dashboard")
                        }
                    >

                        <span className="history-nav-icon">

                            <svg
                                viewBox="0 0 24 24"
                                fill="none"
                                aria-hidden="true"
                            >

                                <path
                                    d="M4 10.5L12 4l8 6.5V20a1 1 0 0 1-1 1h-5v-6H10v6H5a1 1 0 0 1-1-1z"
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
                        className="history-nav-item active"
                        onClick={() =>
                            navigate("/history")
                        }
                    >

                        <span className="history-nav-icon">

                            <svg
                                viewBox="0 0 24 24"
                                fill="none"
                                aria-hidden="true"
                            >

                                <circle
                                    cx="12"
                                    cy="12"
                                    r="8"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                />

                                <path
                                    d="M12 7v5l3 2"
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
                        className="history-nav-item"
                        onClick={() =>
                            navigate("/emotion")
                        }
                    >

                        <span className="history-nav-icon">

                            <svg
                                viewBox="0 0 24 24"
                                fill="none"
                                aria-hidden="true"
                            >

                                <path
                                    d="M5 19V10M12 19V5M19 19v-8"
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


                    <button
                        className="history-nav-item"
                        onClick={() =>
                            navigate("/emotion-graph")
                        }
                    >

                        <span className="history-nav-icon">

                            <svg
                                viewBox="0 0 24 24"
                                fill="none"
                                aria-hidden="true"
                            >

                                <path
                                    d="M4 17l5-5 4 3 7-8"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                />

                                <path
                                    d="M18 7h2v2"
                                    stroke="currentColor"
                                    strokeWidth="1.8"
                                    strokeLinecap="round"
                                />

                            </svg>

                        </span>

                        <span>
                            Emotional Trends
                        </span>

                    </button>

                </nav>


                <div className="history-sidebar-spacer" />


                {/* User */}

                <div className="history-user">

                    <div className="history-user-avatar">
                        {userInitial}
                    </div>

                    <div className="history-user-details">

                        <strong>
                            {userName}
                        </strong>

                        <span>
                            {userEmail}
                        </span>

                    </div>

                </div>


                {/* Logout */}

                <button
                    className="history-logout"
                    onClick={handleLogout}
                >

                    <svg
                        viewBox="0 0 24 24"
                        fill="none"
                        aria-hidden="true"
                    >

                        <path
                            d="M10 17l5-5-5-5"
                            stroke="currentColor"
                            strokeWidth="1.8"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                        />

                        <path
                            d="M15 12H3"
                            stroke="currentColor"
                            strokeWidth="1.8"
                            strokeLinecap="round"
                        />

                        <path
                            d="M21 3v18"
                            stroke="currentColor"
                            strokeWidth="1.8"
                            strokeLinecap="round"
                        />

                    </svg>

                    <span>
                        Logout
                    </span>

                </button>

            </aside>


            {/* =========================
                MAIN CONTENT
            ========================== */}

            <main className="history-main">

                {/* Header */}

                <header className="history-header">

                    <div>

                        <p className="history-eyebrow">
                            Your conversations
                        </p>

                        <h1>
                            Conversation History
                        </h1>

                        <p className="history-description">
                            Review your previous conversations
                            with AdaptiveSense.
                        </p>

                    </div>


                    <div className="history-header-actions">

                        <button
                            className="history-refresh-button"
                            onClick={loadHistory}
                            disabled={loading}
                        >
                            {loading
                                ? "Refreshing..."
                                : "Refresh"}
                        </button>

                        <button
                            className="history-chat-button"
                            onClick={() =>
                                navigate("/chat")
                            }
                        >
                            New Chat
                        </button>

                    </div>

                </header>


                {/* Content */}

                <section className="history-content">

                    {/* Loading */}

                    {loading && (

                        <div className="history-state">

                            <div className="history-spinner" />

                            <h2>
                                Loading your conversations
                            </h2>

                            <p>
                                Your previous conversations
                                will appear here.
                            </p>

                        </div>

                    )}


                    {/* Error */}

                    {!loading && error && (

                        <div className="history-state">

                            <div className="history-state-icon">
                                !
                            </div>

                            <h2>
                                Unable to load history
                            </h2>

                            <p>
                                {error}
                            </p>

                            <button
                                className="history-state-button"
                                onClick={loadHistory}
                            >
                                Try Again
                            </button>

                        </div>

                    )}


                    {/* Empty */}

                    {!loading &&
                        !error &&
                        conversations.length === 0 && (

                            <div className="history-state">

                                <div className="history-state-icon">
                                    +
                                </div>

                                <h2>
                                    No conversations yet
                                </h2>

                                <p>
                                    Start a conversation with
                                    AdaptiveSense and your
                                    conversations will appear here.
                                </p>

                                <button
                                    className="history-state-button"
                                    onClick={() =>
                                        navigate("/chat")
                                    }
                                >
                                    Start a Conversation
                                </button>

                            </div>
                        )}


                    {/* Conversations */}

                    {!loading &&
                        !error &&
                        conversations.length > 0 && (

                            <div className="conversation-list">

                                {conversations.map(
                                    (conversation, index) => (

                                        <article
                                            className="conversation-card"
                                            key={
                                                conversation.id ||
                                                `conversation-${index}`
                                            }
                                        >

                                            {/* Card header */}

                                            <div className="conversation-card-header">

                                                <div className="conversation-title">

                                                    <div className="conversation-number">
                                                        {conversations.length - index}
                                                    </div>

                                                    <div>

                                                        <span>
                                                            Conversation
                                                        </span>

                                                        <small>
                                                            {formatDate(
                                                                conversation.createdAt
                                                            )}
                                                        </small>

                                                    </div>

                                                </div>

                                            </div>


                                            {/* User message */}

                                            <div className="history-message user-history-message">

                                                <div className="history-message-header">

                                                    <div className="history-message-avatar user-history-avatar">
                                                        {userInitial}
                                                    </div>

                                                    <span>
                                                        You
                                                    </span>

                                                </div>

                                                <p>
                                                    {
                                                        conversation.userMessage
                                                    }
                                                </p>

                                            </div>


                                            {/* Assistant response */}

                                            <div className="history-message assistant-history-message">

                                                <div className="history-message-header">

                                                    <div className="history-message-avatar assistant-history-avatar">
                                                        AS
                                                    </div>

                                                    <span>
                                                        AdaptiveSense
                                                    </span>

                                                </div>

                                                <p>
                                                    {
                                                        conversation.assistantResponse
                                                    }
                                                </p>

                                            </div>

                                        </article>
                                    )
                                )}

                            </div>
                        )}

                </section>

            </main>

        </div>
    );
}

export default ConversationHistory;