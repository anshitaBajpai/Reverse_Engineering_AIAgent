import test from "node:test";
import assert from "node:assert/strict";
import { createSseParser, isAbortError, streamJson } from "./apiClient.js";

/** A fetch Response whose body arrives in the given text chunks. */
function streamResponse(chunks, init = { status: 200 }) {
  const encoder = new TextEncoder();
  const body = new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });
  return new Response(body, {
    headers: { "Content-Type": "text/event-stream" },
    ...init,
  });
}

function collect() {
  const events = [];
  return { events, parser: createSseParser((name, data) => events.push([name, data])) };
}

test("createSseParser handles events split across chunks", () => {
  const { events, parser } = collect();
  parser.push('event:delta\ndata:{"te');
  parser.push('xt":"Hel"}\n');
  parser.push('\nevent:delta\ndata: {"text":"lo"}\n\n');

  assert.deepEqual(events, [
    ["delta", { text: "Hel" }],
    ["delta", { text: "lo" }],
  ]);
});

test("createSseParser handles CRLF, comments, default names and plain text", () => {
  const { events, parser } = collect();
  parser.push(": keep-alive\r\n\r\ndata:plain text\r\n\r\n");
  parser.push("data:line one\ndata:line two\n\n");

  assert.deepEqual(events, [
    ["message", "plain text"],
    ["message", "line one\nline two"],
  ]);
});

test("streamJson forwards progress events and resolves with the done payload", async () => {
  const seen = [];
  const result = await streamJson("/query/stream", {
    baseUrl: "http://test",
    body: { question: "  why?  " },
    onEvent: (name, data) => seen.push([name, data]),
    fetchImpl: async (url, options) => {
      assert.equal(url, "http://test/query/stream");
      assert.equal(options.method, "POST");
      assert.equal(options.credentials, "include");
      assert.equal(options.headers.Accept, "text/event-stream");
      assert.equal(options.body, '{"question":"why?"}');
      return streamResponse([
        'event:sources\ndata:{"sources":["### File: a.py"],"citations":[]}\n\n',
        'event:delta\ndata:{"text":"Because"}\n\n',
        'event:done\ndata:{"answer":"Because","sources":[]}\n\n',
      ]);
    },
  });

  assert.deepEqual(result, { answer: "Because", sources: [] });
  assert.deepEqual(seen.map(([name]) => name), ["sources", "delta"]);
});

test("streamJson rejects with the error event's message", async () => {
  await assert.rejects(
    streamJson("/query/stream", {
      baseUrl: "http://test",
      fetchImpl: async () =>
        streamResponse(['event:delta\ndata:{"text":"x"}\n\n', 'event:error\ndata:{"message":"Query failed."}\n\n']),
    }),
    /Query failed\./,
  );
});

test("streamJson surfaces JSON errors returned before the stream starts", async () => {
  await assert.rejects(
    streamJson("/query/stream", {
      baseUrl: "http://test",
      fetchImpl: async () =>
        new Response('{"message":"You have used all 20 questions for today."}', { status: 429 }),
    }),
    /all 20 questions/,
  );
});

test("streamJson reports a stream that ends without done", async () => {
  await assert.rejects(
    streamJson("/query/stream", {
      baseUrl: "http://test",
      fetchImpl: async () => streamResponse(['event:delta\ndata:{"text":"x"}\n\n']),
    }),
    /ended before it finished/,
  );
});

test("streamJson rejects with an abort error when the caller stops it", async () => {
  const controller = new AbortController();
  const error = await streamJson("/query/stream", {
    baseUrl: "http://test",
    signal: controller.signal,
    onEvent: () => controller.abort(),
    fetchImpl: async (url, options) => {
      const body = new ReadableStream({
        start(stream) {
          stream.enqueue(new TextEncoder().encode('event:delta\ndata:{"text":"x"}\n\n'));
          options.signal.addEventListener("abort", () =>
            stream.error(new DOMException("aborted", "AbortError")),
          );
        },
      });
      return new Response(body, { status: 200 });
    },
  }).catch((err) => err);

  assert.ok(isAbortError(error), `expected abort error, got ${error}`);
});
