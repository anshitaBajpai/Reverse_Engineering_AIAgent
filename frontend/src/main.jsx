import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import "./styles.css";

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "http://127.0.0.1:8080";

async function requestJson(path, options = {}) {
  let response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      headers: {
        "Content-Type": "application/json",
        ...(options.headers || {}),
      },
      ...options,
    });
  } catch {
    throw new Error(
      `Cannot reach backend at ${API_BASE_URL}. Start the backend and try again.`,
    );
  }
  const text = await response.text();
  const data = parseResponseBody(text);
  if (!response.ok)
    throw new Error(getErrorMessage(data, text, response.status));
  return data;
}

function parseResponseBody(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function getErrorMessage(data, text, status) {
  if (data?.message) return data.message;
  if (data?.detail) return data.detail;
  if (data?.error) return data.error;
  if (data && typeof data === "object") {
    const messages = Object.values(data).filter(
      (value) => typeof value === "string" && value.trim(),
    );
    if (messages.length > 0) return messages.join(", ");
  }
  if (text?.trim())
    return `Server returned ${status}: ${text.trim().slice(0, 240)}`;
  return `Request failed with status ${status}`;
}

function normalizeProject(project) {
  return {
    project_id: project.project_id ?? project.projectId ?? "",
    repo_url: project.repo_url ?? project.repoUrl ?? "",
    ingested_at: project.ingested_at ?? project.ingestedAt ?? "",
    last_commit_sha: project.last_commit_sha ?? project.lastCommitSha ?? "",
    files_loaded: project.files_loaded ?? project.filesLoaded ?? 0,
    chunks_created: project.chunks_created ?? project.chunksCreated ?? 0,
  };
}

function repoNameFromUrl(url) {
  if (!url) return "Unknown repository";
  const parts = url
    .replace(/\.git$/, "")
    .split("/")
    .filter(Boolean);
  return parts.slice(-2).join("/") || url;
}

function shortSha(sha) {
  return sha ? sha.slice(0, 7) : "unknown";
}

const loaderLines = {
  ingest: [
    "Validating repository",
    "Cloning source",
    "Chunking files",
    "Creating embeddings",
  ],
  query: ["Embedding question", "Searching context", "Building answer"],
  document: ["Reading architecture", "Analyzing behavior", "Writing document"],
};

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

  const statusLabel = useMemo(() => {
    if (health === "ok") return "Backend online";
    if (health === "error") return "Backend offline";
    return "Checking backend";
  }, [health]);

  const scopeLabel = useMemo(() => {
    if (!projects.length) return "No projects";
    if (!selectedIds.length) return "All projects";
    return `${selectedIds.length} selected`;
  }, [projects.length, selectedIds.length]);

  useEffect(() => {
    requestJson("/health")
      .then(() => setHealth("ok"))
      .catch(() => setHealth("error"));
    refreshProjects();
  }, []);

  useEffect(() => {
    const valid = new Set(projects.map((project) => project.project_id));
    setSelectedIds((current) => current.filter((id) => valid.has(id)));
  }, [projects]);

  async function refreshProjects() {
    const list = await requestJson("/projects").catch(() => []);
    setProjects(Array.isArray(list) ? list.map(normalizeProject) : []);
  }

  async function handleIngest(event) {
    event.preventDefault();
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

  async function handleQuery(event) {
    event.preventDefault();
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

  async function handleDocument(event) {
    event.preventDefault();
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
    setSelectedIds((current) =>
      current.includes(id)
        ? current.filter((item) => item !== id)
        : [...current, id],
    );
  }

  function downloadDocument() {
    const blob = new Blob([documentText], { type: "text/markdown" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `${projectName || "reverse-engineering-document"}.md`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  const backendOffline = health === "error";

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">AI codebase analysis</p>
          <h1>Reverse Engineering AI Agent</h1>
          <p className="subtitle">
            Ingest a repository, ask questions, and generate a
            reverse-engineering document.
          </p>
        </div>
        <span className={`status ${health}`}>{statusLabel}</span>
      </header>

      {error && (
        <section className="alert" role="alert">
          {error}
        </section>
      )}

      <section className="layout">
        <aside className="sidebar">
          <form className="card" onSubmit={handleIngest}>
            <CardHeader title="Repository" meta="Step 1" />
            <label>
              GitHub repository URL
              <input
                value={repoUrl}
                onChange={(event) => setRepoUrl(event.target.value)}
                placeholder="https://github.com/owner/repo"
                spellCheck={false}
              />
            </label>
            <button
              className="primary"
              disabled={
                backendOffline || !repoUrl.trim() || activeAction === "ingest"
              }
            >
              {activeAction === "ingest" ? "Ingesting..." : "Ingest repository"}
            </button>
            {backendOffline && (
              <p className="muted">
                Backend is offline. Start the Spring Boot server on port 8080
                before ingesting a repository.
              </p>
            )}
            {ingestResult && (
              <div className="result">
                <span>
                  {ingestResult.files_loaded ?? ingestResult.filesLoaded} files
                </span>
                <span>
                  {ingestResult.chunks_created ?? ingestResult.chunksCreated}{" "}
                  chunks
                </span>
                <span>
                  {shortSha(ingestResult.commit_sha ?? ingestResult.commitSha)}
                </span>
              </div>
            )}
          </form>

          <section className="card">
            <CardHeader title="Project scope" meta={scopeLabel} />
            {!projects.length && (
              <p className="muted">
                Ingest a repository to create a project scope.
              </p>
            )}
            {projects.length > 0 && (
              <>
                <div className="scope-actions">
                  <button
                    type="button"
                    onClick={() =>
                      setSelectedIds(
                        projects.map((project) => project.project_id),
                      )
                    }
                  >
                    Select all
                  </button>
                  <button type="button" onClick={() => setSelectedIds([])}>
                    Clear
                  </button>
                </div>
                <div className="project-list">
                  {projects.map((project) => (
                    <label className="project-item" key={project.project_id}>
                      <input
                        type="checkbox"
                        checked={selectedIds.includes(project.project_id)}
                        onChange={() => toggleProject(project.project_id)}
                      />
                      <span>
                        <strong>{repoNameFromUrl(project.repo_url)}</strong>
                        <small>
                          {project.files_loaded} files ·{" "}
                          {project.chunks_created} chunks ·{" "}
                          {shortSha(project.last_commit_sha)}
                        </small>
                      </span>
                    </label>
                  ))}
                </div>
              </>
            )}
          </section>
        </aside>

        <section className="main-panel">
          <div className="actions-grid">
            <form className="card" onSubmit={handleQuery}>
              <CardHeader title="Ask" meta="RAG answer" />
              <label>
                Question
                <textarea
                  value={question}
                  onChange={(event) => setQuestion(event.target.value)}
                  placeholder="Explain the main architecture and request flow"
                  rows={4}
                  disabled={!hasData}
                />
              </label>
              <div className="inline-row">
                <label>
                  Chunks
                  <input
                    type="number"
                    min="1"
                    max="20"
                    value={queryK}
                    onChange={(event) => setQueryK(event.target.value)}
                    disabled={!hasData}
                  />
                </label>
                <button
                  className="primary"
                  disabled={
                    !hasData || !question.trim() || activeAction === "query"
                  }
                >
                  {activeAction === "query" ? "Thinking..." : "Ask"}
                </button>
              </div>
            </form>

            <form className="card" onSubmit={handleDocument}>
              <CardHeader title="Document" meta="Markdown report" />
              <label>
                Project name
                <input
                  value={projectName}
                  onChange={(event) => setProjectName(event.target.value)}
                  placeholder="My Project"
                  disabled={!hasData}
                />
              </label>
              <div className="inline-row">
                <label>
                  Chunks
                  <input
                    type="number"
                    min="5"
                    max="40"
                    value={documentK}
                    onChange={(event) => setDocumentK(event.target.value)}
                    disabled={!hasData}
                  />
                </label>
                <button
                  className="primary"
                  disabled={
                    !hasData ||
                    !projectName.trim() ||
                    activeAction === "document"
                  }
                >
                  {activeAction === "document" ? "Generating..." : "Generate"}
                </button>
              </div>
            </form>
          </div>

          <section className="output card">
            <div className="output-header">
              <CardHeader title="Output" meta={activeAction || "Ready"} />
              {documentText && (
                <button type="button" onClick={downloadDocument}>
                  Download .md
                </button>
              )}
            </div>

            {activeAction && <Loader action={activeAction} />}

            {!activeAction && !answer && !documentText && (
              <div className="empty">
                <strong>Ready for analysis</strong>
                <span>
                  Connect a repository, then ask a question or generate
                  documentation.
                </span>
              </div>
            )}

            {answer && <MarkdownOutput label="Answer" text={answer} />}
            {answer && querySources.length > 0 && (
              <SourceDetails label="Answer sources" sources={querySources} />
            )}

            {documentText && (
              <MarkdownOutput label="Document" text={documentText} />
            )}
            {chainSteps.length > 0 && <ChainSteps steps={chainSteps} />}
            {documentText && documentSources.length > 0 && (
              <SourceDetails
                label="Document sources"
                sources={documentSources}
              />
            )}
          </section>
        </section>
      </section>
    </main>
  );
}

function CardHeader({ title, meta }) {
  return (
    <div className="card-header">
      <h2>{title}</h2>
      <span>{meta}</span>
    </div>
  );
}

function Loader({ action }) {
  const lines = loaderLines[action] || ["Processing"];
  return (
    <div className="loader">
      {lines.map((line, index) => (
        <div
          key={line}
          className={index === 1 ? "active" : index < 1 ? "done" : ""}
        >
          <span>{index < 1 ? "✓" : index === 1 ? "›" : "·"}</span>
          {line}
        </div>
      ))}
    </div>
  );
}

function MarkdownOutput({ label, text }) {
  return (
    <article className="markdown-output">
      <span className="output-label">{label}</span>
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{text}</ReactMarkdown>
    </article>
  );
}

function ChainSteps({ steps }) {
  return (
    <details className="sources">
      <summary>Prompt chain artifacts ({steps.length})</summary>
      {steps.map((step) => (
        <article className="chain-step" key={step.name}>
          <h3>{step.name.replaceAll("_", " ")}</h3>
          <p>{step.description}</p>
          <pre>{step.content}</pre>
        </article>
      ))}
    </details>
  );
}

function SourceDetails({ label, sources }) {
  return (
    <details className="sources">
      <summary>
        {label} ({sources.length})
      </summary>
      {sources.map((source, index) => (
        <pre key={`${source.slice(0, 30)}-${index}`}>{source}</pre>
      ))}
    </details>
  );
}

createRoot(document.getElementById("root")).render(<App />);
