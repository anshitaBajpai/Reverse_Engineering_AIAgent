import { useEffect, useRef } from "react";
import Markdown from "./Markdown.jsx";
import SourceList from "./SourceList.jsx";

/** One question and its answer, with the sources it was grounded in underneath. */
export default function ConversationTurn({ turn, onStop, onNotice }) {
  const { question, result } = turn;
  const ref = useRef(null);
  const waiting = result.streaming && !result.text;

  // Bring a new turn into view; earlier turns keep their key, so they don't re-scroll.
  useEffect(() => {
    const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    ref.current?.scrollIntoView({ block: "nearest", behavior: reduceMotion ? "auto" : "smooth" });
  }, []);

  async function copy() {
    try {
      await navigator.clipboard.writeText(result.text);
      onNotice("success", "Copied to clipboard.");
    } catch {
      onNotice("error", "Could not copy the answer. Select the text and copy it manually.");
    }
  }

  return (
    <li className="turn" ref={ref} aria-busy={result.streaming}>
      <p className="turn-question">{question}</p>
      <div className="turn-answer">
        <div className="turn-head">
          <span className="step-label">
            ANSWER
            {result.streaming && <span className="stream-status">Writing…</span>}
            {result.stopped && <span className="stream-status stopped">Stopped</span>}
          </span>
          <div className="result-actions">
            {result.streaming && (
              <button type="button" className="text-button" onClick={onStop}>
                Stop
              </button>
            )}
            {result.text && !result.streaming && (
              <button type="button" className="text-button" onClick={copy}>
                Copy
              </button>
            )}
          </div>
        </div>
        {waiting ? (
          <p className="progress-copy stream-waiting">
            {result.sources.length
              ? `Found ${result.sources.length} relevant code sections. Writing the answer…`
              : "Finding relevant code…"}
          </p>
        ) : result.text ? (
          <Markdown text={result.text} streaming={result.streaming} />
        ) : (
          <p className="turn-empty">Stopped before an answer was written.</p>
        )}
        <SourceList sources={result.sources} citations={result.citations} collapsible />
      </div>
    </li>
  );
}
