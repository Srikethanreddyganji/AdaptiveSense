export function createRecordingSession(recorder, {
    maxDurationMs = 60_000,
    timesliceMs = 250,
    now = () => Date.now(),
    setIntervalFn = setInterval,
    clearIntervalFn = clearInterval,
    onDuration = () => {},
    onStopRequested = () => {},
} = {}) {
    let active = false;
    let startedAt = 0;
    let timer = null;

    const stop = (reason = "user") => {
        if (!active) return false;
        active = false;
        if (timer !== null) {
            clearIntervalFn(timer);
            timer = null;
        }
        onStopRequested(reason);
        if (recorder.state !== "inactive") recorder.stop();
        return true;
    };

    return {
        recorder,
        start() {
            if (active) return false;
            recorder.start(timesliceMs);
            active = true;
            startedAt = now();
            onDuration(0);
            timer = setIntervalFn(() => {
                if (!active) return;
                const elapsed = Math.min(maxDurationMs, Math.max(0, now() - startedAt));
                onDuration(elapsed);
                if (elapsed >= maxDurationMs) stop("maximum-duration");
            }, Math.min(250, maxDurationMs));
            return true;
        },
        stop,
        recognitionEnded() {
            // Speech recognition can end during silence; the recorder remains authoritative.
            return active;
        },
        isRecording() {
            return active;
        },
        dispose() {
            return stop("dispose");
        },
    };
}

export function createRecordingEpoch() {
    let currentEpoch = 0;
    return {
        begin() {
            currentEpoch += 1;
            return currentEpoch;
        },
        invalidate() {
            currentEpoch += 1;
            return currentEpoch;
        },
        isCurrent(epoch) {
            return epoch === currentEpoch;
        },
    };
}

export function createSubmissionGate() {
    let locked = false;
    return {
        acquire() {
            if (locked) return false;
            locked = true;
            return true;
        },
        release() {
            locked = false;
        },
    };
}

export function mergeRecognizedTranscript(existing, incoming) {
    const oldWords = existing.trim().split(/\s+/).filter(Boolean);
    const newWords = incoming.trim().split(/\s+/).filter(Boolean);
    if (!oldWords.length) return incoming.trim();
    if (!newWords.length) return existing;

    const normalize = (word) => word.toLocaleLowerCase().replace(/[^\p{L}\p{N}']/gu, "");
    const oldNormalized = oldWords.map(normalize);
    const newNormalized = newWords.map(normalize);
    const overlapLimit = Math.min(oldWords.length, newWords.length);
    let overlap = 0;
    for (let count = overlapLimit; count > 0; count -= 1) {
        if (oldNormalized.slice(-count).join(" ") === newNormalized.slice(0, count).join(" ")) {
            overlap = count;
            break;
        }
    }
    if (overlap === newWords.length) return existing;
    return `${existing.trim()} ${newWords.slice(overlap).join(" ")}`.trim();
}
