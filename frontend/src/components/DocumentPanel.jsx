import { useState } from "react";
import { isExhausted, quotaMessage } from "../lib/format.js";

/** "Generate a technical document" card. `onGenerate(title)` starts a streamed document. */
export default function DocumentPanel({ hasProject, busy, quota, onGenerate }) {
  const [title, setTitle] = useState("");
  const exhausted = isExhausted(quota?.documents_used, quota?.documents_limit);
  const quotaCopy =
    quota && quotaMessage(quota.documents_used, quota.documents_limit, "technical document");

  return (
    <form
      className="tool-card"
      onSubmit={(event) => {
        event.preventDefault();
        onGenerate(title);
      }}
    >
      <span className="step-label">03 · Explain</span>
      <h2>Generate a technical document</h2>
      <p>
        Get architecture, behavior, risk, and synthesis in a shareable Markdown
        report.
      </p>
      <input
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        placeholder="Optional document title"
        disabled={exhausted}
      />
      <button className="secondary-button" disabled={!hasProject || busy || exhausted}>
        {busy ? "Writing document…" : "Generate document"}
      </button>
      {quotaCopy && <p className="quota-copy">{quotaCopy}</p>}
    </form>
  );
}
