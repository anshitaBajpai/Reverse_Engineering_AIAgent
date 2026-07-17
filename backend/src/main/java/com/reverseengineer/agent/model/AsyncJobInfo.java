package com.reverseengineer.agent.model;

import java.time.Instant;

public record AsyncJobInfo(
        String jobId,
        String type,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Object result,
        String error
) {}
