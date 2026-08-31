package com.reverseengineer.agent.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Best-effort client IP resolution. {@code X-Forwarded-For} is only trusted when
 * the immediate peer is loopback (i.e. a local reverse proxy).
 */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (isLoopback(req.getRemoteAddr()) && forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return req.getRemoteAddr();
    }

    private static boolean isLoopback(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::1".equals(remoteAddr);
    }
}
