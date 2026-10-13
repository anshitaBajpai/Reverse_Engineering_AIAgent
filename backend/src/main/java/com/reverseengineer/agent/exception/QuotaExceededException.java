package com.reverseengineer.agent.exception;

/**
 * Thrown when a user has spent today's allowance of {@code /query} or
 * {@code /document} calls (see {@code UserQuotaService}). Maps to HTTP 429.
 */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
        super(message);
    }
}
