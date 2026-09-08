package com.reverseengineer.agent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

/**
 
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
        boolean secure = request.isSecure();
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(secure ? "None" : "Strict")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
