import test from "node:test";
import assert from "node:assert/strict";
import {
  formatFieldErrors,
  getErrorMessage,
  parseResponseBody,
  requestJson,
} from "./apiClient.js";

test("parseResponseBody parses valid JSON and ignores invalid JSON", () => {
  assert.deepEqual(parseResponseBody('{"ok":true}'), { ok: true });
  assert.equal(parseResponseBody("not json"), null);
  assert.equal(parseResponseBody(""), null);
});

test("getErrorMessage prefers field validation messages", () => {
  const message = getErrorMessage(
    { message: "Validation failed", fields: { repoUrl: "must be HTTPS" } },
    "",
    400,
  );
  assert.equal(message, "repoUrl: must be HTTPS");
});

test("formatFieldErrors skips blank messages", () => {
  assert.equal(
    formatFieldErrors({ question: "must not be blank", ignored: "" }),
    "question: must not be blank",
  );
});

test("requestJson returns parsed success body", async () => {
  const data = await requestJson("/health", {
    baseUrl: "http://test",
    fetchImpl: async (url, options) => {
      assert.equal(url, "http://test/health");
      assert.equal(options.headers["Content-Type"], "application/json");
      return new Response('{"status":"ok"}', { status: 200 });
    },
  });

  assert.deepEqual(data, { status: "ok" });
});

test("requestJson throws parsed API errors", async () => {
  await assert.rejects(
    requestJson("/ingest", {
      baseUrl: "http://test",
      fetchImpl: async () =>
        new Response(
          '{"message":"Validation failed","fields":{"repoUrl":"must be HTTPS"}}',
          { status: 400 },
        ),
    }),
    /repoUrl: must be HTTPS/,
  );
});
