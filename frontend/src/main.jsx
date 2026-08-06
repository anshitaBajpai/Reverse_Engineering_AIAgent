import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ingestRepositoryAsync, requestJson } from "./apiClient";
import "./styles.css";

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
  const [ingestJob, setIngestJob] = useState(null);
  const [answer, setAnswer] = useState("");
  const [documentText, setDocumentText] = useState("");
  const [chainSteps, setChainSteps] = useState([]);
  const [querySources, setQuerySources] = useState([]);
  const [documentSources, setDocumentSources] = useState([]);
  const [activeAction, setActiveAction] = useState("");
  const [error, setError] = useState("");
  const [deletingId, setDeletingId] = useState("");
  const [projectStatuses, setProjectStatuses] = useState({});
  const [projectActions, setProjectActions] = useState({});
  const [activeOutputTab, setActiveOutputTab] = useState("answer");

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

  useEffect(() => {
    if (answer) {
      setActiveOutputTab("answer");
      return;
    }
    if (documentText) {
      setActiveOutputTab("document");
      return;
    }
    setActiveOutputTab("answer");
  }, [answer, documentText]);

  async function refreshProjects() {
    const list = await requestJson("/projects").catch(() => []);
    setProjects(Array.isArray(list) ? list.map(normalizeProject) : []);
  }

  async function handleIngest(event) {
    event.preventDefault();
    const trimmedRepoUrl = repoUrl.trim();
    setRepoUrl(trimmedRepoUrl);
    setError("");
    setActiveAction("ingest");
    setIngestResult(null);
    setIngestJob(null);
    try {
      const result = await ingestRepositoryAsync(trimmedRepoUrl, {
        onJobUpdate: setIngestJob,
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
    const trimmedQuestion = question.trim();
    setQuestion(trimmedQuestion);
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
          question: trimmedQuestion,
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
    const trimmedProjectName = projectName.trim();
    setProjectName(trimmedProjectName);
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
          project_name: trimmedProjectName,
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

  async function handleDeleteProject(projectId, repoUrl) {
    const label = repoNameFromUrl(repoUrl);
    if (
      !window.confirm(
        `Remove project "${label}"? This deletes its ingested data.`,
      )
    ) {
      return;
    }
    setError("");
    setDeletingId(projectId);
    try {
      await requestJson(`/projects/${encodeURIComponent(projectId)}`, {
        method: "DELETE",
      });
      setSelectedIds((current) => current.filter((id) => id !== projectId));
      setProjectStatuses((current) => {
        const next = { ...current };
        delete next[projectId];
        return next;
      });
      await refreshProjects();
    } catch (err) {
      setError(err.message);
    } finally {
      setDeletingId("");
    }
  }

  async function handleCheckProjectStatus(projectId) {
    setError("");
    setProjectActions((current) => ({ ...current, [projectId]: "checking" }));
    try {
      const status = await requestJson(
        `/projects/${encodeURIComponent(projectId)}/status`,
      );
      setProjectStatuses((current) => ({ ...current, [projectId]: status }));
    } catch (err) {
      setError(err.message);
    } finally {
      setProjectActions((current) => ({ ...current, [projectId]: "" }));
    }
  }

  async function handleRefreshProject(projectId) {
    setError("");
    setProjectActions((current) => ({ ...current, [projectId]: "refreshing" }));
    try {
      const result = await requestJson(
        `/projects/${encodeURIComponent(projectId)}/refresh`,
        { method: "POST" },
      );
      await refreshProjects();
      await handleCheckProjectStatus(result.project_id || projectId);
    } catch (err) {
      setError(err.message);
    } finally {
      setProjectActions((current) => ({ ...current, [projectId]: "" }));
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
  const outputTabs = getOutputTabs({
    answer,
    documentText,
    querySources,
    documentSources,
    chainSteps,
  });
  const visibleOutputTab =
    outputTabs.some((tab) => tab.id === activeOutputTab)
      ? activeOutputTab
      : outputTabs[0]?.id || "answer";

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Codebase analysis</p>
          <h1>Reverse engineer a repo</h1>
          <p className="subtitle">
            Load a GitHub repository, ask focused questions, or generate a
            concise Markdown report.
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
            <CardHeader title="Repository" meta="Load" />
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
            {backendOffline && <p className="muted">Backend is offline.</p>}
            {ingestJob && (
              <p className={`job-status ${ingestJob.status.toLowerCase()}`}>
                Job {ingestJob.status.toLowerCase()}
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
            <CardHeader title="Scope" meta={scopeLabel} />
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
                    <div
                      className={`project-item ${
                        hasProjectUpdates(projectStatuses[project.project_id])
                          ? "stale"
                          : ""
                      }`}
                      key={project.project_id}
                    >
                      <label>
                        <input
                          type="checkbox"
                          checked={selectedIds.includes(project.project_id)}
                          onChange={() => toggleProject(project.project_id)}
                        />
                        <span>
                          <strong>{repoNameFromUrl(project.repo_url)}</strong>
                          <small>
                            {project.files_loaded} files /{" "}
                            {project.chunks_created} chunks /{" "}
                            {shortSha(project.last_commit_sha)}
                          </small>
                        </span>
                      </label>
                      <ProjectStatus
                        status={projectStatuses[project.project_id]}
                      />
                      <div className="project-actions">
                        <button
                          type="button"
                          disabled={
                            !!projectActions[project.project_id] ||
                            deletingId === project.project_id
                          }
                          onClick={() =>
                            handleCheckProjectStatus(project.project_id)
                          }
                        >
                          {projectActions[project.project_id] === "checking"
                            ? "Checking..."
                            : "Check"}
                        </button>
                        {hasProjectUpdates(
                          projectStatuses[project.project_id],
                        ) && (
                          <button
                            type="button"
                            className="primary"
                            disabled={
                              !!projectActions[project.project_id] ||
                              deletingId === project.project_id
                            }
                            onClick={() =>
                              handleRefreshProject(project.project_id)
                            }
                          >
                            {projectActions[project.project_id] === "refreshing"
                              ? "Refreshing..."
                              : "Refresh"}
                          </button>
                        )}
                        <button
                          type="button"
                          className="danger"
                          disabled={
                            deletingId === project.project_id ||
                            projectActions[project.project_id] === "refreshing"
                          }
                          onClick={() =>
                            handleDeleteProject(
                              project.project_id,
                              project.repo_url,
                            )
                          }
                        >
                          {deletingId === project.project_id
                            ? "Removing..."
                            : "Remove"}
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </section>
        </aside>

        <section className="main-panel">
          <div className="actions-grid">
            <form className="card" onSubmit={handleQuery}>
              <CardHeader title="Ask" meta="Question" />
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
              <CardHeader title="Report" meta="Markdown" />
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
                <strong>No output yet</strong>
                <span>
                  Load a repository, then ask a question or generate a report.
                </span>
              </div>
            )}

            {!activeAction && outputTabs.length > 0 && (
              <>
                <div className="output-tabs" role="tablist" aria-label="Output">
                  {outputTabs.map((tab) => (
                    <button
                      key={tab.id}
                      type="button"
                      role="tab"
                      aria-selected={visibleOutputTab === tab.id}
                      className={visibleOutputTab === tab.id ? "active" : ""}
                      onClick={() => setActiveOutputTab(tab.id)}
                    >
                      {tab.label}
                      {tab.count > 0 && <span>{tab.count}</span>}
                    </button>
                  ))}
                </div>

                {visibleOutputTab === "answer" && (
                  <MarkdownOutput label="Answer" text={answer} />
                )}
                {visibleOutputTab === "document" && (
                  <MarkdownOutput label="Document" text={documentText} />
                )}
                {visibleOutputTab === "sources" && (
                  <SourcesPanel
                    querySources={querySources}
                    documentSources={documentSources}
                  />
                )}
                {visibleOutputTab === "chain" && (
                  <ChainStepsPanel steps={chainSteps} />
                )}
              </>
            )}
          </section>
        </section>
      </section>
    </main>
  );
}

function getOutputTabs({
  answer,
  documentText,
  querySources,
  documentSources,
  chainSteps,
}) {
  const tabs = [];
  const sourceCount = querySources.length + documentSources.length;
  if (answer) tabs.push({ id: "answer", label: "Answer", count: 0 });
  if (documentText) tabs.push({ id: "document", label: "Report", count: 0 });
  if (sourceCount > 0) {
    tabs.push({ id: "sources", label: "Sources", count: sourceCount });
  }
  if (chainSteps.length > 0) {
    tabs.push({ id: "chain", label: "Chain", count: chainSteps.length });
  }
  return tabs;
}

function hasProjectUpdates(status) {
  const github = status?.github;
  return !!(github?.has_new_commits || github?.hasNewCommits);
}

function ProjectStatus({ status }) {
  if (!status) return null;

  const github = status.github;
  const latestSha = github?.latest_commit_sha || github?.latestCommitSha;
  const needsRefresh = hasProjectUpdates(status);

  return (
    <div className="project-status">
      <span className={needsRefresh ? "update-needed" : "up-to-date"}>
        {needsRefresh ? "Update available" : "Up to date"}
      </span>
      {latestSha && <small>GitHub {shortSha(latestSha)}</small>}
    </div>
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
          <span>{index < 1 ? "done" : index === 1 ? "now" : "-"}</span>
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

function ChainStepsPanel({ steps }) {
  return (
    <section className="tab-panel">
      {steps.map((step) => (
        <article className="chain-step" key={step.name}>
          <h3>{step.name.replaceAll("_", " ")}</h3>
          <p>{step.description}</p>
          <pre>{step.content}</pre>
        </article>
      ))}
    </section>
  );
}

function SourcesPanel({ querySources, documentSources }) {
  return (
    <section className="tab-panel sources-panel">
      {querySources.length > 0 && (
        <SourceGroup label="Answer sources" sources={querySources} />
      )}
      {documentSources.length > 0 && (
        <SourceGroup label="Report sources" sources={documentSources} />
      )}
    </section>
  );
}

function SourceGroup({ label, sources }) {
  return (
    <div className="source-group">
      <h3>
        {label} <span>{sources.length}</span>
      </h3>
      {sources.map((source, index) => (
        <pre key={`${label}-${source.slice(0, 30)}-${index}`}>{source}</pre>
      ))}
    </div>
  );
}

createRoot(document.getElementById("root")).render(<App />);
