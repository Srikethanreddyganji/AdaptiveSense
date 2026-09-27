import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import api from "../services/api";
import "./EmotionGraph.css";

function EmotionGraph() {

    const navigate = useNavigate();

    const [history, setHistory] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

    useEffect(() => {

        const loadEmotionHistory = async () => {

            const userId =
                localStorage.getItem("userId");

            if (!userId) {

                setError(
                    "User session not found."
                );

                setLoading(false);

                return;
            }

            try {

                const response =
                    await api.get(
                        `/chat/emotion/history/${userId}`
                    );

                setHistory(
                    Array.isArray(response.data)
                        ? response.data
                        : []
                );

                setError("");

            } catch (error) {

                console.error(
                    "Emotion graph error:",
                    error
                );

                setError(
                    "Unable to load emotional trends."
                );

            } finally {

                setLoading(false);
            }
        };

        loadEmotionHistory();

    }, []);

    const recentHistory = useMemo(() => {

        return [...history]
            .sort(
                (a, b) =>
                    new Date(a.createdAt) -
                    new Date(b.createdAt)
            )
            .slice(-10);

    }, [history]);

    const emotionCounts = useMemo(() => {

        const counts = {};

        history.forEach((item) => {

            const emotion =
                item.emotion || "Unknown";

            counts[emotion] =
                (counts[emotion] || 0) + 1;
        });

        return Object.entries(counts)
            .sort((a, b) => b[1] - a[1]);

    }, [history]);

    const averageConfidence = useMemo(() => {

        if (history.length === 0) {
            return 0;
        }

        const total =
            history.reduce(
                (sum, item) =>
                    sum +
                    Number(item.confidence || 0),
                0
            );

        return (
            (total / history.length) *
            100
        );

    }, [history]);

    const getEmotionClass = (emotion) => {

        if (!emotion) {
            return "neutral";
        }

        return emotion
            .toLowerCase()
            .replace("-like", "")
            .replace(/\s+/g, "-");
    };

    const getEmotionColorClass = (emotion) => {

        switch (emotion) {

            case "Positive":
                return "positive";

            case "Neutral":
                return "neutral";

            case "Stress":
                return "stress";

            case "Anxiety-like":
                return "anxiety";

            case "Sadness":
                return "sadness";

            case "Anger":
                return "anger";

            case "Fear":
                return "fear";

            case "Confusion":
                return "confusion";

            default:
                return "neutral";
        }
    };

    const getDistressClass = (level) => {

        switch (level) {

            case "Low":
                return "low";

            case "Moderate":
                return "moderate";

            case "High":
                return "high";

            default:
                return "low";
        }
    };

    const getRiskClass = (level) => {

        switch (level) {

            case "Low":
                return "low";

            case "Moderate":
                return "moderate";

            case "Elevated":
                return "elevated";

            default:
                return "low";
        }
    };

    const getShortDate = (date) => {

        if (!date) {
            return "";
        }

        return new Date(date).toLocaleDateString(
            undefined,
            {
                month: "short",
                day: "numeric"
            }
        );
    };

    const getTime = (date) => {

        if (!date) {
            return "";
        }

        return new Date(date).toLocaleTimeString(
            undefined,
            {
                hour: "numeric",
                minute: "2-digit"
            }
        );
    };

    if (loading) {

        return (
            <div className="graph-page">

                <main className="graph-main">

                    <button
                        className="graph-back-button"
                        onClick={() =>
                            navigate("/dashboard")
                        }
                    >
                        Back
                    </button>

                    <div className="graph-heading">

                        <h1>
                            Emotional Trends
                        </h1>

                        <p>
                            A view of the emotional signals
                            detected across your recent conversations.
                        </p>

                    </div>

                    <div className="graph-loading">

                        <div className="graph-spinner"></div>

                        <p>
                            Loading your emotional trends...
                        </p>

                    </div>

                </main>

            </div>
        );
    }

    if (error) {

        return (
            <div className="graph-page">

                <main className="graph-main">

                    <button
                        className="graph-back-button"
                        onClick={() =>
                            navigate("/dashboard")
                        }
                    >
                        Back
                    </button>

                    <div className="graph-heading">

                        <h1>
                            Emotional Trends
                        </h1>

                        <p>
                            A view of the emotional signals
                            detected across your recent conversations.
                        </p>

                    </div>

                    <div className="graph-empty">

                        <div className="graph-empty-icon">
                            AS
                        </div>

                        <h2>
                            Unable to load trends
                        </h2>

                        <p>
                            We couldn't retrieve your
                            emotional history right now.
                        </p>

                        <button
                            className="graph-primary-button"
                            onClick={() =>
                                navigate("/chat")
                            }
                        >
                            Start a Conversation
                        </button>

                    </div>

                </main>

            </div>
        );
    }

    if (history.length === 0) {

        return (
            <div className="graph-page">

                <main className="graph-main">

                    <button
                        className="graph-back-button"
                        onClick={() =>
                            navigate("/dashboard")
                        }
                    >
                        Back
                    </button>

                    <div className="graph-heading">

                        <h1>
                            Emotional Trends
                        </h1>

                        <p>
                            A view of the emotional signals
                            detected across your recent conversations.
                        </p>

                    </div>

                    <div className="graph-empty">

                        <div className="graph-empty-icon">
                            AS
                        </div>

                        <h2>
                            No emotional history yet
                        </h2>

                        <p>
                            Have a conversation with
                            AdaptiveSense to start building
                            your emotional trends.
                        </p>

                        <button
                            className="graph-primary-button"
                            onClick={() =>
                                navigate("/chat")
                            }
                        >
                            Start a Conversation
                        </button>

                    </div>

                </main>

            </div>
        );
    }

    return (
        <div className="graph-page">

            <main className="graph-main">

                {/* Header */}

                <button
                    className="graph-back-button"
                    onClick={() =>
                        navigate("/dashboard")
                    }
                >
                    Back
                </button>

                <div className="graph-heading">

                    <h1>
                        Emotional Trends
                    </h1>

                    <p>
                        A view of the emotional signals
                        detected across your recent conversations.
                    </p>

                </div>

                {/* Summary */}

                <section className="graph-summary">

                    <div className="summary-card">

                        <span>
                            Analyses
                        </span>

                        <strong>
                            {history.length}
                        </strong>

                    </div>

                    <div className="summary-card">

                        <span>
                            Most common emotion
                        </span>

                        <strong>
                            {emotionCounts[0]?.[0] || "None"}
                        </strong>

                    </div>

                    <div className="summary-card">

                        <span>
                            Average confidence
                        </span>

                        <strong>
                            {averageConfidence.toFixed(1)}%
                        </strong>

                    </div>

                </section>

                {/* Emotional Timeline */}

                <section className="timeline-card">

                    <div className="section-header">

                        <div>
                            <h2>
                                Recent emotional signals
                            </h2>

                            <p>
                                Your latest conversation analyses
                            </p>
                        </div>

                        <span className="history-count">
                            {recentHistory.length} recent
                        </span>

                    </div>

                    <div className="timeline">

                        {recentHistory.map(
                            (analysis, index) => {

                                const emotionClass =
                                    getEmotionColorClass(
                                        analysis.emotion
                                    );

                                return (
                                    <div
                                        className="timeline-item"
                                        key={
                                            analysis.id ||
                                            index
                                        }
                                    >

                                        <div className="timeline-date">

                                            <strong>
                                                {getShortDate(
                                                    analysis.createdAt
                                                )}
                                            </strong>

                                            <span>
                                                {getTime(
                                                    analysis.createdAt
                                                )}
                                            </span>

                                        </div>

                                        <div className="timeline-line">

                                            <div
                                                className={`timeline-dot ${emotionClass}`}
                                            ></div>

                                            {index !==
                                                recentHistory.length - 1 && (
                                                <div className="timeline-connector"></div>
                                            )}

                                        </div>

                                        <div className="timeline-content">

                                            <div className="timeline-top">

                                                <span
                                                    className={`timeline-emotion ${emotionClass}`}
                                                >
                                                    {analysis.emotion}
                                                </span>

                                                <span className="timeline-confidence">
                                                    {(
                                                        Number(
                                                            analysis.confidence ||
                                                            0
                                                        ) * 100
                                                    ).toFixed(0)}
                                                    % confidence
                                                </span>

                                            </div>

                                            <div className="timeline-details">

                                                <div>
                                                    <span>
                                                        Distress
                                                    </span>

                                                    <strong
                                                        className={
                                                            getDistressClass(
                                                                analysis.distressLevel
                                                            )
                                                        }
                                                    >
                                                        {
                                                            analysis.distressLevel
                                                        }
                                                    </strong>
                                                </div>

                                                <div>
                                                    <span>
                                                        Risk
                                                    </span>

                                                    <strong
                                                        className={
                                                            getRiskClass(
                                                                analysis.riskLevel
                                                            )
                                                        }
                                                    >
                                                        {
                                                            analysis.riskLevel
                                                        }
                                                    </strong>
                                                </div>

                                            </div>

                                        </div>

                                    </div>
                                );
                            }
                        )}

                    </div>

                </section>

                {/* Emotion Distribution */}

                <section className="distribution-card">

                    <div className="section-header">

                        <div>
                            <h2>
                                Emotional distribution
                            </h2>

                            <p>
                                Emotions detected in your available history
                            </p>
                        </div>

                    </div>

                    <div className="distribution-list">

                        {emotionCounts.map(
                            ([emotion, count]) => {

                                const percentage =
                                    (
                                        count /
                                        history.length
                                    ) * 100;

                                const emotionClass =
                                    getEmotionClass(
                                        emotion
                                    );

                                return (
                                    <div
                                        className="distribution-row"
                                        key={emotion}
                                    >

                                        <div className="distribution-label">

                                            <span
                                                className={`distribution-dot ${emotionClass}`}
                                            ></span>

                                            <strong>
                                                {emotion}
                                            </strong>

                                            <span>
                                                {count}{" "}
                                                {count === 1
                                                    ? "analysis"
                                                    : "analyses"}
                                            </span>

                                        </div>

                                        <div className="distribution-bar">

                                            <div
                                                className={`distribution-fill ${emotionClass}`}
                                                style={{
                                                    width:
                                                        `${percentage}%`
                                                }}
                                            ></div>

                                        </div>

                                        <strong className="distribution-percentage">
                                            {percentage.toFixed(0)}%
                                        </strong>

                                    </div>
                                );
                            }
                        )}

                    </div>

                </section>

                {/* Actions */}

                <div className="graph-actions">

                    <button
                        className="graph-primary-button"
                        onClick={() =>
                            navigate("/chat")
                        }
                    >
                        Continue Conversation
                    </button>

                    <button
                        className="graph-secondary-button"
                        onClick={() =>
                            navigate("/emotion")
                        }
                    >
                        View Latest Analysis
                    </button>

                </div>

            </main>

        </div>
    );
}

export default EmotionGraph;