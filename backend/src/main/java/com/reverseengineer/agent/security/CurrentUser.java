package com.reverseengineer.agent.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/** Reads the authenticated caller out of the security context. */
public final class CurrentUser {

    private CurrentUser() {}

    /** The user id carried in the JWT {@code sub} claim. */
    public static long id() {
        Authentication auth = authentication();
        try {
            return Long.parseLong(auth.getName());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Malformed token subject.");
        }
    }

    /** A stable identity string for rate-limiting and usage accounting. */
    public static String identity() {
        return "user:" + id();
    }

    public static String username() {
        Authentication auth = authentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String name = jwt.getToken().getClaimAsString("username");
            if (name != null) {
                return name;
            }
        }
        return auth.getName();
    }

    private static Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        return auth;
    }
}
