-- Local user accounts. Passwords are BCrypt hashes; the app verifies them and
-- then issues its own HMAC-signed JWTs.
CREATE TABLE users (
    id            BIGSERIAL   PRIMARY KEY,
    username      TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username UNIQUE (username)
);

-- Ownership for ingested projects. NULL = shared / MCP-originated: the MCP
-- endpoint stays unauthenticated, so its projects belong to no REST user.
ALTER TABLE project_registry ADD COLUMN owner_id BIGINT;

CREATE INDEX idx_project_registry_owner ON project_registry (owner_id);
