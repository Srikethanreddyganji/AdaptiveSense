import test from "node:test";
import assert from "node:assert/strict";
import { postChatStream, ChatStreamError } from "../src/services/chatSse.js";

function streamResponse(chunks) {
    const encoder = new TextEncoder();
    return new Response(new ReadableStream({
        start(controller) {
            for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
            controller.close();
        },
    }), { headers: { "content-type": "text/event-stream" } });
}

test("chat stream displays deltas and returns the completion event", async () => {
    const previousFetch = globalThis.fetch;
    const previousStorage = globalThis.localStorage;
    const previousWindow = globalThis.window;
    const deltas = [];
    let captured;
    globalThis.localStorage = { getItem: () => "test-jwt" };
    globalThis.window = { crypto: { randomUUID: () => "request-id" } };
    globalThis.fetch = async (url, options) => {
        captured = { url, options };
        return streamResponse([
            'event: response\ndata: {"delta":"I hear you. "}\n\n',
            'event: response\ndata: {"delta":"What feels hardest?"}\n\n',
            'event: complete\ndata: {"response":"I hear you. What feels hardest?","safetyEscalation":false}\n\n',
        ]);
    };
    try {
        const result = await postChatStream("I feel stressed", { onDelta: (delta) => deltas.push(delta) });
        assert.equal(captured.url, "/api/chat/stream");
        assert.equal(captured.options.headers.Authorization, "Bearer test-jwt");
        assert.deepEqual(JSON.parse(captured.options.body), { message: "I feel stressed" });
        assert.deepEqual(deltas, ["I hear you. ", "What feels hardest?"]);
        assert.equal(result.safetyEscalation, false);
    } finally {
        globalThis.fetch = previousFetch;
        globalThis.localStorage = previousStorage;
        globalThis.window = previousWindow;
    }
});

test("chat stream errors are not treated as successful completion", async () => {
    const previousFetch = globalThis.fetch;
    const previousStorage = globalThis.localStorage;
    const previousWindow = globalThis.window;
    globalThis.localStorage = { getItem: () => null };
    globalThis.window = { crypto: { randomUUID: () => "request-id" } };
    globalThis.fetch = async () => streamResponse([
        'event: error\ndata: {"code":"OLLAMA_UNAVAILABLE","message":"Try again later."}\n\n',
    ]);
    try {
        await assert.rejects(postChatStream("Hello"), (error) => error instanceof ChatStreamError
            && error.code === "OLLAMA_UNAVAILABLE");
    } finally {
        globalThis.fetch = previousFetch;
        globalThis.localStorage = previousStorage;
        globalThis.window = previousWindow;
    }
});
