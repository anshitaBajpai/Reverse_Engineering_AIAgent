import { useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  API_BASE_URL,
  AUTH_EVENT,
  fetchMe,
  getStoredUser,
  getToken,
  ingestRepositoryAsync,
  login,
  logout,
  register,
  requestJson,
} from "./apiClient.js";

const shortSha = (sha) => (sha ? sha.slice(0, 7) : "Unknown");
const escapeFilename = (value) =>
  String(value)
    .trim()
    .replace(/[\\/:*?"<>|]+/g, "-")
    .slice(0, 80);

function parseMarkdownBlocks(markdown) {
  const lines = String(markdown).split(/\r?\n/);
  const blocks = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) {
      i += 1;
      continue;
    }

    if (line.startsWith("```")) {
      const codeLines = [];
      i += 1;
      while (i < lines.length && !lines[i].startsWith("```")) {
        codeLines.push(lines[i]);
        i += 1;
      }
      blocks.push({ type: "code", text: codeLines.join("\n") });
      i += 1;
      continue;
    }

    const headingMatch = line.match(/^(#{1,3})\s+(.*)$/);
    if (headingMatch) {
      blocks.push({
        type: "heading",
        level: headingMatch[1].length,
        text: headingMatch[2].trim(),
      });
      i += 1;
      continue;
    }

    if (line.includes("|")) {
      const tableRows = [];
      let hasSeparator = false;
      while (i < lines.length && lines[i].includes("|")) {
        const currentLine = lines[i].trim();
        if (!currentLine) break;
        if (/^\|?[\s:-]+\|[\s|:-]*$/.test(currentLine)) {
          hasSeparator = true;
        } else {
          const cells = currentLine
            .replace(/^\|/, "")
            .replace(/\|$/, "")
            .split("|")
            .map((cell) => cell.trim());
          tableRows.push(cells);
        }
        i += 1;
      }
      if (hasSeparator && tableRows.length >= 2) {
        blocks.push({ type: "table", rows: tableRows });
        continue;
      }
      i -= tableRows.length ? 0 : 0;
    }

    const listMatch = line.match(/^[-*+]\s+(.*)$/);
    if (listMatch) {
      const items = [];
      while (i < lines.length) {
        const itemMatch = lines[i].match(/^[-*+]\s+(.*)$/);
        if (!itemMatch) break;
        items.push(itemMatch[1].trim());
        i += 1;
      }
      blocks.push({ type: "list", items });
      continue;
    }

    const paragraphLines = [line.trim()];
    i += 1;
    while (i < lines.length && lines[i].trim()) {
      if (
        lines[i].startsWith("```") ||
        lines[i].match(/^(#{1,3})\s+(.*)$/) ||
        lines[i].match(/^[-*+]\s+(.*)$/)
      ) {
        break;
      }
      paragraphLines.push(lines[i].trim());
      i += 1;
    }
    blocks.push({ type: "paragraph", text: paragraphLines.join(" ") });
  }

  return blocks;
}

function formatSourceRows(sources = []) {
  return sources.map((source, index) => ({
    index: index + 1,
    text: String(source),
  }));
}

function stripMarkdownMarkers(text) {
  return String(text)
    .replace(/\*\*(.+?)\*\*/g, "$1")
    .replace(/__(.+?)__/g, "$1")
    .replace(/(?<!\w)\*(?!\s)([^*\n]+?)(?<!\s)\*(?!\w)/g, "$1")
    .replace(/_(.+?)_/g, "$1")
    .replace(/`(.+?)`/g, "$1")
    .replace(/^\s*>\s?/gm, "")
    .trim();
}

function normalizeDocumentControl(markdown, projectName = "this codebase") {
  const text = String(markdown || "");
  if (!text.includes("## Document Control")) {
    return text;
  }

  const replacement = (rows = []) => {
    const defaultRows = [
      `Provide a comprehensive overview of ${projectName} for maintenance and future development.`,
      "Repository structure, source files, and README documentation.",
      "High",
      "Technical Document",
    ];
    const values = defaultRows.map((fallback, index) =>
      rows[index] && rows[index].trim() ? rows[index].trim() : fallback,
    );

    return [
      "## Document Control",
      "",
      "| Document Purpose | Source Basis | Confidence Level | Generated Output Type |",
      "| --- | --- | --- | --- |",
      `| ${values[0]} | ${values[1]} | ${values[2]} | ${values[3]} |`,
      "",
    ].join("\n");
  };

  const sectionMatch = text.match(
    /## Document Control([\s\S]*?)(?=\n## |\n# |\s*$)/,
  );
  if (!sectionMatch) return text;

  const sectionBody = sectionMatch[1];
  const tableRows = sectionBody
    .split(/\r?\n/)
    .filter((line) => line.includes("|"))
    .map((line) =>
      line
        .replace(/^\|/, "")
        .replace(/\|$/, "")
        .split("|")
        .map((cell) => cell.trim()),
    )
    .filter((row) => row.length > 1 && !row.every((cell) => /^-+$/.test(cell)));

  const valueLines =
    tableRows.length >= 2
      ? tableRows[1]
      : sectionBody
          .split(/\r?\n/)
          .map((line) => line.trim())
          .filter(Boolean)
          .filter((line) => !line.startsWith("#"))
          .slice(0, 4);

  return text.replace(
    /## Document Control[\s\S]*?(?=\n## |\n# |\s*$)/,
    replacement(valueLines),
  );
}

function normalizeDocumentSections(markdown) {
  const text = String(markdown || "");
  const sections = [
    "Executive Summary",
    "Scope And Methodology",
    "High-Level System Context",
    "Technology Stack",
    "Repository And Module Structure",
    "Component Inventory",
    "Runtime Behavior And Control Flow",
    "Data Flow And State Management",
    "API Surface And Interfaces",
    "Configuration, Environment, And Deployment",
    "Dependencies And External Integrations",
    "Security And Privacy Review",
    "Operational Risks And Failure Modes",
    "Maintainability Assessment",
    "Unknowns And Assumptions",
    "Recommended Next Steps",
    "Evidence Index",
  ];

  let normalized = text.replace(
    /^(#{2,3})\s+(.+?)\s*$/gm,
    (match, hashes, title) => `${hashes} ${title.trim()}`,
  );

  sections.forEach((section) => {
    const pattern = new RegExp(
      `(^##\\s+${section.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*$)`,
      "m",
    );
    normalized = normalized.replace(pattern, "\n$1\n");
  });

  return normalized.replace(/\n{3,}/g, "\n\n");
}

function App() {
  const [backendStatus, setBackendStatus] = useState("checking");
  const [projects, setProjects] = useState([]);
  const [selectedProjectId, setSelectedProjectId] = useState("");
  const [repoUrl, setRepoUrl] = useState("");
  const [question, setQuestion] = useState("");
  const [documentName, setDocumentName] = useState("");
  const [answer, setAnswer] = useState(null);
  const [document, setDocument] = useState(null);
  const [projectStatus, setProjectStatus] = useState(null);
  const [notice, setNotice] = useState(null);
  const [busyAction, setBusyAction] = useState("");
  const [ingestStage, setIngestStage] = useState("");
  const [resultTab, setResultTab] = useState("main");
  const [projectPickerOpen, setProjectPickerOpen] = useState(false);
  const projectPickerRef = useRef(null);
  const [authed, setAuthed] = useState(() => Boolean(getToken()));
  const [authUser, setAuthUser] = useState(() => getStoredUser());
  const [quota, setQuota] = useState(null);

  useEffect(() => {
    const onUnauthorized = () => {
      setAuthed(false);
      setAuthUser(null);
      setQuota(null);
      setProjects([]);
      setSelectedProjectId("");
    };
    window.addEventListener(AUTH_EVENT, onUnauthorized);
    return () => window.removeEventListener(AUTH_EVENT, onUnauthorized);
  }, []);

  const refreshQuota = useCallback(() => {
    fetchMe()
      .then((me) => setQuota(me?.quota || null))
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (authed) refreshQuota();
  }, [authed, refreshQuota]);

  const queriesExhausted =
    !!quota && quota.queries_limit > 0 && quota.queries_used >= quota.queries_limit;
  const documentsExhausted =
    !!quota &&
    quota.documents_limit > 0 &&
    quota.documents_used >= quota.documents_limit;

  const isBackendOnline = backendStatus === "online";
  const selectedProject = projects.find(
    (project) => project.project_id === selectedProjectId,
  );
  const normalizedDocument = normalizeDocumentControl(
    normalizeDocumentSections(document?.document),
    documentName || selectedProject?.repo_url || "this codebase",
  );
  const markdownComponents = {
    table: ({ children }) => (
      <div className="markdown-table-wrap">
        <table className="markdown-table">{children}</table>
      </div>
    ),
    th: ({ children }) => <th>{children}</th>,
    td: ({ children }) => <td>{children}</td>,
    tr: ({ children }) => <tr>{children}</tr>,
  };

  const showNotice = (type, message) => setNotice({ type, message });
  const projectIds = selectedProjectId ? [selectedProjectId] : [];

  useEffect(() => {
    const onPointerDown = (event) => {
      if (
        projectPickerRef.current &&
        !projectPickerRef.current.contains(event.target)
      ) {
        setProjectPickerOpen(false);
      }
    };

    window.addEventListener("pointerdown", onPointerDown);
    return () => window.removeEventListener("pointerdown", onPointerDown);
  }, []);

  const loadProjects = useCallback(async () => {
    const data = await requestJson("/projects");
    setProjects(Array.isArray(data) ? data : []);
    setSelectedProjectId((current) =>
      current && data?.some((project) => project.project_id === current)
        ? current
        : data?.[0]?.project_id || "",
    );
  }, []);

  useEffect(() => {
    let mounted = true;
    const checkBackend = async () => {
      try {
        const response = await fetch(`${API_BASE_URL}/health`);
        if (!mounted) return;
        setBackendStatus(response.ok ? "online" : "offline");
        if (response.ok && authed) {
          try {
            await loadProjects();
          } catch {
            // Health is available even when a project registry is not yet reachable.
          }
        }
      } catch {
        if (mounted) setBackendStatus("offline");
      }
    };
    checkBackend();
    const intervalId = window.setInterval(checkBackend, 15000);
    return () => {
      mounted = false;
      window.clearInterval(intervalId);
    };
  }, [loadProjects, authed]);

  async function ingestRepository(event) {
    event.preventDefault();
    if (!repoUrl.trim())
      return showNotice("error", "Enter a GitHub repository URL first.");
    setBusyAction("ingest");
    setIngestStage("Starting repository analysis…");
    setNotice(null);
    try {
      const result = await ingestRepositoryAsync(repoUrl, {
        onJobUpdate: (job) =>
          setIngestStage(
            job.status === "RUNNING"
              ? "Cloning and indexing source files…"
              : "Preparing analysis job…",
          ),
      });
      await loadProjects();
      setSelectedProjectId(result?.project_id || "");
      setRepoUrl("");
      showNotice(
        "success",
        `Repository ready: ${result?.files_loaded ?? 0} files and ${result?.chunks_created ?? 0} code chunks indexed.`,
      );
    } catch (error) {
      showNotice("error", error.message);
    } finally {
      setBusyAction("");
      setIngestStage("");
    }
  }

  async function askQuestion(event) {
    event.preventDefault();
    if (!question.trim())
      return showNotice("error", "Write a question before asking the agent.");
    if (!selectedProjectId)
      return showNotice(
        "error",
        "Ingest and select a project before asking a question.",
      );
    if (queriesExhausted)
      return showNotice(
        "error",
        "You've used all your questions for today. The limit resets tomorrow.",
      );
    setBusyAction("ask");
    setNotice(null);
    setDocument(null);
    setResultTab("main");
    try {
      const result = await requestJson("/query", {
        method: "POST",
        body: { question, k: 5, project_ids: projectIds },
      });
      setAnswer(result);
      refreshQuota();
    } catch (error) {
      showNotice("error", error.message);
    } finally {
      setBusyAction("");
    }
  }

  async function generateDocument(event) {
    event.preventDefault();
    if (!selectedProjectId)
      return showNotice(
        "error",
        "Ingest and select a project before generating a document.",
      );
    if (documentsExhausted)
      return showNotice(
        "error",
        "You've used all your technical documents for today. The limit resets tomorrow.",
      );
    setBusyAction("document");
    setNotice(null);
    setAnswer(null);
    setResultTab("main");
    try {
      const result = await requestJson("/document", {
        method: "POST",
        body: {
          project_name:
            documentName || selectedProject?.repo_url || "Ingested Repository",
          k: 25,
          project_ids: projectIds,
        },
      });
      setDocument(result);
      refreshQuota();
    } catch (error) {
      showNotice("error", error.message);
    } finally {
      setBusyAction("");
    }
  }

  async function downloadDocumentPdf() {
    const content = document?.document || "";
    const normalizedContent = normalizeDocumentControl(
      normalizeDocumentSections(content),
      documentName || selectedProject?.repo_url || "this codebase",
    );
    if (!normalizedContent.trim()) {
      showNotice(
        "error",
        "Generate a technical document before downloading a PDF.",
      );
      return;
    }

    const { jsPDF } = await import("jspdf");
    const title = escapeFilename(
      documentName || selectedProject?.repo_url || "Technical Document",
    );
    const doc = new jsPDF({ unit: "pt", format: "a4" });
    const pageWidth = doc.internal.pageSize.getWidth();
    const pageHeight = doc.internal.pageSize.getHeight();
    const margin = 40;
    const maxWidth = pageWidth - margin * 2;
    const lineHeight = 14;
    const paragraphGap = 8;
    let cursorY = margin;
    let pageNumber = 1;

    const ensureSpace = (needed = lineHeight) => {
      if (cursorY + needed > pageHeight - margin) {
        doc.addPage();
        cursorY = margin;
        pageNumber += 1;
      }
    };

    const writeWrapped = (text, fontSize = 11, options = {}) => {
      doc.setFont("helvetica", options.bold ? "bold" : "normal");
      doc.setFontSize(fontSize);
      const lines = doc.splitTextToSize(String(text), maxWidth);
      ensureSpace(lines.length * lineHeight);
      lines.forEach((line) => {
        if (cursorY > pageHeight - margin) {
          doc.addPage();
          cursorY = margin;
          pageNumber += 1;
        }
        doc.text(line, options.indent || margin, cursorY);
        cursorY += lineHeight;
      });
      cursorY += options.afterGap || 0;
    };

    parseMarkdownBlocks(normalizedContent).forEach((block) => {
      if (block.type === "heading") {
        const sizeByLevel = { 1: 15, 2: 13, 3: 12 };
        writeWrapped(
          stripMarkdownMarkers(block.text),
          sizeByLevel[block.level] || 12,
          {
            bold: true,
            afterGap: 5,
          },
        );
        return;
      }

      if (block.type === "paragraph") {
        writeWrapped(stripMarkdownMarkers(block.text), 11, {
          afterGap: paragraphGap,
        });
        return;
      }

      if (block.type === "list") {
        block.items.forEach((item) => {
          writeWrapped(`• ${stripMarkdownMarkers(item)}`, 11, {
            indent: margin + 12,
            afterGap: 2,
          });
        });
        cursorY += paragraphGap;
        return;
      }

      if (block.type === "table") {
        const rows = block.rows;
        const colCount = Math.max(...rows.map((row) => row.length));
        const usableWidth = maxWidth;
        const firstColWidth = Math.min(160, usableWidth * 0.34);
        const remainingWidth = usableWidth - firstColWidth;
        const otherColWidth =
          colCount > 1
            ? remainingWidth / Math.max(1, colCount - 1)
            : remainingWidth;
        const columnWidths = Array.from({ length: colCount }, (_, index) =>
          index === 0 ? firstColWidth : otherColWidth,
        );
        const startX = margin;
        const padX = 7;
        const padY = 6;

        rows.forEach((row, rowIndex) => {
          const cellLines = row.map((cell, cellIndex) =>
            doc.splitTextToSize(
              stripMarkdownMarkers(cell || ""),
              columnWidths[cellIndex] - padX * 2,
            ),
          );
          const rowHeight =
            Math.max(...cellLines.map((lines) => lines.length)) * lineHeight +
            padY * 2;
          ensureSpace(rowHeight + 4);

          doc.setFillColor(
            rowIndex === 0 ? 233 : 248,
            rowIndex === 0 ? 237 : 249,
            rowIndex === 0 ? 241 : 250,
          );
          doc.rect(startX, cursorY - 11, usableWidth, rowHeight, "F");
          doc.setDrawColor(210, 214, 219);
          doc.rect(startX, cursorY - 11, usableWidth, rowHeight);

          let cellX = startX;
          row.forEach((cell, cellIndex) => {
            if (cellIndex > 0) {
              doc.line(cellX, cursorY - 11, cellX, cursorY - 11 + rowHeight);
            }
            doc.setFont("helvetica", rowIndex === 0 ? "bold" : "normal");
            doc.setFontSize(10);
            const lines = cellLines[cellIndex];
            lines.forEach((line, lineIndex) => {
              doc.text(
                line,
                cellX + padX,
                cursorY + padY + lineIndex * lineHeight - 1,
              );
            });
            cellX += columnWidths[cellIndex];
          });

          cursorY += rowHeight;
        });
        cursorY += paragraphGap;
        return;
      }

      if (block.type === "code") {
        ensureSpace(28);
        doc.setFont("courier", "normal");
        doc.setFontSize(10);
        const codeLines = block.text.split(/\r?\n/);
        codeLines.forEach((codeLine) => {
          const wrapped = doc.splitTextToSize(codeLine || " ", maxWidth - 12);
          wrapped.forEach((segment) => {
            ensureSpace(lineHeight);
            doc.text(segment, margin + 6, cursorY);
            cursorY += lineHeight;
          });
        });
        cursorY += paragraphGap;
      }
    });

    const totalPages = doc.internal.getNumberOfPages();
    for (let page = 1; page <= totalPages; page += 1) {
      doc.setPage(page);
      doc.setFont("helvetica", "normal");
      doc.setFontSize(9);
      doc.setTextColor(107, 114, 128);
      doc.text(
        `Page ${page} of ${totalPages}`,
        pageWidth - margin,
        pageHeight - 18,
        {
          align: "right",
        },
      );
    }

    doc.save(`${title}.pdf`);
  }

  async function checkForUpdates() {
    if (!selectedProjectId) return;
    setBusyAction("status");
    setNotice(null);
    try {
      setProjectStatus(
        await requestJson(
          `/projects/${encodeURIComponent(selectedProjectId)}/status`,
        ),
      );
    } catch (error) {
      showNotice("error", error.message);
    } finally {
      setBusyAction("");
    }
  }

  async function refreshProject() {
    if (!selectedProjectId) return;
    setBusyAction("refresh");
    setNotice(null);
    try {
      const result = await requestJson(
        `/projects/${encodeURIComponent(selectedProjectId)}/refresh`,
        { method: "POST" },
      );
      if (result?.project_id) {
        setProjects((current) =>
          current.map((project) =>
            project.project_id === result.project_id
              ? {
                  ...project,
                  files_loaded:
                    typeof result.files_loaded === "number"
                      ? result.files_loaded
                      : project.files_loaded,
                  chunks_created:
                    typeof result.chunks_created === "number"
                      ? result.chunks_created
                      : project.chunks_created,
                  last_commit_sha:
                    typeof result.commit_sha === "string"
                      ? result.commit_sha
                      : project.last_commit_sha,
                }
              : project,
          ),
        );
      }
      await loadProjects();
      setProjectStatus(null);
      showNotice("success", result.message);
    } catch (error) {
      showNotice("error", error.message);
    } finally {
      setBusyAction("");
    }
  }

  async function deleteProject() {
    if (
      !selectedProject ||
      !window.confirm(
        `Remove ${selectedProject.repo_url} and its indexed data?`,
      )
    )
      return;
    setBusyAction("delete");
    try {
      const result = await requestJson(
        `/projects/${encodeURIComponent(selectedProjectId)}`,
        { method: "DELETE" },
      );
      setAnswer(null);
      setDocument(null);
      setProjectStatus(null);
      await loadProjects();
      showNotice("success", result.message);
    } catch (error) {
      showNotice("error", error.message);
    } finally {
      setBusyAction("");
    }
  }

  if (!authed) {
    return (
      <AuthView
        backendStatus={backendStatus}
        onAuthenticated={(user) => {
          setAuthUser(user);
          setAuthed(true);
          setNotice(null);
        }}
      />
    );
  }

  return (
    <div className="app-shell">
      <main className="workspace">
        <header className="topbar">
          <div className="hero-copy">
            <span className="eyebrow">Reverse Engineering AI Agent</span>
            <h1>Understand any codebase.</h1>
            <p>
              Ingest a repository, explore it with grounded answers, and
              generate a technical document.
            </p>
          </div>
          <div className="topbar-side">
            <div className={`status-pill ${isBackendOnline ? "" : "offline"}`}>
              <span className="status-dot" />
              {backendStatus === "checking"
                ? "Checking backend…"
                : isBackendOnline
                  ? "Backend ready"
                  : "Backend offline"}
            </div>
            <div className="account-chip">
              <span title="Signed in">{authUser?.username || "Signed in"}</span>
              <button
                type="button"
                className="text-button"
                onClick={() => {
                  logout();
                  setAuthed(false);
                  setAuthUser(null);
                }}
              >
                Sign out
              </button>
            </div>
          </div>
        </header>
        {notice && (
          <div className={`notice ${notice.type}`} role="status">
            {notice.message}
            <button
              onClick={() => setNotice(null)}
              aria-label="Dismiss notification"
            >
              ×
            </button>
          </div>
        )}
        <section className="ingest-panel">
          <div>
            <span className="step-label">01 · Add a repository</span>
            <h2>Start an analysis</h2>
            <p>
              Paste an HTTPS GitHub URL. Indexing runs in the background, so you
              can see progress without guessing.
            </p>
          </div>
          <form onSubmit={ingestRepository} className="ingest-form">
            <input
              aria-label="Repository URL"
              value={repoUrl}
              onChange={(event) => setRepoUrl(event.target.value)}
              placeholder="https://github.com/owner/repository"
              disabled={!isBackendOnline || busyAction === "ingest"}
            />
            <button
              className="primary-button"
              disabled={!isBackendOnline || busyAction === "ingest"}
            >
              {busyAction === "ingest" ? "Indexing…" : "Ingest repository"}
            </button>
          </form>
          {ingestStage && (
            <p className="progress-copy" aria-live="polite">
              {ingestStage}
            </p>
          )}
        </section>
        <section className="project-bar">
          <div className="project-picker" ref={projectPickerRef}>
            <label htmlFor="project">Active project</label>
            <div className={`select-shell ${projectPickerOpen ? "open" : ""}`}>
              <button
                type="button"
                className="select-trigger"
                aria-haspopup="listbox"
                aria-expanded={projectPickerOpen}
                onClick={() => setProjectPickerOpen((current) => !current)}
                disabled={!projects.length}
              >
                <span className="select-trigger-label">
                  {selectedProject?.repo_url ||
                    (projects.length
                      ? "Choose a project"
                      : "No projects indexed yet")}
                </span>
                <span className="select-trigger-icon" aria-hidden="true" />
              </button>
              {projectPickerOpen && projects.length > 0 && (
                <div className="select-menu" role="listbox" aria-label="Projects">
                  {projects.map((project) => {
                    const isActive = project.project_id === selectedProjectId;
                    return (
                      <button
                        key={project.project_id}
                        type="button"
                        role="option"
                        aria-selected={isActive}
                        className={`select-option ${isActive ? "active" : ""}`}
                        onClick={() => {
                          setSelectedProjectId(project.project_id);
                          setAnswer(null);
                          setDocument(null);
                          setProjectStatus(null);
                          setProjectPickerOpen(false);
                        }}
                      >
                        <span className="select-option-main">
                          {project.repo_url}
                        </span>
                        <span className="select-option-meta">
                          {project.files_loaded} files · {project.chunks_created} chunks
                        </span>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          </div>
          {selectedProject && (
            <div className="project-meta">
              <span>{selectedProject.files_loaded} files</span>
              <span>{selectedProject.chunks_created} chunks</span>
              <span title={selectedProject.last_commit_sha}>
                commit {shortSha(selectedProject.last_commit_sha)}
              </span>
            </div>
          )}
          <div className="project-actions">
            <button
              onClick={checkForUpdates}
              disabled={!selectedProjectId || busyAction}
              className="text-button"
            >
              {busyAction === "status" ? "Checking…" : "Check updates"}
            </button>
            <button
              onClick={deleteProject}
              disabled={!selectedProjectId || busyAction}
              className="danger-button"
            >
              Remove
            </button>
          </div>
        </section>
        {projectStatus && (
          <section
            className={`update-card ${projectStatus.github?.has_new_commits ? "has-updates" : ""}`}
          >
            <div>
              <strong>
                {projectStatus.github?.has_new_commits
                  ? "New commits found"
                  : "Project is up to date"}
              </strong>
              <span>
                Branch {projectStatus.github?.default_branch || "unknown"} ·{" "}
                {projectStatus.github?.open_pr_count ?? "—"} open pull requests
                · latest {shortSha(projectStatus.github?.current_commit_sha)}
              </span>
            </div>
            {projectStatus.github?.has_new_commits && (
              <button
                className="primary-button compact"
                onClick={refreshProject}
                disabled={busyAction === "refresh"}
              >
                {busyAction === "refresh" ? "Refreshing…" : "Refresh index"}
              </button>
            )}
          </section>
        )}
        <section className="tool-grid">
          <form className="tool-card" onSubmit={askQuestion}>
            <span className="step-label">02 · Explore</span>
            <h2>Ask the codebase</h2>
            <p>
              Answers are restricted to the active project and include source
              paths.
            </p>
            <textarea
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              placeholder="How does authentication work?"
              rows="5"
              disabled={queriesExhausted}
            />
            <button
              className="primary-button"
              disabled={
                !selectedProjectId || busyAction === "ask" || queriesExhausted
              }
            >
              {busyAction === "ask" ? "Finding evidence…" : "Ask agent"}
            </button>
            {quota && quota.queries_limit > 0 && (
              <p className="quota-copy">
                {queriesExhausted
                  ? "You've used all your questions for today. The limit resets tomorrow."
                  : `${quota.queries_limit - quota.queries_used} of ${quota.queries_limit} question${quota.queries_limit === 1 ? "" : "s"} left.`}
              </p>
            )}
          </form>
          <form className="tool-card" onSubmit={generateDocument}>
            <span className="step-label">03 · Explain</span>
            <h2>Generate a technical document</h2>
            <p>
              Get architecture, behavior, risk, and synthesis in a shareable
              Markdown report.
            </p>
            <input
              value={documentName}
              onChange={(event) => setDocumentName(event.target.value)}
              placeholder="Optional document title"
              disabled={documentsExhausted}
            />
            <button
              className="secondary-button"
              disabled={
                !selectedProjectId ||
                busyAction === "document" ||
                documentsExhausted
              }
            >
              {busyAction === "document"
                ? "Writing document…"
                : "Generate document"}
            </button>
            {quota && quota.documents_limit > 0 && (
              <p className="quota-copy">
                {documentsExhausted
                  ? "You've used all your technical documents for today. The limit resets tomorrow."
                  : `${quota.documents_limit - quota.documents_used} of ${quota.documents_limit} document${quota.documents_limit === 1 ? "" : "s"} left.`}
              </p>
            )}
          </form>
        </section>
        {(answer || document) && (
          <section className="result-panel" aria-live="polite">
            <div className="result-head">
              <span className="step-label">
                {answer ? "ANSWER" : "TECHNICAL DOCUMENT"}
              </span>
              <div className="result-actions">
                {document && (
                  <button className="text-button" onClick={downloadDocumentPdf}>
                    Download PDF
                  </button>
                )}
                <button
                  className="text-button"
                  onClick={async () => {
                    try {
                      await navigator.clipboard.writeText(
                        answer?.answer || document?.document || "",
                      );
                      showNotice("success", "Copied to clipboard.");
                    } catch {
                      showNotice(
                        "error",
                        "Could not copy the result. Select the text and copy it manually.",
                      );
                    }
                  }}
                >
                  Copy
                </button>
              </div>
            </div>
            <div
              className="result-tabs"
              role="tablist"
              aria-label="Result views"
            >
              <button
                type="button"
                className={`tab-button ${resultTab === "main" ? "active" : ""}`}
                onClick={() => setResultTab("main")}
              >
                {answer ? "Answer" : "Document"}
              </button>
              <button
                type="button"
                className={`tab-button ${resultTab === "sources" ? "active" : ""}`}
                onClick={() => setResultTab("sources")}
                disabled={
                  !(answer?.sources?.length || document?.sources?.length)
                }
              >
                Sources
              </button>
            </div>
            {resultTab === "sources" ? (
              <SourceList sources={answer?.sources || document?.sources} />
            ) : (
              <div className="markdown">
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={markdownComponents}
                >
                  {answer?.answer || normalizedDocument || ""}
                </ReactMarkdown>
              </div>
            )}
          </section>
        )}
      </main>
    </div>
  );
}

function AuthView({ onAuthenticated, backendStatus }) {
  const [mode, setMode] = useState("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [signupCode, setSignupCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const isRegister = mode === "register";

  async function submit(event) {
    event.preventDefault();
    setError("");
    if (username.trim().length < 3) {
      setError("Username must be at least 3 characters.");
      return;
    }
    if (password.length < 8) {
      setError("Password must be at least 8 characters.");
      return;
    }
    setBusy(true);
    try {
      const { user } = isRegister
        ? await register(username.trim(), password, signupCode.trim())
        : await login(username.trim(), password);
      onAuthenticated(user);
    } catch (err) {
      setError(err.message || "Authentication failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app-shell auth-shell">
      <main className="auth-card">
        <span className="eyebrow">Reverse Engineering AI Agent</span>
        <h1>{isRegister ? "Create your account" : "Sign in"}</h1>
        <p>
          {isRegister
            ? "Pick a username and password. Your ingested repositories stay private to your account."
            : "Sign in to ingest repositories and ask questions about them."}
        </p>
        <form className="auth-form" onSubmit={submit}>
          <label htmlFor="auth-username">Username</label>
          <input
            id="auth-username"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            placeholder="ada"
          />
          <label htmlFor="auth-password">Password</label>
          <input
            id="auth-password"
            type="password"
            autoComplete={isRegister ? "new-password" : "current-password"}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            placeholder="At least 8 characters"
          />
          {isRegister && (
            <>
              <label htmlFor="auth-code">Invite code</label>
              <input
                id="auth-code"
                value={signupCode}
                onChange={(event) => setSignupCode(event.target.value)}
                placeholder="Provided by the site owner"
              />
            </>
          )}
          {error && (
            <div className="notice error" role="alert">
              {error}
            </div>
          )}
          <button className="primary-button" disabled={busy}>
            {busy
              ? "Working…"
              : isRegister
                ? "Create account"
                : "Sign in"}
          </button>
        </form>
        <button
          type="button"
          className="text-button auth-switch"
          onClick={() => {
            setMode(isRegister ? "login" : "register");
            setError("");
          }}
        >
          {isRegister
            ? "Already have an account? Sign in"
            : "New here? Create an account"}
        </button>
        {backendStatus === "offline" && (
          <p className="progress-copy">
            The backend looks offline — start it and try again.
          </p>
        )}
      </main>
    </div>
  );
}

function SourceList({ sources = [] }) {
  const rows = formatSourceRows(sources);
  return rows.length ? (
    <div className="sources">
      <strong>Grounding sources</strong>
      <div className="source-table" role="table" aria-label="Grounding sources">
        <div className="source-table-head" role="row">
          <span role="columnheader">#</span>
          <span role="columnheader">Source</span>
        </div>
        {rows.map((row) => (
          <div
            className="source-table-row"
            role="row"
            key={`${row.index}-${row.text}`}
          >
            <span role="cell">{row.index}</span>
            <span role="cell">{row.text}</span>
          </div>
        ))}
      </div>
    </div>
  ) : null;
}

export default App;
