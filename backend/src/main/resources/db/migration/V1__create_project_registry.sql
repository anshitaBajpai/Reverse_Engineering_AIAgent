CREATE TABLE IF NOT EXISTS project_registry (
    project_id       TEXT PRIMARY KEY,
    repo_url         TEXT NOT NULL,
    ingested_at      TIMESTAMPTZ,
    last_commit_sha  TEXT,
    files_loaded     INTEGER NOT NULL DEFAULT 0,
    chunks_created   INTEGER NOT NULL DEFAULT 0
);

