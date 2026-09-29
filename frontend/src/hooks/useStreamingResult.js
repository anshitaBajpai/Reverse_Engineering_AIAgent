import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError, streamJson } from "../apiClient.js";

/** How often buffered text is flushed into state while a response streams in. */
const FLUSH_INTERVAL_MS = 60;

/**
 * State for one streamed answer or document.
 *
 * `result` is null or `{ id, kind, title, text, sources, citations, steps, streaming, stopped }`.
 * `run()` starts a stream (cancelling any previous one) and resolves with
 * `{ ok }`, `{ stopped }` or `{ error }` — it never throws. Text deltas are
 * buffered and flushed every FLUSH_INTERVAL_MS so a long document doesn't
 * re-render its Markdown on every token.
 */
export function useStreamingResult() {
  const [result, setResult] = useState(null);
  const runIdRef = useRef(0);
  const abortRef = useRef(null);
  const pendingRef = useRef("");
  const timerRef = useRef(null);

  const flush = useCallback(() => {
    clearTimeout(timerRef.current);
    timerRef.current = null;
    const text = pendingRef.current;
    pendingRef.current = "";
    if (text) setResult((current) => current && { ...current, text: current.text + text });
  }, []);

  const cancelCurrent = useCallback(() => {
    runIdRef.current += 1;
    abortRef.current?.abort();
    abortRef.current = null;
    clearTimeout(timerRef.current);
    timerRef.current = null;
    pendingRef.current = "";
  }, []);

  useEffect(() => cancelCurrent, [cancelCurrent]);

  const run = useCallback(
    async ({ kind, title, path, body }) => {
      cancelCurrent();
      const runId = runIdRef.current;
      const isCurrent = () => runIdRef.current === runId;
      const controller = new AbortController();
      abortRef.current = controller;
      setResult({
        id: runId,
        kind,
        title,
        text: "",
        sources: [],
        citations: [],
        steps: [],
        streaming: true,
        stopped: false,
      });

      const onEvent = (name, data) => {
        if (!isCurrent()) return;
        if (name === "sources") {
          setResult((current) => current && {
            ...current,
            sources: data?.sources || [],
            citations: data?.citations || [],
          });
        } else if (name === "step") {
          setResult((current) => current && {
            ...current,
            steps: [...current.steps, data?.name],
          });
        } else if (name === "delta") {
          pendingRef.current += data?.text || "";
          if (!timerRef.current) timerRef.current = setTimeout(flush, FLUSH_INTERVAL_MS);
        }
      };

      try {
        const final = await streamJson(path, { body, onEvent, signal: controller.signal });
        if (!isCurrent()) return { superseded: true };
        clearTimeout(timerRef.current);
        timerRef.current = null;
        pendingRef.current = "";
        setResult((current) => current && {
          ...current,
          text: final?.answer ?? final?.document ?? current.text,
          sources: final?.sources ?? current.sources,
          citations: final?.citations ?? current.citations,
          streaming: false,
        });
        return { ok: true };
      } catch (error) {
        if (!isCurrent()) return { superseded: true };
        flush();
        if (isAbortError(error)) {
          setResult((current) => current && { ...current, streaming: false, stopped: true });
          return { stopped: true };
        }
        // Keep whatever text arrived; drop an empty result so the error notice stands alone.
        setResult((current) =>
          current && current.text ? { ...current, streaming: false, stopped: true } : null,
        );
        return { error };
      } finally {
        if (isCurrent()) abortRef.current = null;
      }
    },
    [cancelCurrent, flush],
  );

  const stop = useCallback(() => abortRef.current?.abort(), []);

  const clear = useCallback(() => {
    cancelCurrent();
    setResult(null);
  }, [cancelCurrent]);

  return { result, run, stop, clear };
}
