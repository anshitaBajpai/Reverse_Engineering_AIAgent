package com.reverseengineer.agent.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.model.UserAccount;
import com.reverseengineer.agent.service.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link JwtIssuer} tokens through a decoder wired like {@code SecurityConfig#jwtDecoder}. */
class JwtSessionTest {

    private static final SecretKey KEY = new SecretKeySpec(
            "0123456789abcdefghijklmnopqrstuvwxyzABCD".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private static final long TTL = 900;
    private static final UserAccount ALICE = new UserAccount(42L, "alice", "hash", "USER", null);

    private final SessionRegistry sessions = new SessionRegistry(
            new DefaultListableBeanFactory().getBeanProvider(StringRedisTemplate.class), props("test-issuer"));
    private final JwtIssuer issuer = new JwtIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)),
            props("test-issuer"));
    private final NimbusJwtDecoder decoder = decoder("test-issuer");

    @Test
    void issuedTokenCarriesUserClaimsAndExpiry() {
        String sid = sessions.rotate(ALICE.id());

        Jwt jwt = decoder.decode(issuer.issue(ALICE, sid));

        assertEquals("42", jwt.getSubject());
        assertEquals("alice", jwt.getClaimAsString("username"));
        assertEquals("USER", jwt.getClaimAsString("role"));
        assertEquals(sid, jwt.getClaimAsString("sid"));
        assertEquals(Duration.ofSeconds(TTL), Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()));
    }

    @Test
    void missingRoleDefaultsToUser() {
        UserAccount noRole = new UserAccount(43L, "bob", "hash", null, null);

        Jwt jwt = decoder.decode(issuer.issue(noRole, sessions.rotate(43L)));

        assertEquals("USER", jwt.getClaimAsString("role"));
    }

    @Test
    void renewedTokenKeepsSubjectAndSession() {
        String sid = sessions.rotate(ALICE.id());
        Jwt original = decoder.decode(issuer.issue(ALICE, sid));

        Jwt renewed = decoder.decode(issuer.renew(original));

        assertEquals(original.getSubject(), renewed.getSubject());
        assertEquals(original.getClaimAsString("username"), renewed.getClaimAsString("username"));
        assertEquals(sid, renewed.getClaimAsString("sid"));
        assertFalse(renewed.getExpiresAt().isBefore(original.getExpiresAt()));
    }

    @Test
    void newerLoginInvalidatesTheOlderToken() {
        String oldToken = issuer.issue(ALICE, sessions.rotate(ALICE.id()));
        String newToken = issuer.issue(ALICE, sessions.rotate(ALICE.id()));

        assertThrows(JwtException.class, () -> decoder.decode(oldToken));
        assertDoesNotThrow(() -> decoder.decode(newToken));
    }

    @Test
    void logoutInvalidatesTheToken() {
        String token = issuer.issue(ALICE, sessions.rotate(ALICE.id()));

        sessions.clear(ALICE.id());

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtIssuer foreign = new JwtIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)), props("someone-else"));

        String token = foreign.issue(ALICE, sessions.rotate(ALICE.id()));

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        SecretKey otherKey = new SecretKeySpec(
                "ZYXWVUTSRQPONMLKJIHGFEDCBA9876543210zyxw".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtIssuer forger = new JwtIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(otherKey)), props("test-issuer"));

        String token = forger.issue(ALICE, sessions.rotate(ALICE.id()));

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    private NimbusJwtDecoder decoder(String expectedIssuer) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(expectedIssuer),
                new SessionTokenValidator(sessions)));
        return d;
    }

    private static AppProperties props(String jwtIssuer) {
        return new AppProperties(null, List.of(), null, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, null, null,
                null, null, new AppProperties.Auth("unused", jwtIssuer, TTL, null), null, null);
    }
}
