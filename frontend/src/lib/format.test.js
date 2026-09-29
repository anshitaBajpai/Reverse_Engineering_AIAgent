import test from "node:test";
import assert from "node:assert/strict";
import { isExhausted, quotaMessage } from "./format.js";

test("quotaMessage counts down and reports exhaustion", () => {
  assert.equal(quotaMessage(3, 20, "question"), "17 of 20 questions left today.");
  assert.equal(quotaMessage(0, 1, "technical document"), "1 of 1 technical document left today.");
  assert.equal(
    quotaMessage(3, 3, "technical document"),
    "You've used all your technical documents for today. The limit resets tomorrow.",
  );
});

test("unlimited quotas show nothing and never run out", () => {
  assert.equal(quotaMessage(50, 0, "question"), null);
  assert.equal(isExhausted(50, 0), false);
  assert.equal(isExhausted(2, 2), true);
  assert.equal(isExhausted(undefined, undefined), false);
});
