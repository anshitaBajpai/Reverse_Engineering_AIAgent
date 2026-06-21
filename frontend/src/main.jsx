import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import "./styles.css";

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "http://127.0.0.1:8080";

async function requestJson(path, options = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  const text = await response.text();
  const data = parseResponseBody(text);
  if (!response.ok) throw new Error(getErrorMessage(data, text, response.status));
  return data;
}

function parseResponseBody(text) {
  if (!text) return null;
  try { return JSON.parse(text); } catch { return null; }
}

function getErrorMessage(data, text, status) {
  if (data?.message) return data.message;
  if (data?.detail) return data.detail;
  if (data?.error) return data.error;
  if (data && typeof data === "object") {
    const msgs = Object.values(data).filter((v) => typeof v === "string" && v.trim());
    if (msgs.length > 0) return msgs.join(", ");
  }
  if (text?.trim()) return `Server returned ${status}: ${text.trim().slice(0, 240)}`;
  return `Request failed with status ${status}`;
}

function normalizeProject(p) {
  return {
    project_id: p.project_id ?? p.projectId ?? "",
    repo_url: p.repo_url ?? p.repoUrl ?? "",
    ingested_at: p.ingested_at ?? p.ingestedAt ?? "",
    last_commit_sha: p.last_commit_sha ?? p.lastCommitSha ?? "",
    files_loaded: p.files_loaded ?? p.filesLoaded ?? 0,
    chunks_created: p.chunks_created ?? p.chunksCreated ?? 0,
  };
}

function shortSha(sha) { return sha ? sha.slice(0, 7) : "unknown"; }

function repoNameFromUrl(url) {
  if (!url) return "Unknown repository";
  const parts = url.replace(/\.git$/, "").split("/").filter(Boolean);
  return parts.slice(-2).join("/") || url;
}

// ── Terminal loader ──────────────────────────────────────────────────────────

const LOADER_CONFIG = {
  ingest: {
    lines: [
      "Validating repository URL",
      "Cloning repository via JGit",
      "Walking file tree",
      "Chunking source files",
      "Generating embeddings",
      "Storing vectors in PGVector",
    ],
    interval: 7000,
  },
  query: {
    lines: [
      "Embedding question",
      "Similarity search",
      "Building context window",
      "Generating answer",
    ],
    interval: 3500,
  },
  document: {
    lines: [
      "Architecture extraction",
      "Behavior analysis  (parallel)",
      "Risk & security assessment  (parallel)",
      "Synthesizing final document",
    ],
    interval: 22000,
  },
};

function TerminalLoader({ action }) {
  const { lines, interval } = LOADER_CONFIG[action] ?? {
    lines: ["Processing..."],
    interval: 2000,
  };
  const [active, setActive] = useState(0);

  useEffect(() => { setActive(0); }, [action]);

  useEffect(() => {
    if (active >= lines.length - 1) return;
    const t = setTimeout(() => setActive((a) => a + 1), interval);
    return () => clearTimeout(t);
  }, [active, interval, lines.length]);

  return (
    <div className="terminal-loader">
      <div className="terminal-header">
        <span className="mac-dot red" />
        <span className="mac-dot yellow" />
        <span className="mac-dot green" />
        <span className="terminal-title">agent process</span>
      </div>
      <div className="terminal-body">
        {lines.map((line, i) => (
          <div
            key={line}
            className={`t-line ${
              i < active ? "t-done" : i === active ? "t-active" : "t-pending"
            }`}
          >
            <span className="t-icon">
              {i < active ? "✓" : i === active ? "›" : "·"}
            </span>
            <span>{line}</span>
            {i === active && <span className="t-cursor" />}
          </div>
        ))}
      </div>
      <div className="terminal-scanline" />
    </div>
  );
}

// ── Source details ───────────────────────────────────────────────────────────

function SourceDetails({ label, sources }) {
  return (
    <details className="sources">
      <summary>{label} ({sources.length})</summary>
      {sources.map((src, i) => (
        <pre key={`${src.slice(0, 30)}-${i}`}>{src}</pre>
      ))}
    </details>
  );
}

// ── App ──────────────────────────────────────────────────────────────────────

