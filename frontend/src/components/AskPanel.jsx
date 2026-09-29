import { useState } from "react";
import { isExhausted, quotaMessage } from "../lib/format.js";

/** "Ask the codebase" card. `onAsk(question)` starts a streamed answer. */
export default function AskPanel({ hasProject, busy, quota, onAsk }) {
  const [question, setQuestion] = useState("");
  const exhausted = isExhausted(quota?.queries_used, quota?.queries_limit);
  const quotaCopy = quota && quotaMessage(quota.queries_used, quota.queries_limit, "question");

  return (
    <form
      className="tool-card"
      onSubmit={(event) => {
        event.preventDefault();
        onAsk(question);
      }}
    >
      <span className="step-label">02 · Explore</span>
      <h2>Ask the codebase</h2>
      <p>Answers are restricted to the active project and link to the source files.</p>
      <textarea
        value={question}
        onChange={(event) => setQuestion(event.target.value)}
        placeholder="How does authentication work?"
        rows="5"
        disabled={exhausted}
      />
      <button className="primary-button" disabled={!hasProject || busy || exhausted}>
        {busy ? "Answering…" : "Ask agent"}
      </button>
      {quotaCopy && <p className="quota-copy">{quotaCopy}</p>}
    </form>
  );
}
