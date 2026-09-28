import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import api from "../services/api";
import "./EmotionAnalysis.css";

function EmotionAnalysis() {

    const navigate = useNavigate();

    const [analysis, setAnalysis] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

    useEffect(() => {

        const loadLatestAnalysis = async () => {

            try {

                const response =
                    await api.get(
                        "/chat/emotion/latest"
                    );

                setAnalysis(response.data);
                setError("");

            } catch (error) {

                console.error(
                    "Emotion analysis error:",
                    error
                );

                if (error.response?.status === 404) {

                    setError(
                        "No emotional analysis is available yet."
                    );

                } else {

                    setError(
                        "Unable to load emotional analysis."
                    );
                }

            } finally {

                setLoading(false);
            }
        };

        loadLatestAnalysis();

    }, []);

    const getEmotionMessage = (emotion) => {

        switch (emotion) {

            case "Stress":
                return "Your recent conversation contains signals associated with stress.";

            case "Anxiety-like":
                return "Your recent conversation contains signals associated with worry or nervousness.";

            case "Sadness":
                return "Your recent conversation contains signals associated with sadness.";

            case "Anger":
                return "Your recent conversation contains signals associated with frustration or anger.";

            case "Fear":
                return "Your recent conversation contains signals associated with fear or concern.";

            case "Confusion":
                return "Your recent conversation contains signals associated with uncertainty or confusion.";

            case "Positive":
                return "Your recent conversation contains generally positive emotional signals.";

            case "Neutral":
                return "Your recent conversation contains mostly neutral emotional signals.";

            default:
                return "Your recent conversation has been analyzed for emotional signals.";
        }
    };

    const getEmotionClass = (emotion) => {

        if (!emotion) {
            return "neutral";
        }

        return emotion
            .toLowerCase()
            .replace("-like", "")
            .replace(/\s+/g, "-");
    };

    if (loading) {

        return (
            <div className="insights-page">

                <main className="insights-main">

                    <button
                        className="back-button"
                        onClick={() =>
                            navigate("/dashboard")
                        }
                    >
                        Back
                    </button>

                    <div className="insights-heading">
                        <h1>Emotional Insights</h1>

                        <p>
                            A look at the emotional signals
                            from your recent conversation.
                        </p>
                    </div>

                    <div className="insights-loading">

                        <div className="loading-spinner"></div>

                        <p>
                            Loading your latest insights...
                        </p>

                    </div>

                </main>

            </div>
        );
    }

    if (error) {

        return (
            <div className="insights-page">

                <main className="insights-main">

                    <button
                        className="back-button"
                        onClick={() =>
                            navigate("/dashboard")
                        }
                    >
                        Back
                    </button>

                    <div className="insights-heading">

                        <h1>
                            Emotional Insights
                        </h1>

                        <p>
                            A look at the emotional signals
                            from your recent conversation.
                        </p>

                    </div>

                    <div className="empty-insights">

                        <div className="empty-icon">
                            AS
                        </div>

                        <h2>
                            No insights yet
                        </h2>

                        <p>
                            Start a conversation with
                            AdaptiveSense to see your
                            emotional insights here.
                        </p>

                        <button
                            className="primary-button"
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

    const confidence =
        Number(analysis?.confidence || 0) * 100;

    const emotionClass =
        getEmotionClass(analysis?.emotion);

    return (
        <div className="insights-page">

            <main className="insights-main">

                {/* Header */}

                <button
                    className="back-button"
                    onClick={() =>
                        navigate("/dashboard")
                    }
                >
                    Back
                </button>

                <div className="insights-heading">

                    <h1>
                        Emotional Insights
                    </h1>

                    <p>
                        A look at the emotional signals
                        from your recent conversation.
                    </p>

                </div>

                {/* Main Analysis Card */}

                <section className="latest-card">

                    <div className="latest-card-top">

                        <div>

                            <span className="section-label">
                                LATEST ANALYSIS
                            </span>

                            <h2>
                                {analysis.emotion}
                            </h2>

                        </div>

                        <span
                            className={`emotion-badge ${emotionClass}`}
                        >
                            {analysis.emotion}
                        </span>

                    </div>

                    <p className="analysis-description">
                        {getEmotionMessage(
                            analysis.emotion
                        )}
                    </p>

                </section>

                {/* Metrics */}

                <section className="metrics-grid">

                    <div className="metric-card">

                        <span className="metric-label">
                            Emotion
                        </span>

                        <strong
                            className={`metric-value emotion-text ${emotionClass}`}
                        >
                            {analysis.emotion}
                        </strong>

                    </div>

                    <div className="metric-card">

                        <span className="metric-label">
                            Distress Level
                        </span>

                        <strong
                            className={`metric-value distress-${analysis.distressLevel?.toLowerCase()}`}
                        >
                            {analysis.distressLevel}
                        </strong>

                    </div>

                    <div className="metric-card">

                        <span className="metric-label">
                            Risk Level
                        </span>

                        <strong
                            className={`metric-value risk-${analysis.riskLevel?.toLowerCase()}`}
                        >
                            {analysis.riskLevel}
                        </strong>

                    </div>

                    <div className="metric-card">

                        <span className="metric-label">
                            Confidence
                        </span>

                        <strong className="metric-value">
                            {confidence.toFixed(1)}%
                        </strong>

                    </div>

                </section>

                {/* Confidence */}

                <section className="confidence-card">

                    <div className="confidence-header">

                        <span>
                            Analysis confidence
                        </span>

                        <strong>
                            {confidence.toFixed(1)}%
                        </strong>

                    </div>

                    <div className="confidence-track">

                        <div
                            className="confidence-fill"
                            style={{
                                width: `${Math.min(
                                    confidence,
                                    100
                                )}%`
                            }}
                        ></div>

                    </div>

                </section>

                {/* Information */}

                <section className="information-card">

                    <h3>
                        Understanding your insights
                    </h3>

                    <p>
                        These insights reflect emotional
                        signals detected in your recent
                        conversation. They are intended to
                        help you reflect on how you may be
                        feeling.
                    </p>

                    <p>
                        Emotional signals can change over
                        time and may not represent your
                        overall emotional state.
                    </p>

                    <div className="insight-note">

                        <strong>
                            Important
                        </strong>

                        <span>
                            This analysis is not a medical
                            diagnosis or a replacement for
                            professional support.
                        </span>

                    </div>

                </section>

                {/* Actions */}

                <div className="insights-actions">

                    <button
                        className="primary-button"
                        onClick={() =>
                            navigate("/chat")
                        }
                    >
                        Continue Conversation
                    </button>

                    <button
                        className="secondary-button"
                        onClick={() =>
                            navigate("/dashboard")
                        }
                    >
                        Back to Home
                    </button>

                </div>

            </main>

        </div>
    );
}

export default EmotionAnalysis;