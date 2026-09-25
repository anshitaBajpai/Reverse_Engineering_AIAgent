package com.reverseengineer.agent.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.reverseengineer.agent.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionRenewalFilterTest {

    private static final SecretKey KEY = new SecretKeySpec(
            "0123456789abcdefghijklmnopqrstuvwxyzABCD".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private static final long TTL = 3600;

    private final JwtIssuer issuer = new JwtIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)),
            new AppProperties(null, List.of(), null, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, null, null,
                    null, null, new AppProperties.Auth("unused", "test-issuer", TTL, null), null, null));
    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(KEY)
            .macAlgorithm(MacAlgorithm.HS256).build();
    private final SessionRenewalFilter filter = new SessionRenewalFilter(issuer);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void renewsAgedTokenKeepingSessionAndClaims() throws Exception {
        Instant issued = Instant.now().minusSeconds(40 * 60);
        authenticate(issued);

        MockHttpServletResponse res = run("/projects");

        String cookie = res.getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.contains("Max-Age=" + TTL));
        Jwt renewed = decoder.decode(cookie.substring((AuthCookie.NAME + "=").length(), cookie.indexOf(';')));
        assertEquals("42", renewed.getSubject());
        assertEquals("alice", renewed.getClaimAsString("username"));
        assertEquals("USER", renewed.getClaimAsString("role"));
        assertEquals("sid-1", renewed.getClaimAsString("sid"));
        assertTrue(renewed.getExpiresAt().isAfter(issued.plusSeconds(TTL)));
    }

    @Test
    void leavesFreshTokenAlone() throws Exception {
        authenticate(Instant.now().minusSeconds(30));

        assertNull(run("/projects").getHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void doesNotRenewOnLogout() throws Exception {
        authenticate(Instant.now().minusSeconds(40 * 60));

        assertNull(run("/auth/logout").getHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void ignoresAnonymousRequests() throws Exception {
        assertNull(run("/health").getHeader(HttpHeaders.SET_COOKIE));
    }

    private void authenticate(Instant issuedAt) {
        Jwt jwt = new Jwt("token", issuedAt, issuedAt.plusSeconds(TTL), Map.of("alg", "HS256"),
                Map.of("sub", "42", "username", "alice", "role", "USER", "sid", "sid-1"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private MockHttpServletResponse run(String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
