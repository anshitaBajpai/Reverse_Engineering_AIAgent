import { useEffect, useRef, useState } from "react";
import { shortSha } from "../lib/format.js";

/** Active-project picker plus the selected project's stats and actions. */
export default function ProjectBar({
  projects,
  selectedProject,
  onSelect,
  onCheckUpdates,
  onRemove,
  busyAction,
}) {
  const [open, setOpen] = useState(false);
  const pickerRef = useRef(null);

  useEffect(() => {
    const onPointerDown = (event) => {
      if (pickerRef.current && !pickerRef.current.contains(event.target)) {
        setOpen(false);
      }
    };
    window.addEventListener("pointerdown", onPointerDown);
    return () => window.removeEventListener("pointerdown", onPointerDown);
  }, []);

  return (
    <section className="project-bar">
      <div className="project-picker" ref={pickerRef}>
        <label htmlFor="project">Active project</label>
        <div className={`select-shell ${open ? "open" : ""}`}>
          <button
            type="button"
            className="select-trigger"
            aria-haspopup="listbox"
            aria-expanded={open}
            onClick={() => setOpen((current) => !current)}
            disabled={!projects.length}
          >
            <span className="select-trigger-label">
              {selectedProject?.repo_url ||
                (projects.length ? "Choose a project" : "No projects indexed yet")}
            </span>
            <span className="select-trigger-icon" aria-hidden="true" />
          </button>
          {open && projects.length > 0 && (
            <div className="select-menu" role="listbox" aria-label="Projects">
              {projects.map((project) => {
                const isActive = project.project_id === selectedProject?.project_id;
                return (
                  <button
                    key={project.project_id}
                    type="button"
                    role="option"
                    aria-selected={isActive}
                    className={`select-option ${isActive ? "active" : ""}`}
                    onClick={() => {
                      onSelect(project.project_id);
                      setOpen(false);
                    }}
                  >
                    <span className="select-option-main">{project.repo_url}</span>
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
          onClick={onCheckUpdates}
          disabled={!selectedProject || Boolean(busyAction)}
          className="text-button"
        >
          {busyAction === "status" ? "Checking…" : "Check updates"}
        </button>
        <button
          onClick={onRemove}
          disabled={!selectedProject || Boolean(busyAction)}
          className="danger-button"
        >
          Remove
        </button>
      </div>
    </section>
  );
}
