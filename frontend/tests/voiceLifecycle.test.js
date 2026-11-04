import test from "node:test";
import assert from "node:assert/strict";
import { createRecordingEpoch, createRecordingSession, createSubmissionGate, mergeRecognizedTranscript } from "../src/services/voiceLifecycle.js";

function setupSession(maxDurationMs = 60_000) {
    let currentTime = 0;
    let callback;
    let timerId = 0;
    const cleared = new Set();
    const recorder = {
        state: "inactive",
        start() { this.state = "recording"; },
        stop() { this.state = "inactive"; this.stopCalls = (this.stopCalls || 0) + 1; },
    };
    const session = createRecordingSession(recorder, {
        maxDurationMs,
        now: () => currentTime,
        setIntervalFn(fn) { callback = fn; return ++timerId; },
        clearIntervalFn(id) { cleared.add(id); },
    });
    return {
        recorder,
        session,
        advance(ms) { currentTime += ms; callback?.(); },
        cleared,
    };
}

for (const pauseMs of [1_000, 5_000, 10_000]) {
    test(`${pauseMs / 1000}-second silence does not stop MediaRecorder`, () => {
        const { recorder, session, advance } = setupSession();
        session.start();
        advance(pauseMs);
        assert.equal(session.recognitionEnded(), true);
        assert.equal(session.isRecording(), true);
        assert.equal(recorder.state, "recording");
        assert.equal(recorder.stopCalls || 0, 0);
    });
}

test("recording stops when the user requests Stop", () => {
    const { recorder, session } = setupSession();
    session.start();
    assert.equal(session.stop("user"), true);
    assert.equal(session.isRecording(), false);
    assert.equal(recorder.stopCalls, 1);
});

test("starting the same recording session twice does not create duplicate MediaRecorders", () => {
    const { recorder, session } = setupSession();
    assert.equal(session.start(), true);
    assert.equal(session.start(), false);
    assert.equal(recorder.state, "recording");
});

test("maximum recording duration stops MediaRecorder", () => {
    const { recorder, session, advance } = setupSession(60_000);
    session.start();
    advance(59_000);
    assert.equal(session.isRecording(), true);
    advance(1_000);
    assert.equal(session.isRecording(), false);
    assert.equal(recorder.stopCalls, 1);
});

test("duplicate submissions are rejected until the active request releases the gate", () => {
    const gate = createSubmissionGate();
    assert.equal(gate.acquire(), true);
    assert.equal(gate.acquire(), false);
    gate.release();
    assert.equal(gate.acquire(), true);
});

test("speech-recognition restarts merge overlapping words without duplicating transcript", () => {
    assert.equal(
        mergeRecognizedTranscript("I need a short break", "short break today"),
        "I need a short break today",
    );
});

test("late callbacks from a previous recording cannot change the next recording", () => {
    const epoch = createRecordingEpoch();
    const firstRecording = epoch.begin();
    const secondRecording = epoch.begin();

    assert.equal(epoch.isCurrent(firstRecording), false);
    assert.equal(epoch.isCurrent(secondRecording), true);

    epoch.invalidate();
    assert.equal(epoch.isCurrent(secondRecording), false);
});
