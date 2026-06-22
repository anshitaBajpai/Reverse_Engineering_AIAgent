package com.reverseengineer.agent.model;

/** Response body for {@code POST /ingest}. */
public record IngestResponse(
        String message,
        String projectId,
        String commitSha,
        int filesLoaded,
        int chunksCreated
) {}
