import { useState } from "react";

/** Repository URL form. `onIngest(url)` resolves true on success, which clears the field. */
export default function IngestPanel({ online, busy, stage, onIngest }) {
  const [repoUrl, setRepoUrl] = useState("");

  async function submit(event) {
    event.preventDefault();
    if (await onIngest(repoUrl)) setRepoUrl("");
  }

  return (
    <section className="ingest-panel">
      <div>
        <span className="step-label">01 · Add a repository</span>
        <h2>Start an analysis</h2>
        <p>
          Paste an HTTPS GitHub URL. Indexing runs in the background, so you can
          see progress without guessing.
        </p>
      </div>
      <form onSubmit={submit} className="ingest-form">
        <input
          aria-label="Repository URL"
          value={repoUrl}
          onChange={(event) => setRepoUrl(event.target.value)}
          placeholder="https://github.com/owner/repository"
          disabled={!online || busy}
        />
        <button className="primary-button" disabled={!online || busy}>
          {busy ? "Indexing…" : "Ingest repository"}
        </button>
      </form>
      {stage && (
        <p className="progress-copy" aria-live="polite">
          {stage}
        </p>
      )}
    </section>
  );
}
