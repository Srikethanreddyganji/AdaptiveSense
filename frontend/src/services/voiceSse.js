export class VoiceStreamError extends Error {
    constructor(message, code = "VOICE_STREAM_FAILURE") {
        super(message);
        this.name = "VoiceStreamError";
        this.code = code;
    }
}

export function voiceHttpErrorMessage(status, payload = {}) {
    const serverMessage = payload?.message || payload?.detail?.message;
    if (serverMessage) return serverMessage;

    switch (status) {
        case 401: return "Your session expired. Please sign in again.";
        case 403: return "You are not allowed to use this voice conversation.";
        case 413: return "This recording is too large. Please record a shorter message.";
        case 429: return "The response service is busy. Please wait a moment and try again.";
        case 500: return "AdaptiveSense could not process this request. Please try again.";
        case 502:
        case 503: return "The voice or response service is temporarily unavailable. Your voice analysis may still be available.";
        case 504: return "Voice processing took too long. Please try a shorter recording.";
        default: return `Voice request failed (HTTP ${status}). Please try again.`;
    }
}

function takeFrame(buffer) {
    const separator = /\r?\n\r?\n/;
    const match = separator.exec(buffer);
    if (!match) return null;
    return {
        frame: buffer.slice(0, match.index),
        rest: buffer.slice(match.index + match[0].length),
    };
}

function parseFrame(frame) {
    let event = "message";
    const data = [];
    for (const line of frame.split(/\r\n|\r|\n/)) {
        if (!line || line.startsWith(":")) continue;
        const colon = line.indexOf(":");
        const field = colon < 0 ? line : line.slice(0, colon);
        let value = colon < 0 ? "" : line.slice(colon + 1);
        if (value.startsWith(" ")) value = value.slice(1);
        if (field === "event") event = value;
        if (field === "data") data.push(value);
    }
    if (data.length === 0) return null;

    let payload;
    try {
        payload = JSON.parse(data.join("\n"));
    } catch {
        throw new VoiceStreamError("AdaptiveSense returned an unreadable voice response.", "INVALID_SSE_EVENT");
    }
    return { event, payload };
}

/** Consume the backend's event/data SSE framing and stop as soon as complete arrives. */
export async function consumeVoiceSse(response, onEvent) {
    if (!response?.body?.getReader) {
        throw new VoiceStreamError("The voice response did not include a readable event stream.", "MISSING_SSE_BODY");
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let completed = false;
    let terminalError = false;

    const dispatch = async (frame) => {
        const parsed = parseFrame(frame);
        if (!parsed) return false;
        if (parsed.event === "error") {
            terminalError = true;
            throw new VoiceStreamError(
                parsed.payload?.message || "Voice processing failed. Please try again.",
                parsed.payload?.code || "VOICE_PROCESSING_FAILURE",
            );
        }
        await onEvent(parsed.event, parsed.payload);
        if (parsed.event === "complete") {
            completed = true;
            return true;
        }
        return false;
    };

    try {
        while (!completed) {
            let chunk;
            try {
                chunk = await reader.read();
            } catch {
                throw new VoiceStreamError(
                    "The connection was interrupted before AdaptiveSense finished the response.",
                    "VOICE_CONNECTION_INTERRUPTED",
                );
            }

            buffer += decoder.decode(chunk.value || new Uint8Array(), { stream: !chunk.done });
            while (true) {
                const next = takeFrame(buffer);
                if (!next) break;
                buffer = next.rest;
                if (await dispatch(next.frame)) break;
            }

            if (completed) break;
            if (chunk.done) {
                if (buffer.trim()) {
                    await dispatch(buffer);
                    buffer = "";
                }
                if (!completed) {
                    throw new VoiceStreamError(
                        "The voice stream ended before a complete response arrived.",
                        "INCOMPLETE_VOICE_STREAM",
                    );
                }
            }
        }
    } finally {
        if (completed || terminalError) {
            try { await reader.cancel(); } catch { /* The stream may already be closed. */ }
        }
        reader.releaseLock();
    }

    return { completed };
}
