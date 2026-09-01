package com.reverseengineer.agent.controller;

import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.model.AuthRequest;
import com.reverseengineer.agent.model.AuthResponse;
import com.reverseengineer.agent.model.UserAccount;
import com.reverseengineer.agent.security.ClientIp;
import com.reverseengineer.agent.security.CurrentUser;
import com.reverseengineer.agent.security.JwtIssuer;
import com.reverseengineer.agent.service.RateLimiterService;
import com.reverseengineer.agent.service.SessionRegistry;
import com.reverseengineer.agent.service.UserAccountService;
import com.reverseengineer.agent.service.UserQuotaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserAccountService users;
    private final JwtIssuer jwtIssuer;
    private final RateLimiterService rateLimiter;
    private final UserQuotaService userQuota;
    private final SessionRegistry sessions;
    private final AppProperties props;

    public AuthController(UserAccountService users, JwtIssuer jwtIssuer,
                         RateLimiterService rateLimiter, UserQuotaService userQuota,
                         SessionRegistry sessions, AppProperties props) {
        this.users = users;
        this.jwtIssuer = jwtIssuer;
        this.rateLimiter = rateLimiter;
        this.userQuota = userQuota;
        this.sessions = sessions;
        this.props = props;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody AuthRequest body,
                                                 HttpServletRequest httpReq) {
        rateLimit(httpReq);
        requireValidSignupCode(body.signupCode());
        UserAccount user;
        try {
            user = users.register(body.username(), body.password());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        log.info("New account created: {}", user.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(token(user));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest body,
                                              HttpServletRequest httpReq) {
        rateLimit(httpReq);
        UserAccount user = users.authenticate(body.username(), body.password())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid username or password."));
        return ResponseEntity.ok(token(user));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        long id = CurrentUser.id();
        return Map.of(
                "id", id,
                "username", CurrentUser.username(),
                "quota", userQuota.snapshot(id));
    }

    /** Ends this account's active session server-side so its token stops working immediately. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        sessions.clear(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    /**
     * Issues a token on a fresh session id. Any token from an earlier login for
     * this user carries the previous id and is rejected on its next request.
     */
    private AuthResponse token(UserAccount user) {
        String sessionId = sessions.rotate(user.id());
        return AuthResponse.bearer(
                jwtIssuer.issue(user, sessionId), jwtIssuer.ttlSeconds(), user.username(),
                user.role() != null ? user.role() : "USER");
    }

    private void rateLimit(HttpServletRequest httpReq) {
        if (!rateLimiter.isAllowed(ClientIp.of(httpReq), RateLimiterService.Endpoint.AUTH)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Please wait a minute and try again.");
        }
    }

    /** No-op unless {@code SIGNUP_CODE} is configured; then the request must carry a matching code. */
    private void requireValidSignupCode(String provided) {
        AppProperties.Auth auth = props.auth();
        if (auth == null || !auth.signupCodeRequired()) {
            return;
        }
        byte[] expected = auth.signupCode().strip().getBytes(StandardCharsets.UTF_8);
        byte[] given = (provided == null ? "" : provided.strip()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A valid signup code is required to create an account.");
        }
    }
}
