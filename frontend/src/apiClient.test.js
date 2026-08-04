import test from "node:test";
import assert from "node:assert/strict";
import {
  formatFieldErrors,
  getErrorMessage,
  ingestRepositoryAsync,
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

test("requestJson trims string values in JSON bodies before sending", async () => {
  await requestJson("/query", {
    baseUrl: "http://test",
    fetchImpl: async (url, options) => {
      assert.equal(url, "http://test/query");
      assert.equal(options.body, '{"question":"what is this?","k":5}');
      return new Response('{"answer":"ok"}', { status: 200 });
    },
    body: JSON.stringify({ question: "  what is this?  ", k: 5 }),
  });
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

test("ingestRepositoryAsync polls until the job succeeds", async () => {
  const updates = [];
  const calls = [];
  const result = await ingestRepositoryAsync("https://github.com/acme/app", {
    baseUrl: "http://test",
    pollIntervalMs: 1,
    sleep: async () => {},
    onJobUpdate: (job) => updates.push(job.status),
    fetchImpl: async (url, options) => {
      calls.push({ url, body: options.body });
      if (url.endsWith("/ingest/async")) {
        return new Response('{"jobId":"job-1","status":"PENDING"}', {
          status: 202,
        });
      }
      return new Response(
        '{"jobId":"job-1","status":"SUCCEEDED","result":{"files_loaded":3,"chunks_created":8}}',
        { status: 200 },
      );
    },
  });

  assert.deepEqual(result, { files_loaded: 3, chunks_created: 8 });
  assert.deepEqual(updates, ["PENDING", "SUCCEEDED"]);
  assert.equal(calls[0].url, "http://test/ingest/async");
  assert.equal(calls[0].body, '{"repo_url":"https://github.com/acme/app"}');
  assert.equal(calls[1].url, "http://test/jobs/job-1");
});

test("ingestRepositoryAsync throws failed job errors", async () => {
  await assert.rejects(
    ingestRepositoryAsync("https://github.com/acme/app", {
      baseUrl: "http://test",
      pollIntervalMs: 1,
      sleep: async () => {},
      fetchImpl: async (url) => {
        if (url.endsWith("/ingest/async")) {
          return new Response('{"jobId":"job-1","status":"RUNNING"}', {
            status: 202,
          });
        }
        return new Response(
          '{"jobId":"job-1","status":"FAILED","error":"Clone failed"}',
          { status: 200 },
        );
      },
    }),
    /Clone failed/,
  );
});

test("ingestRepositoryAsync times out while job is still active", async () => {
  await assert.rejects(
    ingestRepositoryAsync("https://github.com/acme/app", {
      baseUrl: "http://test",
      pollIntervalMs: 1,
      timeoutMs: 0,
      sleep: async () => {},
      fetchImpl: async () =>
        new Response('{"jobId":"job-1","status":"RUNNING"}', {
          status: 202,
        }),
    }),
    /still running/,
  );
});
