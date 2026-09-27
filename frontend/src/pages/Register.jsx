import { useState } from "react";
import { useNavigate } from "react-router-dom";
import api from "../services/api";
import "./Register.css";

function Register() {

    const navigate = useNavigate();

    const [name, setName] = useState("");
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");

    const [message, setMessage] = useState("");
    const [messageType, setMessageType] = useState("");

    const [showPassword, setShowPassword] = useState(false);
    const [loading, setLoading] = useState(false);

    const handleRegister = async (e) => {

        e.preventDefault();

        setMessage("");
        setMessageType("");
        setLoading(true);

        try {

            const response = await api.post(
                "/auth/register",
                {
                    name,
                    email,
                    password
                }
            );

            console.log(
                "Registration response:",
                response.data
            );

            setMessage(
                "Your account has been created successfully."
            );

            setMessageType("success");

            setName("");
            setEmail("");
            setPassword("");

        } catch (error) {

            console.error(
                "Registration error:",
                error
            );

            if (
                error.response?.data?.message
            ) {

                setMessage(
                    error.response.data.message
                );

            } else if (
                error.response?.data?.error
            ) {

                setMessage(
                    error.response.data.error
                );

            } else {

                setMessage(
                    "Registration failed. Please try again."
                );
            }

            setMessageType("error");

        } finally {

            setLoading(false);
        }
    };

    return (

        <div className="register-page">

            <div className="register-container">

                {/* Left section */}

                <div className="register-info">

                    <div className="register-brand">

                        <div className="register-brand-mark">
                            AS
                        </div>

                        <span>
                            AdaptiveSense
                        </span>

                    </div>


                    <div className="register-info-content">

                        <p className="register-label">
                            GET STARTED
                        </p>

                        <h1>
                            A space to
                            <br />
                            talk freely.
                        </h1>

                        <p className="register-description">
                            Create your personal AdaptiveSense
                            account and have a private place to
                            talk, reflect, and explore your recent
                            emotional signals.
                        </p>

                    </div>

                </div>


                {/* Register form */}

                <div className="register-card">

                    <div className="register-header">

                        <h2>
                            Create your account
                        </h2>

                        <p>
                            Start your personal AdaptiveSense
                            experience.
                        </p>

                    </div>


                    <form
                        onSubmit={handleRegister}
                        className="register-form"
                    >

                        <div className="register-form-group">

                            <label htmlFor="name">
                                Name
                            </label>

                            <input
                                id="name"
                                type="text"
                                placeholder="Enter your name"
                                value={name}
                                onChange={(e) =>
                                    setName(e.target.value)
                                }
                                autoComplete="name"
                                required
                            />

                        </div>


                        <div className="register-form-group">

                            <label htmlFor="register-email">
                                Email
                            </label>

                            <input
                                id="register-email"
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


                        <div className="register-form-group">

                            <label htmlFor="register-password">
                                Password
                            </label>

                            <div className="register-password-wrapper">

                                <input
                                    id="register-password"
                                    type={
                                        showPassword
                                            ? "text"
                                            : "password"
                                    }
                                    placeholder="Create a password"
                                    value={password}
                                    onChange={(e) =>
                                        setPassword(
                                            e.target.value
                                        )
                                    }
                                    autoComplete="new-password"
                                    required
                                />

                                <button
                                    type="button"
                                    className="register-password-toggle"
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

                            <div
                                className={
                                    `register-message ${messageType}`
                                }
                            >
                                {message}
                            </div>

                        )}


                        <button
                            type="submit"
                            className="register-submit"
                            disabled={loading}
                        >

                            {loading
                                ? "Creating account..."
                                : "Create account"}

                        </button>

                    </form>


                    <div className="register-login">

                        <span>
                            Already have an account?
                        </span>

                        <button
                            type="button"
                            onClick={() =>
                                navigate("/login")
                            }
                        >
                            Sign in
                        </button>

                    </div>


                    <p className="register-footer">
                        AdaptiveSense is designed as a personal
                        emotional support and reflection space.
                    </p>

                </div>

            </div>

        </div>
    );
}

export default Register;