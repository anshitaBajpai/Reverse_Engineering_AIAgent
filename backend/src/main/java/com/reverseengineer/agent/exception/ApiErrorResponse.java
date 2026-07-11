package com.reverseengineer.agent.exception;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fields
) {
    public static ApiErrorResponse of(
            int status,
            String error,
            String message,
            String path) {
        return new ApiErrorResponse(
                Instant.now(),
                status,
                error,
                message,
                path,
                Map.of());
    }

    public static ApiErrorResponse withFields(
            int status,
            String error,
            String message,
            String path,
            Map<String, String> fields) {
        return new ApiErrorResponse(
                Instant.now(),
                status,
                error,
                message,
                path,
                fields != null ? fields : Map.of());
    }
}