function App() {
  const [health, setHealth] = useState("checking");
  const [projects, setProjects] = useState([]);
  const [selectedIds, setSelectedIds] = useState([]);
  const [repoUrl, setRepoUrl] = useState("");
  const [projectName, setProjectName] = useState("");
  const [question, setQuestion] = useState("");
  const [queryK, setQueryK] = useState(5);
  const [documentK, setDocumentK] = useState(25);
  const [ingestResult, setIngestResult] = useState(null);
  const [answer, setAnswer] = useState("");
  const [documentText, setDocumentText] = useState("");
  const [chainSteps, setChainSteps] = useState([]);
  const [querySources, setQuerySources] = useState([]);
  const [documentSources, setDocumentSources] = useState([]);
  const [activeAction, setActiveAction] = useState("");
  const [error, setError] = useState("");

  const hasData = projects.length > 0 || !!ingestResult;
  const scopedProjects = selectedIds.length;

  const canIngest = repoUrl.trim().length > 0;
  const canAsk = hasData && question.trim().length > 0;
  const canDocument = hasData && projectName.trim().length > 0;

  const statusLabel = useMemo(() => {
    if (health === "ok") return "Backend online";
    if (health === "error") return "Backend offline";
    return "Checking…";
  }, [health]);

  const projectScopeLabel = useMemo(() => {
    if (!projects.length) return "No projects";
    if (!scopedProjects) return "All projects";
    return `${scopedProjects} selected`;
  }, [projects.length, scopedProjects]);

  useEffect(() => {
    requestJson("/health")
      .then(() => setHealth("ok"))
      .catch(() => setHealth("error"));
    refreshProjects();
  }, []);

  useEffect(() => {
    const valid = new Set(projects.map((p) => p.project_id));
    setSelectedIds((cur) => cur.filter((id) => valid.has(id)));
  }, [projects]);

  async function refreshProjects() {
    const list = await requestJson("/projects").catch(() => []);
    setProjects(Array.isArray(list) ? list.map(normalizeProject) : []);
  }

  async function handleIngest(e) {
    e.preventDefault();
    setError("");
    setActiveAction("ingest");
    setIngestResult(null);
    try {
      const result = await requestJson("/ingest", {
        method: "POST",
        body: JSON.stringify({ repo_url: repoUrl.trim() }),
      });
      setIngestResult(result);
      await refreshProjects();
    } catch (err) {
      setError(err.message);
    } finally {
      setActiveAction("");
    }
  }

  async function handleQuery(e) {
    e.preventDefault();
    setError("");
    setActiveAction("query");
    setAnswer("");
    setDocumentText("");
    setChainSteps([]);
    setQuerySources([]);
    setDocumentSources([]);
    try {
      const result = await requestJson("/query", {
        method: "POST",
        body: JSON.stringify({
          question: question.trim(),
          k: Number(queryK),
          project_ids: selectedIds,
        }),
      });
      setAnswer(result.answer);
      setQuerySources(result.sources || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setActiveAction("");
    }
  }

  async function handleDocument(e) {
    e.preventDefault();
    setError("");
    setActiveAction("document");
    setAnswer("");
    setDocumentText("");
    setChainSteps([]);
    setQuerySources([]);
    setDocumentSources([]);
    try {
      const result = await requestJson("/document", {
        method: "POST",
        body: JSON.stringify({
          project_name: projectName.trim(),
          k: Number(documentK),
          project_ids: selectedIds,
        }),
      });
      setDocumentText(result.document);
      setChainSteps(result.chain_steps || []);
      setDocumentSources(result.sources || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setActiveAction("");
    }
  }

  function toggleProject(id) {
    setSelectedIds((cur) =>
      cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]
    );
  }

  function downloadDocument() {
    const blob = new Blob([documentText], { type: "text/markdown" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${projectName || "reverse-engineering-document"}.md`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <main className="app-shell">
      {/* ── Header ── */}
      <header className="topbar">
        <div>
          <p className="eyebrow">// Codebase Intelligence</p>
          <h1>Reverse Engineering AI Agent</h1>
        </div>
        <span className={`status-pill ${health}`}>
          <span className="status-dot" />
          {statusLabel}
        </span>
      </header>

      {error && (
        <section className="alert" role="alert">
          <span className="alert-icon">⚠</span> {error}
        </section>
      )}

      <section className="workspace">
        {/* ── Left column ── */}
        <div className="left-column">
          {/* Ingest */}
          <form className="panel" onSubmit={handleIngest}>
            <div className="panel-heading">
              <h2>Ingest Repository</h2>
              <span className="step-badge">01</span>
            </div>
            <label>
              GitHub repository URL
              <input
                value={repoUrl}
                onChange={(e) => setRepoUrl(e.target.value)}
                placeholder="https://github.com/owner/repo"
                spellCheck={false}
              />
            </label>
            <button disabled={!canIngest || activeAction === "ingest"}>
              {activeAction === "ingest" ? (
                <><span className="btn-spinner" /> Ingesting…</>
              ) : (
                "Ingest"
              )}
            </button>
            {ingestResult && (
              <div className="result-strip">
                <span title="Project ID">⬡ {ingestResult.project_id}</span>
                <span title="HEAD commit">⌥ {shortSha(ingestResult.commit_sha ?? ingestResult.commitSha)}</span>
                <span>{ingestResult.files_loaded ?? ingestResult.filesLoaded} files</span>
                <span>{ingestResult.chunks_created ?? ingestResult.chunksCreated} chunks</span>
              </div>
            )}
          </form>

          {/* Project scope */}
          {projects.length > 0 && (
            <section className="panel">
              <div className="panel-heading">
                <h2>Project Scope</h2>
                <span className="step-badge">{projectScopeLabel}</span>
              </div>
              <p className="panel-notice">
                Selected projects scope Ask &amp; Document. Clear to search all.
              </p>
              <div className="project-actions">
                <button
                  type="button"
                  className="secondary-button"
                  onClick={() => setSelectedIds(projects.map((p) => p.project_id))}
                  disabled={selectedIds.length === projects.length}
                >
                  Select all
                </button>
                <button
                  type="button"
                  className="secondary-button"
                  onClick={() => setSelectedIds([])}
                  disabled={selectedIds.length === 0}
                >
                  Clear
                </button>
              </div>
              <div className="project-list">
                {projects.map((p, idx) => (
                  <label
                    className="project-option"
                    key={p.project_id}
                    style={{ animationDelay: `${idx * 60}ms` }}
                  >
                    <input
                      type="checkbox"
                      checked={selectedIds.includes(p.project_id)}
                      onChange={() => toggleProject(p.project_id)}
                    />
                    <span>
                      <strong>{repoNameFromUrl(p.repo_url)}</strong>
                      <small>{p.project_id}</small>
                      <small>
                        {p.files_loaded} files · {p.chunks_created} chunks · {shortSha(p.last_commit_sha)}
                      </small>
                    </span>
                  </label>
                ))}
              </div>
            </section>
          )}

          {/* Query */}
          <form className="panel" onSubmit={handleQuery}>
            <div className="panel-heading">
              <h2>Ask Questions</h2>
              <span className="step-badge">02</span>
            </div>
            {!hasData && <p className="panel-notice">Ingest a repository first.</p>}
            <label>
              Question
              <textarea
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                placeholder="Explain the main architecture and request flow"
                rows={5}
                disabled={!hasData}
              />
            </label>
            <label>
              Context chunks
              <input
                type="number"
                min="1"
                max="20"
                value={queryK}
                onChange={(e) => setQueryK(e.target.value)}
                disabled={!hasData}
              />
            </label>
            <button disabled={!canAsk || activeAction === "query"}>
              {activeAction === "query" ? (
                <><span className="btn-spinner" /> Thinking…</>
              ) : (
                "Ask"
              )}
            </button>
          </form>

          {/* Document */}
          <form className="panel" onSubmit={handleDocument}>
            <div className="panel-heading">
              <h2>Generate RE Document</h2>
              <span className="step-badge">03</span>
            </div>
            {!hasData && <p className="panel-notice">Ingest a repository first.</p>}
            <label>
              Project name
              <input
                value={projectName}
                onChange={(e) => setProjectName(e.target.value)}
                placeholder="My Project"
                disabled={!hasData}
              />
            </label>
            <label>
              Context chunks
              <input
                type="number"
                min="5"
                max="40"
                value={documentK}
                onChange={(e) => setDocumentK(e.target.value)}
                disabled={!hasData}
              />
            </label>
            <button disabled={!canDocument || activeAction === "document"}>
              {activeAction === "document" ? (
                <><span className="btn-spinner" /> Generating…</>
              ) : (
                "Generate Report"
              )}
            </button>
          </form>
        </div>

        {/* ── Output area ── */}
        <section className="output-area">
          <div className="output-header">
            <div>
              <p className="eyebrow">// Output</p>
              <h2>Analysis Output</h2>
            </div>
            {documentText && (
              <button className="secondary-button" onClick={downloadDocument}>
                ↓ Download .md
              </button>
            )}
          </div>

          <div className="output-body">
            {/* Loading */}
            {activeAction && <TerminalLoader action={activeAction} />}

            {/* Empty state */}
            {!activeAction && !answer && !documentText && (
              <div className="empty-state">
                <div className="empty-icon">⬡</div>
                <div>
                  <p>Ready for analysis</p>
                  <p className="empty-sub">
                    Ingest a repo, ask a question, or generate a reverse-engineering document.
                  </p>
                </div>
              </div>
            )}

            {/* Answer */}
            {answer && (
              <article className="markdown-output">
                <div className="output-section-header">
                  <span className="output-section-label">answer</span>
                </div>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{answer}</ReactMarkdown>
              </article>
            )}

            {answer && querySources.length > 0 && (
              <SourceDetails label="Answer sources" sources={querySources} />
            )}

            {/* Document */}
            {documentText && (
              <article className="markdown-output">
                <div className="output-section-header">
                  <span className="output-section-label">reverse engineering document</span>
                </div>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{documentText}</ReactMarkdown>
              </article>
            )}

            {chainSteps.length > 0 && (
              <details className="chain-steps">
                <summary>Prompt chain artifacts ({chainSteps.length})</summary>
                {chainSteps.map((step) => (
                  <article key={step.name}>
                    <h3>{step.name.replaceAll("_", " ")}</h3>
                    <p>{step.description}</p>
                    <pre>{step.content}</pre>
                  </article>
                ))}
              </details>
            )}

            {documentText && documentSources.length > 0 && (
              <SourceDetails label="Document sources" sources={documentSources} />
            )}
          </div>
        </section>
      </section>
    </main>
  );
}

createRoot(document.getElementById("root")).render(<App />);
