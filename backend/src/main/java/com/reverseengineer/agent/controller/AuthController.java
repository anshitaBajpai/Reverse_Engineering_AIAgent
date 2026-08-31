package com.reverseengineer.agent.controller;

import com.reverseengineer.agent.model.AuthRequest;
import com.reverseengineer.agent.model.AuthResponse;
import com.reverseengineer.agent.model.UserAccount;
import com.reverseengineer.agent.security.ClientIp;
import com.reverseengineer.agent.security.CurrentUser;
import com.reverseengineer.agent.security.JwtIssuer;
import com.reverseengineer.agent.service.RateLimiterService;
import com.reverseengineer.agent.service.UserAccountService;
import com.reverseengineer.agent.service.UserQuotaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public AuthController(UserAccountService users, JwtIssuer jwtIssuer,
                         RateLimiterService rateLimiter, UserQuotaService userQuota) {
        this.users = users;
        this.jwtIssuer = jwtIssuer;
        this.rateLimiter = rateLimiter;
        this.userQuota = userQuota;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody AuthRequest body,
                                                 HttpServletRequest httpReq) {
        rateLimit(httpReq);
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

    private AuthResponse token(UserAccount user) {
        return AuthResponse.bearer(
                jwtIssuer.issue(user), jwtIssuer.ttlSeconds(), user.username(),
                user.role() != null ? user.role() : "USER");
    }

    private void rateLimit(HttpServletRequest httpReq) {
        if (!rateLimiter.isAllowed(ClientIp.of(httpReq), RateLimiterService.Endpoint.AUTH)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Please wait a minute and try again.");
        }
    }
}
