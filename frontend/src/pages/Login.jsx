import { useState } from "react";
import { useNavigate } from "react-router-dom";
import api from "../services/api";
import "./Login.css";

function Login() {

    const navigate = useNavigate();

    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");

    const [message, setMessage] = useState("");
    const [showPassword, setShowPassword] = useState(false);
    const [loading, setLoading] = useState(false);

    const handleLogin = async (e) => {

        e.preventDefault();

        setMessage("");
        setLoading(true);

        try {

            const response = await api.post(
                "/auth/login",
                {
                    email,
                    password
                }
            );

            const {
                token,
                userId,
                name,
                email: userEmail
            } = response.data;

            localStorage.setItem(
                "token",
                token
            );

            localStorage.setItem(
                "userId",
                userId
            );

            localStorage.setItem(
                "userName",
                name
            );

            localStorage.setItem(
                "userEmail",
                userEmail
            );

            navigate("/dashboard");

        } catch (error) {
            const status = error.response?.status;
            const serverMessage = error.response?.data?.message
                || error.response?.data?.error;

            if (status === 401) {
                setMessage(serverMessage || "Invalid email or password.");
            } else if (!error.response) {
                setMessage("Unable to reach AdaptiveSense. Check that the backend is running and try again.");
            } else {
                setMessage(serverMessage || "Sign-in failed. Please try again.");
            }

        } finally {

            setLoading(false);
        }
    };

    return (

        <div className="auth-page">

            <div className="auth-container">

                {/* Left information section */}

                <div className="auth-info">

                    <div className="auth-brand">

                        <div className="brand-mark">
                            AS
                        </div>

                        <span>
                            AdaptiveSense
                        </span>

                    </div>

                    <div className="auth-info-content">

                        <h1>
                            Your personal
                            <br />
                            emotional support space.
                        </h1>

                        <p>
                            A private space where you can
                            talk freely, reflect on how you're
                            feeling, and understand your
                            emotional patterns over time.
                        </p>

                        <div className="auth-feature">

                            <div className="feature-line"></div>

                            <span>
                                Conversations designed around
                                how you're feeling.
                            </span>

                        </div>

                    </div>

                </div>


                {/* Login section */}

                <div className="auth-card">

                    <div className="auth-card-header">

                        <h2>
                            Welcome back
                        </h2>

                        <p>
                            Sign in to continue to AdaptiveSense.
                        </p>

                    </div>


                    <form
                        onSubmit={handleLogin}
                        className="auth-form"
                    >

                        <div className="form-group">

                            <label htmlFor="email">
                                Email
                            </label>

                            <input
                                id="email"
                                type="email"
                                placeholder="Enter your email"
                                value={email}
                                onChange={(e) =>
                                    setEmail(e.target.value)
                                }
                                autoComplete="email"
                                required
                            />

                        </div>


                        <div className="form-group">

                            <label htmlFor="password">
                                Password
                            </label>

                            <div className="password-wrapper">

                                <input
                                    id="password"
                                    type={
                                        showPassword
                                            ? "text"
                                            : "password"
                                    }
                                    placeholder="Enter your password"
                                    value={password}
                                    onChange={(e) =>
                                        setPassword(
                                            e.target.value
                                        )
                                    }
                                    autoComplete="current-password"
                                    required
                                />

                                <button
                                    type="button"
                                    className="password-toggle"
                                    onClick={() =>
                                        setShowPassword(
                                            !showPassword
                                        )
                                    }
                                >
                                    {showPassword
                                        ? "Hide"
                                        : "Show"}
                                </button>

                            </div>

                        </div>


                        {message && (

                            <div className="auth-message error">
                                {message}
                            </div>

                        )}


                        <button
                            type="submit"
                            className="auth-submit"
                            disabled={loading}
                        >

                            {loading
                                ? "Signing in..."
                                : "Sign in"}

                        </button>

                    </form>


                    <div className="auth-divider">
                        <span></span>
                        <p>New to AdaptiveSense?</p>
                        <span></span>
                    </div>


                    <button
                        type="button"
                        className="secondary-auth-button"
                        onClick={() =>
                            navigate("/register")
                        }
                    >
                        Create an account
                    </button>


                    <p className="auth-footer-text">
                        Your conversations are intended
                        for personal reflection and support.
                    </p>

                </div>

            </div>

        </div>
    );
}

export default Login;
