package com.reverseengineer.agent.model;

import java.time.Instant;

/** Response body for {@code GET /projects/{projectId}/status}. */
public record StatusResponse(
        String projectId,
        String repoUrl,
        Instant ingestedAt,
        String lastCommitSha,
        int filesLoaded,
        int chunksCreated,
        GitHubInfo github
) {
    /**
     * Live data fetched from the GitHub API at request time.
     * All fields are {@code null} / -1 when the GitHub API is unreachable
     * or the token is missing.
     */
    public record GitHubInfo(
            String defaultBranch,
            String currentCommitSha,
            boolean hasNewCommits,
            int openPrCount,
            int branchCount,
            String lastPushedAt
    ) {}
}
