package com.reverseengineer.agent.security;

import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.model.UserAccount;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
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

    public String issue(UserAccount user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(config.jwtIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(config.jwtTtlSeconds(), ChronoUnit.SECONDS))
                .subject(Long.toString(user.id()))
                .claim("username", user.username())
                .claim("role", user.role() != null ? user.role() : "USER")
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public long ttlSeconds() {
        return config.jwtTtlSeconds();
    }
}
