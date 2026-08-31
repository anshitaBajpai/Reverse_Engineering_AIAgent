package com.reverseengineer.agent.model;

/**
 * A user's remaining allowance for the token-expensive endpoints.
 * Serialized snake_case: {@code queries_used}, {@code queries_limit}, etc.
 * A limit of {@code 0} means unlimited.
 */
public record QuotaSnapshot(
        int queriesUsed,
        int queriesLimit,
        int documentsUsed,
        int documentsLimit
) {
    public boolean queriesExhausted() {
        return queriesLimit > 0 && queriesUsed >= queriesLimit;
    }

    public boolean documentsExhausted() {
        return documentsLimit > 0 && documentsUsed >= documentsLimit;
    }
}
