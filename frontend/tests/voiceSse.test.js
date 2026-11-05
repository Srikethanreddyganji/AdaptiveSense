import test from "node:test";
import assert from "node:assert/strict";
import { consumeVoiceSse, VoiceStreamError } from "../src/services/voiceSse.js";

function responseFrom(chunks) {
    const encoder = new TextEncoder();
    return new Response(new ReadableStream({
        start(controller) {
            for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
            controller.close();
        },
    }), { headers: { "content-type": "text/event-stream" } });
}

test("valid complete event ends consumption without treating later stream closure as network failure", async () => {
    const events = [];
    const result = await consumeVoiceSse(responseFrom([
        ": keepalive\n\n",
        'event: complete\ndata: {"response":"Hello"}\n\n',
    ]), (event, payload) => events.push([event, payload]));
    assert.equal(result.completed, true);
    assert.deepEqual(events, [["complete", { response: "Hello" }]]);
});

test("server error event is surfaced as a categorized error", async () => {
    await assert.rejects(
        consumeVoiceSse(responseFrom([
            'event: error\ndata: {"code":"OLLAMA_UNAVAILABLE","message":"Try again later."}\n\n',
        ]), () => {}),
        (error) => error instanceof VoiceStreamError
            && error.code === "OLLAMA_UNAVAILABLE"
            && error.message === "Try again later.",
    );
});

test("stream termination before complete is reported as incomplete", async () => {
    await assert.rejects(
        consumeVoiceSse(responseFrom(['event: analysis\ndata: {"transcript":"hello"}\n\n']), () => {}),
        (error) => error.code === "INCOMPLETE_VOICE_STREAM",
    );
});
