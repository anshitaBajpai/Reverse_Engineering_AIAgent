package com.reverseengineer.agent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

/**
 * Builds the httpOnly cookie that carries the session JWT. SameSite=Strict assumes the
 * frontend and backend are served from the same site (including different ports/subdomains,
 * e.g. 127.0.0.1:5173 and 127.0.0.1:8080 in local dev) — a cross-site deployment would need
 * SameSite=None plus a CSRF token, which this app does not implement.
 */
public final class AuthCookie {

    public static final String NAME = "reagent_token";

    private AuthCookie() {}

    public static ResponseCookie issue(HttpServletRequest request, String token, long ttlSeconds) {
        return build(request, token, ttlSeconds);
    }

    public static ResponseCookie clear(HttpServletRequest request) {
        return build(request, "", 0);
    }

    private static ResponseCookie build(HttpServletRequest request, String value, long maxAgeSeconds) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
