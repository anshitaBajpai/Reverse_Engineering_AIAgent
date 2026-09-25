package com.reverseengineer.agent.security;

import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.model.UserAccount;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Mints the HMAC-signed access tokens returned by {@code /auth/login} and {@code /auth/register}. */
@Component
public class JwtIssuer {

    private final JwtEncoder encoder;
    private final AppProperties.Auth config;

    public JwtIssuer(JwtEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.config = props.auth();
    }

    public String issue(UserAccount user, String sessionId) {
        return sign(Long.toString(user.id()), user.username(),
                user.role() != null ? user.role() : "USER", sessionId);
    }

    /** Re-mints {@code current} with a fresh expiry, keeping its subject, claims and session id. */
    public String renew(Jwt current) {
        return sign(current.getSubject(), current.getClaimAsString("username"),
                current.getClaimAsString("role"), current.getClaimAsString("sid"));
    }

    private String sign(String subject, String username, String role, String sessionId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(config.jwtIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(config.jwtTtlSeconds(), ChronoUnit.SECONDS))
                .subject(subject)
                .claim("username", username)
                .claim("role", role)
                .claim("sid", sessionId)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public long ttlSeconds() {
        return config.jwtTtlSeconds();
    }
}
