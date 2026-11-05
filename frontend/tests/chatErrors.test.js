import test from "node:test";
import assert from "node:assert/strict";
import { chatRequestErrorMessage } from "../src/services/chatErrors.js";

test("chat request errors are user-safe and preserve the retryable message", () => {
    assert.match(chatRequestErrorMessage({ request: {} }), /connection/i);
    assert.match(chatRequestErrorMessage({ response: { status: 401 } }), /session expired/i);
    assert.match(chatRequestErrorMessage({ response: { status: 429 } }), /busy/i);
    assert.match(chatRequestErrorMessage({ response: { status: 503 } }), /temporarily unavailable/i);
});
