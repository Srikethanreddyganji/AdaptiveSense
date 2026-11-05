import { clearSession } from "../utils/session.js";
import { chatRequestErrorMessage } from "./chatErrors.js";

export class ChatStreamError extends Error {
    constructor(message, code = "CHAT_STREAM_FAILURE", status) {
        super(message);
        this.name = "ChatStreamError";
        this.code = code;
        this.status = status;
    }
}

function parseEvent(frame) {
    let event = "message";
    const data = [];
    for (const line of frame.split(/\r\n|\r|\n/)) {
        if (!line || line.startsWith(":")) continue;
        const separator = line.indexOf(":");
        const field = separator < 0 ? line : line.slice(0, separator);
        let value = separator < 0 ? "" : line.slice(separator + 1);
        if (value.startsWith(" ")) value = value.slice(1);
        if (field === "event") event = value;
        if (field === "data") data.push(value);
    }
    if (!data.length) return null;
    try {
        return { event, payload: JSON.parse(data.join("\n")) };
    } catch {
        throw new ChatStreamError("AdaptiveSense returned an unreadable response.", "INVALID_SSE_EVENT");
    }
}

export async function postChatStream(message, { onDelta, signal } = {}) {
    const baseURL = (import.meta.env?.VITE_API_URL || "/api").replace(/\/+$/, "");
    const token = localStorage.getItem("token");
    let response;
    try {
        response = await fetch(`${baseURL}/chat/stream`, {
            method: "POST",
            headers: {
                Accept: "text/event-stream",
                "Content-Type": "application/json",
                "X-Request-ID": window.crypto?.randomUUID?.() || `chat-${Date.now()}`,
                ...(token ? { Authorization: `Bearer ${token}` } : {}),
            },
            body: JSON.stringify({ message }),
            signal,
        });
    } catch (error) {
        if (error?.name === "AbortError") throw error;
        throw new ChatStreamError(
            "Could not reach AdaptiveSense. Check your connection and try again.",
            "CHAT_NETWORK_ERROR",
        );
    }

    if (!response.ok) {
        let payload = {};
        try { payload = await response.json(); } catch { /* Use safe status-based text. */ }
        if (response.status === 401) {
            clearSession();
            window.location.assign("/login");
        }
        throw new ChatStreamError(
            payload?.message || payload?.detail?.message
                || chatRequestErrorMessage({ response: { status: response.status } }),
            payload?.code || `HTTP_${response.status}`,
            response.status,
        );
    }
    if (!response.headers.get("content-type")?.toLowerCase().includes("text/event-stream")
        || !response.body?.getReader) {
        throw new ChatStreamError("AdaptiveSense did not return a readable response stream.", "INVALID_STREAM");
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let completed = false;
    try {
        while (!completed) {
            let chunk;
            try {
                chunk = await reader.read();
            } catch (error) {
                if (error?.name === "AbortError" || signal?.aborted) throw error;
                throw new ChatStreamError(
                    "The connection ended before AdaptiveSense finished responding.",
                    "CHAT_CONNECTION_INTERRUPTED",
                );
            }
            buffer += decoder.decode(chunk.value || new Uint8Array(), { stream: !chunk.done });
            while (true) {
                const separator = /\r?\n\r?\n/.exec(buffer);
                if (!separator) break;
                const frame = buffer.slice(0, separator.index);
                buffer = buffer.slice(separator.index + separator[0].length);
                const parsed = parseEvent(frame);
                if (!parsed) continue;
                if (parsed.event === "error") {
                    throw new ChatStreamError(
                        parsed.payload?.message || "AdaptiveSense could not complete the response.",
                        parsed.payload?.code || "CHAT_PROCESSING_FAILURE",
                    );
                }
                if (parsed.event === "response") {
                    const delta = parsed.payload?.delta ?? parsed.payload?.response ?? "";
                    if (delta) onDelta?.(String(delta));
                }
                if (parsed.event === "complete") {
                    completed = true;
                    return parsed.payload;
                }
            }
            if (chunk.done && !completed) {
                throw new ChatStreamError(
                    "The response stream ended before completion.", "INCOMPLETE_CHAT_STREAM",
                );
            }
        }
    } finally {
        try { await reader.cancel(); } catch { /* Already closed. */ }
        reader.releaseLock();
    }
    throw new ChatStreamError("The response stream ended unexpectedly.", "INCOMPLETE_CHAT_STREAM");
}
