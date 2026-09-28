import { useNavigate } from "react-router-dom";
import { clearSession } from "../utils/session";
import "./Dashboard.css";

function Dashboard() {
    const navigate = useNavigate();

    const name =
        localStorage.getItem("userName") || "User";

    const email =
        localStorage.getItem("userEmail") || "";

    const getInitial = () => {
        return name
            ? name.charAt(0).toUpperCase()
            : "U";
    };

    const handleLogout = () => {
        clearSession();
        navigate("/login");
    };

    return (
        <div className="dashboard">

            {/* =========================
                SIDEBAR
            ========================== */}

            <aside className="dashboard-sidebar">

                {/* Brand */}

                <div className="sidebar-brand">
                    <div className="brand-icon">
                        AS
                    </div>

                    <div className="brand-name">
                        AdaptiveSense
                    </div>
                </div>


                {/* New Chat */}

                <button
                    className="new-chat-button"
                    onClick={() => navigate("/chat")}
                >
                    <span className="new-chat-plus">
                        +
                    </span>

                    <span>
                        New Chat
                    </span>
                </button>


                {/* Navigation */}

                <nav className="sidebar-navigation">

                    {/* Home */}

                    <button
                        className="sidebar-item active"
                        onClick={() => navigate("/dashboard")}
                    >
                        <span className="sidebar-icon">
                            <svg
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="1.8"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                            >
                                <path d="M3 10.5L12 3l9 7.5" />
                                <path d="M5.5 9.5V21h13V9.5" />
                                <path d="M9.5 21v-6h5v6" />
                            </svg>
                        </span>

                        <span>
                            Home
                        </span>
                    </button>


                    {/* Conversation History */}

                    <button
                        className="sidebar-item"
                        onClick={() => navigate("/history")}
                    >
                        <span className="sidebar-icon">
                            <svg
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="1.8"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                            >
                                <circle
                                    cx="12"
                                    cy="12"
                                    r="8.5"
                                />

                                <path d="M12 7v5l3 2" />
                            </svg>
                        </span>

                        <span>
                            Conversation History
                        </span>
                    </button>


                    {/* Emotional Insights */}

                    <button
                        className="sidebar-item"
                        onClick={() => navigate("/emotion")}
                    >
                        <span className="sidebar-icon">
                            <svg
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="1.8"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                            >
                                <path d="M5 19V10" />
                                <path d="M12 19V5" />
                                <path d="M19 19v-7" />
                            </svg>
                        </span>

                        <span>
                            Emotional Insights
                        </span>
                    </button>

                </nav>


                {/* Empty flexible area */}

                <div className="sidebar-spacer"></div>


                {/* User */}

                <div className="sidebar-user">

                    <div className="user-avatar">
                        {getInitial()}
                    </div>

                    <div className="user-details">

                        <div className="user-name">
                            {name}
                        </div>

                        <div className="user-email">
                            {email}
                        </div>

                    </div>

                </div>


                {/* Logout */}

                <button
                    className="logout-button"
                    onClick={handleLogout}
                >
                    <svg
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                    >
                        <path d="M10 17l5-5-5-5" />
                        <path d="M15 12H3" />
                        <path d="M21 3v18" />
                    </svg>

                    <span>
                        Logout
                    </span>
                </button>

            </aside>


            {/* =========================
                MAIN CONTENT
            ========================== */}

            <main className="dashboard-main">

                {/* Header */}

                <header className="dashboard-header">

                    <div>
                        <h1>
                            Home
                        </h1>

                        <p>
                            Your personal emotional
                            support space
                        </p>
                    </div>

                </header>


                {/* Main Home Area */}

                <section className="home-content">

                    <div className="welcome-section">

                        <div className="welcome-icon">
                            AS
                        </div>

                        <h2>
                            Hello, {name}
                        </h2>

                        <p className="welcome-question">
                            How are you feeling today?
                        </p>

                        <p className="welcome-description">
                            I'm here to listen and support
                            you. Feel free to share what's
                            on your mind.
                        </p>

                        <button
                            className="start-chat-button"
                            onClick={() => navigate("/chat")}
                        >
                            <span>
                                Start a conversation
                            </span>

                            <span className="start-arrow">
                                →
                            </span>
                        </button>

                    </div>


                    {/* Support Note */}

                    <div className="support-note">

                        <div className="support-note-icon">
                            i
                        </div>

                        <div>
                            <strong>
                                A space to talk
                            </strong>

                            <p>
                                AdaptiveSense is here to
                                listen without judgment.
                                Share whatever is on your
                                mind whenever you're ready.
                            </p>
                        </div>

                    </div>

                </section>

            </main>

        </div>
    );
}

export default Dashboard;