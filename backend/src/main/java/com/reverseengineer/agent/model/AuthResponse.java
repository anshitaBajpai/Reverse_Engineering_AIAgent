package com.reverseengineer.agent.model;

/**
 * Response body for {@code POST /auth/register} and {@code POST /auth/login}. The session
 * token itself travels only as an httpOnly {@code reagent_token} cookie (see {@code AuthCookie}),
 * never in this body, so it isn't readable from JavaScript.
 */
public record AuthResponse(
        long expiresInSeconds,
        String username,
        String role
) {
    public static AuthResponse of(long ttlSeconds, String username, String role) {
        return new AuthResponse(ttlSeconds, username, role);
    }
}
