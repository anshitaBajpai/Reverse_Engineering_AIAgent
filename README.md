# Reverse Engineering AI Agent

Point this tool at any GitHub repository and ask questions about how it works. It clones the repo, chunks the source files, stores everything as embeddings in a local Postgres database, and lets you query it in plain English — or have it write a full reverse-engineering document for you.

## What it does

- **Ingest a repo** — paste a GitHub URL, the backend clones it, chunks the code, and loads it into a PGVector store
- **Ask questions** — RAG-backed Q&A over the codebase ("how does authentication work?", "where is the rate limiter?")
- **Generate a document** — runs a 4-step LLM chain (architecture → behaviour → risk → synthesis) and produces a Markdown report
- **Check for updates** — compare the ingested commit SHA against the current HEAD on GitHub and re-ingest if needed
- **MCP server** — the backend also exposes an SSE endpoint so Claude Desktop (or any MCP client) can call the same tools directly, no UI needed

## Tech stack

| Layer        | What                            |
| ------------ | ------------------------------- |
| Frontend     | React 19 + Vite                 |
| Backend      | Spring Boot 3.3, Spring AI 1.0  |
| LLM          | OpenAI (gpt-4o-mini by default) |
| Embeddings   | text-embedding-3-small          |
| Vector store | PGVector (Postgres 16)          |
| Repo cloning | JGit                            |

## Prerequisites

- Java 21
- Node 18+
- Docker
- An OpenAI API key

## Getting started

**1. Start the database**

```bash
docker compose up -d
```

**2. Set up environment variables**

Copy `.env.example` to `.env` and fill in your OpenAI key:

**3. Start the backend**

```bash
cd backend
./mvnw spring-boot:run
```

The API will be at `http://localhost:8080`.

**4. Start the frontend**

```bash
cd frontend
npm install
npm run dev
```

Open `http://127.0.0.1:5173` in your browser.

## API endpoints

| Method | Path                     | What it does                            |
| ------ | ------------------------ | --------------------------------------- |
| POST   | `/ingest`                | Clone and ingest a GitHub repo          |
| POST   | `/query`                 | Ask a question about ingested code      |
| POST   | `/document`              | Generate a reverse-engineering document |
| GET    | `/projects`              | List all ingested projects              |
| GET    | `/projects/{id}/status`  | Check a project's status vs. GitHub     |
| POST   | `/projects/{id}/refresh` | Re-ingest if new commits exist          |
| DELETE | `/projects/{id}`         | Remove a project                        |

## MCP (Claude Desktop)

The backend exposes an MCP server at `http://localhost:8080/sse`. Add it to your Claude Desktop config:

```json
{
  "mcpServers": {
    "reverse-engineer": {
      "url": "http://localhost:8080/sse"
    }
  }
}
```

Claude will then have access to `ingest_repo`, `ask_question`, `generate_document`, `list_projects`, and `check_updates` as tools.
