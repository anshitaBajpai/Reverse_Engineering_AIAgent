package com.reverseengineer.agent.model;

/**
 * Response body for {@code POST /auth/register} and {@code POST /auth/login}.
 * Serialized snake_case: {@code access_token}, {@code token_type}, {@code expires_in_seconds}.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String username,
        String role
) {
    public static AuthResponse bearer(String token, long ttlSeconds, String username, String role) {
        return new AuthResponse(token, "Bearer", ttlSeconds, username, role);
    }
}
