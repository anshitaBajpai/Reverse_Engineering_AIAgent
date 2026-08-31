package com.reverseengineer.agent.exception;

/**
 * Thrown when a user has spent a lifetime per-user allowance (e.g. their one
 * allowed {@code /query} or {@code /document} call). Maps to HTTP 429.
 */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
        super(message);
    }
}
