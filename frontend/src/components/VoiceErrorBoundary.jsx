import React from "react";

class VoiceErrorBoundary extends React.Component {
    state = { hasError: false, resetKey: 0 };

    static getDerivedStateFromError() {
        return { hasError: true };
    }

    componentDidCatch(error, info) {
        console.error("Voice page render failed", error, info.componentStack);
    }

    retry = () => {
        this.setState((state) => ({
            hasError: false,
            resetKey: state.resetKey + 1,
        }));
    };

    render() {
        if (this.state.hasError) {
            return (
                <main className="voice-crash-fallback" role="alert">
                    <h1>Voice conversation needs a reset</h1>
                    <p>
                        An unexpected display error interrupted this voice session.
                        The current recording may not have been saved.
                    </p>
                    <button type="button" onClick={this.retry}>
                        Try the voice page again
                    </button>
                </main>
            );
        }

        return React.cloneElement(this.props.children, { key: this.state.resetKey });
    }
}

export default VoiceErrorBoundary;
