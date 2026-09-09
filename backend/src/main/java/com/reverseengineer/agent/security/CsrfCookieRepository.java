package com.reverseengineer.agent.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.util.UUID;

/**
 * Double-submit CSRF cookie for the SPA: readable by JS (unlike {@link AuthCookie}) so it
 * can be echoed back in the {@value #HEADER_NAME} header on state-changing requests. Same
 * secure/sameSite rule as {@link AuthCookie} so the two cookies travel together.
 */
public final class CsrfCookieRepository implements CsrfTokenRepository {

    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-XSRF-TOKEN";
    private static final String PARAMETER_NAME = "_csrf";

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, UUID.randomUUID().toString());
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        boolean secure = request.isSecure();
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token != null ? token.getToken() : "")
                .httpOnly(false)
                .secure(secure)
                .sameSite(secure ? "None" : "Strict")
                .path("/")
                .maxAge(token != null ? -1 : 0)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, cookie.getValue());
            }
        }
        return null;
    }
}
