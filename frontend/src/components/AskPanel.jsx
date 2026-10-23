import { useState } from "react";
import { isExhausted, quotaMessage } from "../lib/format.js";
import ConversationTurn from "./ConversationTurn.jsx";

/**
 * "Ask the codebase" card, holding the conversation so far above the question box.
 * `onAsk(question)` returns null when the question can't be sent, otherwise a
 * promise of the stream's outcome; the box is cleared on send and refilled on error.
 */
export default function AskPanel({
  hasProject,
  busy,
  quota,
  turns,
  onAsk,
  onStop,
  onNewConversation,
  onNotice,
}) {
  const [question, setQuestion] = useState("");
  const exhausted = isExhausted(quota?.queries_used, quota?.queries_limit);
  const quotaCopy = quota && quotaMessage(quota.queries_used, quota.queries_limit, "question");
  const active = turns.length > 0;

  async function submit(event) {
    event.preventDefault();
    const asked = question;
    const pending = onAsk(asked);
    if (!pending) return;
    setQuestion("");
    const outcome = await pending;
    if (outcome?.error) setQuestion((current) => current || asked);
  }

  return (
    <form className={`tool-card${active ? " conversation-active" : ""}`} onSubmit={submit}>
      <div className="ask-head">
        <div>
          <span className="step-label">02 · Explore</span>
          <h2>Ask the codebase</h2>
        </div>
        {active && (
          <button
            type="button"
            className="text-button"
            onClick={onNewConversation}
            disabled={busy}
          >
            New conversation
          </button>
        )}
      </div>
      <p>Answers are restricted to the active project and link to the source files.</p>
      {active && (
        <ol className="conversation" aria-live="polite">
          {turns.map((turn) => (
            <ConversationTurn key={turn.id} turn={turn} onStop={onStop} onNotice={onNotice} />
          ))}
        </ol>
      )}
      <textarea
        value={question}
        onChange={(event) => setQuestion(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
            event.preventDefault();
            event.currentTarget.form.requestSubmit();
          }
        }}
        placeholder={active ? "Ask another question…" : "How does authentication work?"}
        aria-label="Question"
        rows={active ? 3 : 5}
        disabled={exhausted}
      />
      <button className="primary-button" disabled={!hasProject || busy || exhausted}>
        {busy ? "Answering…" : "Ask agent"}
      </button>
      {quotaCopy && <p className="quota-copy">{quotaCopy}</p>}
    </form>
  );
}
