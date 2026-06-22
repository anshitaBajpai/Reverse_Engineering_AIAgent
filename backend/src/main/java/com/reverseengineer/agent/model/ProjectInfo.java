package com.reverseengineer.agent.model;

import java.time.Instant;


public record ProjectInfo(
        String projectId,
        String repoUrl,
        Instant ingestedAt,
        String lastCommitSha,
        int filesLoaded,
        int chunksCreated
) {}
