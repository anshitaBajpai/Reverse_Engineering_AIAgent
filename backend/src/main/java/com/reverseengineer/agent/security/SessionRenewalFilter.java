package com.reverseengineer.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Sliding session: re-issues the session cookie with a fresh expiry on authenticated
 * requests, so {@code app.auth.jwt-ttl-seconds} acts as an idle timeout rather than a hard
 * cap on how long a signed-in user stays signed in. The renewed token keeps the same
 * {@code sid}, so "last login wins" and logout revocation still apply.
 *
 * <p>Tokens younger than {@link #renewAfter} are left alone to avoid minting a new
 * cookie on every request.
 */
public class SessionRenewalFilter extends OncePerRequestFilter {

    private static final Duration MAX_RENEW_AFTER = Duration.ofMinutes(5);

    private final JwtIssuer jwtIssuer;
    private final Duration renewAfter;

    public SessionRenewalFilter(JwtIssuer jwtIssuer) {
        this.jwtIssuer = jwtIssuer;
        Duration half = Duration.ofSeconds(jwtIssuer.ttlSeconds() / 2);
        this.renewAfter = half.compareTo(MAX_RENEW_AFTER) < 0 ? half : MAX_RENEW_AFTER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && shouldRenew(jwtAuth.getToken())) {
            response.addHeader(HttpHeaders.SET_COOKIE, AuthCookie.issue(
                    request, jwtIssuer.renew(jwtAuth.getToken()), jwtIssuer.ttlSeconds()).toString());
        }
        chain.doFilter(request, response);
    }

    /** Logout and account deletion clear the cookie themselves; don't hand out a fresh one. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/auth/logout") || path.equals("/auth/account");
    }

    private boolean shouldRenew(Jwt token) {
        Instant issuedAt = token.getIssuedAt();
        return issuedAt == null || issuedAt.plus(renewAfter).isBefore(Instant.now());
    }
}
