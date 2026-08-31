-- Per-user lifetime counters for the token-expensive endpoints. Each user may
-- run /query and /document a limited number of times (default 1); the limit
-- itself lives in app config, only the running totals are stored here.
ALTER TABLE users ADD COLUMN queries_used   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN documents_used INTEGER NOT NULL DEFAULT 0;
