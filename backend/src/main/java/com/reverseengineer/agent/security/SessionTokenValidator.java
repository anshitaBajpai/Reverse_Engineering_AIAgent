package com.reverseengineer.agent.security;

import com.reverseengineer.agent.service.SessionRegistry;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a JWT whose {@code sid} claim is no longer the user's current session,
 * i.e. a newer login has taken over. Wired into the {@code JwtDecoder} in
 * {@code SecurityConfig} alongside the default expiry checks.
 */
public class SessionTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final SessionRegistry sessions;

    public SessionTokenValidator(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String subject = token.getSubject();
        if (subject == null) {
            return OAuth2TokenValidatorResult.success();
        }
        long userId;
        try {
            userId = Long.parseLong(subject);
        } catch (NumberFormatException e) {
            return OAuth2TokenValidatorResult.success();
        }
        if (sessions.isCurrent(userId, token.getClaimAsString("sid"))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "Session superseded by a newer login.", null));
    }
}
