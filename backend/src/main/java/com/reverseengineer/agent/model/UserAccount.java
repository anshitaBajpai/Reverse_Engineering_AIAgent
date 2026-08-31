package com.reverseengineer.agent.model;

import java.time.Instant;

/** A local user account row. {@code passwordHash} is a BCrypt digest. */
public record UserAccount(
        long id,
        String username,
        String passwordHash,
        String role,
        Instant createdAt
) {}
