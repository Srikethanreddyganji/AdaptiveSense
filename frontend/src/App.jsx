import {
    BrowserRouter,
    Routes,
    Route,
    Navigate
} from "react-router-dom";

import Login from "./pages/Login";
import Register from "./pages/Register";
import Dashboard from "./pages/Dashboard";
import Chat from "./pages/Chat";
import VoiceConversation from "./pages/VoiceConversation";
import EmotionAnalysis from "./pages/EmotionAnalysis";
import ConversationHistory from "./pages/ConversationHistory";
import EmotionGraph from "./pages/EmotionGraph";

import ProtectedRoute from "./components/ProtectedRoute";

function App() {

    return (
        <BrowserRouter>

            <Routes>

                {/* Default route */}

                <Route
                    path="/"
                    element={
                        <Navigate
                            to="/login"
                            replace
                        />
                    }
                />

                {/* Authentication */}

                <Route
                    path="/login"
                    element={<Login />}
                />

                <Route
                    path="/register"
                    element={<Register />}
                />

                {/* Main dashboard */}

                <Route
                    path="/dashboard"
                    element={
                        <ProtectedRoute>
                            <Dashboard />
                        </ProtectedRoute>
                    }
                />

                {/* Main chat */}

                <Route
                    path="/chat"
                    element={
                        <ProtectedRoute>
                            <Chat />
                        </ProtectedRoute>
                    }
                />

                {/* Temporary voice route
                    We will inspect this later and
                    move its functionality into Chat. */}

                <Route
                    path="/voice"
                    element={
                        <ProtectedRoute>
                            <VoiceConversation />
                        </ProtectedRoute>
                    }
                />

                {/* Latest emotional analysis */}

                <Route
                    path="/emotion"
                    element={
                        <ProtectedRoute>
                            <EmotionAnalysis />
                        </ProtectedRoute>
                    }
                />

                {/* Conversation history */}

                <Route
                    path="/history"
                    element={
                        <ProtectedRoute>
                            <ConversationHistory />
                        </ProtectedRoute>
                    }
                />

                {/* Emotional trends */}

                <Route
                    path="/emotion-graph"
                    element={
                        <ProtectedRoute>
                            <EmotionGraph />
                        </ProtectedRoute>
                    }
                />

                {/* Unknown routes */}

                <Route
                    path="*"
                    element={
                        <Navigate
                            to="/dashboard"
                            replace
                        />
                    }
                />

            </Routes>

        </BrowserRouter>
    );
}

export default App;