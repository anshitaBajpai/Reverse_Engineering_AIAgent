import { useCallback, useEffect, useState } from "react";
import {
  API_BASE_URL,
  AUTH_EVENT,
  deleteAccount,
  fetchMe,
  getStoredUser,
  ingestRepositoryAsync,
  logout,
  requestJson,
} from "./apiClient.js";
import { isExhausted } from "./lib/format.js";
import { useStreamingResult } from "./hooks/useStreamingResult.js";
import AppHeader from "./components/AppHeader.jsx";
import AskPanel from "./components/AskPanel.jsx";
import AuthView from "./components/AuthView.jsx";
import ConfirmModal from "./components/ConfirmModal.jsx";
import DocumentPanel from "./components/DocumentPanel.jsx";
import IngestPanel from "./components/IngestPanel.jsx";
import ProjectBar from "./components/ProjectBar.jsx";
import ResultPanel from "./components/ResultPanel.jsx";
import UpdateCard from "./components/UpdateCard.jsx";

function App() {
  const [backendStatus, setBackendStatus] = useState("checking");
  const [projects, setProjects] = useState([]);
  const [selectedProjectId, setSelectedProjectId] = useState("");
  const [projectStatus, setProjectStatus] = useState(null);
  const [notice, setNotice] = useState(null);
  const [busyAction, setBusyAction] = useState("");
  const [ingestStage, setIngestStage] = useState("");
  // The session lives in an httpOnly cookie the JS layer can't read, so this is only an
  // optimistic guess from the last login to avoid a login-screen flash — refreshQuota()
  // below verifies it against the server and onUnauthorized rolls it back on a 401.
  const [authed, setAuthed] = useState(() => Boolean(getStoredUser()));
  const [authUser, setAuthUser] = useState(() => getStoredUser());
  const [quota, setQuota] = useState(null);
  const [confirmingProjectDelete, setConfirmingProjectDelete] = useState(false);
  const { result, run, stop, clear: clearResult } = useStreamingResult();

  const showNotice = useCallback((type, message) => setNotice({ type, message }), []);
  const selectedProject = projects.find((project) => project.project_id === selectedProjectId);
  const isBackendOnline = backendStatus === "online";

  const resetSession = useCallback(() => {
    setAuthed(false);
    setAuthUser(null);
    setQuota(null);
    setProjects([]);
    setSelectedProjectId("");
    clearResult();
  }, [clearResult]);

  useEffect(() => {
    window.addEventListener(AUTH_EVENT, resetSession);
    return () => window.removeEventListener(AUTH_EVENT, resetSession);
  }, [resetSession]);

  const refreshQuota = useCallback(() => {
    fetchMe()
      .then((me) => setQuota(me?.quota || null))
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (authed) refreshQuota();
  }, [authed, refreshQuota]);

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

  function selectProject(projectId) {
    setSelectedProjectId(projectId);
    setProjectStatus(null);
    clearResult();
  }

  async function ingestRepository(repoUrl) {
    if (!repoUrl.trim()) {
      showNotice("error", "Enter a GitHub repository URL first.");
      return false;
    }
    setBusyAction("ingest");
    setIngestStage("Starting repository analysis…");
    setNotice(null);
    try {
      const ingested = await ingestRepositoryAsync(repoUrl, {
        onJobUpdate: (job) =>
          setIngestStage(
            job.status === "RUNNING"
              ? "Cloning and indexing source files…"
              : "Preparing analysis job…",
          ),
      });
      await loadProjects();
      setSelectedProjectId(ingested?.project_id || "");
      showNotice(
        "success",
        `Repository ready: ${ingested?.files_loaded ?? 0} files and ${ingested?.chunks_created ?? 0} code chunks indexed.`,
      );
      return true;
    } catch (error) {
      showNotice("error", error.message);
      return false;
    } finally {
      setBusyAction("");
      setIngestStage("");
    }
  }

  /** Streams an answer or document into the result panel; quota is re-read afterwards. */
  async function streamResult(action, request) {
    setBusyAction(action);
    setNotice(null);
    const outcome = await run(request);
    if (outcome.error) showNotice("error", outcome.error.message);
    setBusyAction("");
    refreshQuota();
  }

  function askQuestion(question) {
    if (!question.trim()) return showNotice("error", "Write a question before asking the agent.");
    if (!selectedProjectId)
      return showNotice("error", "Ingest and select a project before asking a question.");
    if (isExhausted(quota?.queries_used, quota?.queries_limit))
      return showNotice("error", "You've used all your questions for today. The limit resets tomorrow.");
    return streamResult("ask", {
      kind: "answer",
      path: "/query/stream",
      body: { question, k: 5, project_ids: [selectedProjectId] },
    });
  }

  function generateDocument(title) {
    if (!selectedProjectId)
      return showNotice("error", "Ingest and select a project before generating a document.");
    if (isExhausted(quota?.documents_used, quota?.documents_limit))
      return showNotice(
        "error",
        "You've used all your technical documents for today. The limit resets tomorrow.",
      );
    const projectName = title.trim() || selectedProject?.repo_url || "Ingested Repository";
    return streamResult("document", {
      kind: "document",
      title: projectName,
      path: "/document/stream",
      body: { project_name: projectName, k: 25, project_ids: [selectedProjectId] },
    });
  }

  async function checkForUpdates() {
    if (!selectedProjectId) return;
    setBusyAction("status");
    setNotice(null);
    try {
      setProjectStatus(
        await requestJson(`/projects/${encodeURIComponent(selectedProjectId)}/status`),
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
      const refreshed = await requestJson(
        `/projects/${encodeURIComponent(selectedProjectId)}/refresh`,
        { method: "POST" },
      );
      await loadProjects();
      setProjectStatus(null);
      showNotice("success", refreshed.message);
    } catch (error) {
      showNotice("error", error.message);
    } finally {
      setBusyAction("");
    }
  }

  async function performDeleteProject() {
    setBusyAction("delete");
    try {
      const removed = await requestJson(`/projects/${encodeURIComponent(selectedProjectId)}`, {
        method: "DELETE",
      });
      clearResult();
      setProjectStatus(null);
      await loadProjects();
      showNotice("success", removed.message);
    } catch (error) {
      showNotice("error", error.message);
    } finally {
      setBusyAction("");
      setConfirmingProjectDelete(false);
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

  const streaming = busyAction === "ask" || busyAction === "document";

  return (
    <div className="app-shell">
      <main className="workspace">
        <AppHeader
          backendStatus={backendStatus}
          username={authUser?.username}
          onSignOut={() => {
            logout();
            resetSession();
          }}
          onDeleteAccount={async () => {
            try {
              await deleteAccount();
              resetSession();
            } catch (error) {
              showNotice("error", error.message);
            }
          }}
        />
        {notice && (
          <div className={`notice ${notice.type}`} role="status">
            {notice.message}
            <button onClick={() => setNotice(null)} aria-label="Dismiss notification">
              ×
            </button>
          </div>
        )}
        <IngestPanel
          online={isBackendOnline}
          busy={busyAction === "ingest"}
          stage={ingestStage}
          onIngest={ingestRepository}
        />
        <ProjectBar
          projects={projects}
          selectedProject={selectedProject}
          onSelect={selectProject}
          onCheckUpdates={checkForUpdates}
          onRemove={() => selectedProject && setConfirmingProjectDelete(true)}
          busyAction={busyAction}
        />
        {projectStatus && (
          <UpdateCard
            status={projectStatus}
            refreshing={busyAction === "refresh"}
            onRefresh={refreshProject}
          />
        )}
        <section className="tool-grid">
          <AskPanel
            hasProject={Boolean(selectedProjectId)}
            busy={streaming}
            quota={quota}
            onAsk={askQuestion}
          />
          <DocumentPanel
            hasProject={Boolean(selectedProjectId)}
            busy={streaming}
            quota={quota}
            onGenerate={generateDocument}
          />
        </section>
        {result && (
          <ResultPanel
            key={result.id}
            result={result}
            onStop={stop}
            onNotice={showNotice}
          />
        )}
      </main>
      {confirmingProjectDelete && selectedProject && (
        <ConfirmModal
          title="Remove this project?"
          message={`This deletes the indexed data for ${selectedProject.repo_url}. Existing answers and documents tied to it will be gone, but you can re-ingest the repository later.`}
          confirmLabel={busyAction === "delete" ? "Removing…" : "Remove project"}
          danger
          busy={busyAction === "delete"}
          onConfirm={performDeleteProject}
          onCancel={() => setConfirmingProjectDelete(false)}
        />
      )}
    </div>
  );
}

export default App;
