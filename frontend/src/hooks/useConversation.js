import { useCallback, useState } from "react";
import { useStreamingResult } from "./useStreamingResult.js";

/**
 * A thread of questions and their streamed answers.
 *
 * `turns` is `[{ id, question, result }]`, oldest first; the last one is live
 * while it streams. `ask(question, request)` archives the latest turn and
 * streams the next answer, resolving like useStreamingResult's `run()`. A
 * question whose request fails before any text arrives drops out of the thread.
 */
export function useConversation() {
  const { result, run, stop, clear } = useStreamingResult();
  const [earlier, setEarlier] = useState([]);
  const [question, setQuestion] = useState("");

  const ask = useCallback(
    (nextQuestion, request) => {
      if (result) {
        // run() cancels a stream still in flight, so archive it as stopped.
        const settled = result.streaming ? { ...result, streaming: false, stopped: true } : result;
        setEarlier((turns) => [...turns, { id: result.id, question, result: settled }]);
      }
      setQuestion(nextQuestion);
      return run(request);
    },
    [result, question, run],
  );

  const reset = useCallback(() => {
    clear();
    setEarlier([]);
    setQuestion("");
  }, [clear]);

  const turns = result ? [...earlier, { id: result.id, question, result }] : earlier;
  return { turns, ask, stop, reset };
}
