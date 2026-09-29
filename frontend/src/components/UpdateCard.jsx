import { shortSha } from "../lib/format.js";

/** Result of "Check updates": whether GitHub has newer commits, with a re-index button. */
export default function UpdateCard({ status, refreshing, onRefresh }) {
  const github = status.github;
  return (
    <section className={`update-card ${github?.has_new_commits ? "has-updates" : ""}`}>
      <div>
        <strong>{github?.has_new_commits ? "New commits found" : "Project is up to date"}</strong>
        <span>
          Branch {github?.default_branch || "unknown"} · {github?.open_pr_count ?? "—"} open
          pull requests · latest {shortSha(github?.current_commit_sha)}
        </span>
      </div>
      {github?.has_new_commits && (
        <button className="primary-button compact" onClick={onRefresh} disabled={refreshing}>
          {refreshing ? "Refreshing…" : "Refresh index"}
        </button>
      )}
    </section>
  );
}
