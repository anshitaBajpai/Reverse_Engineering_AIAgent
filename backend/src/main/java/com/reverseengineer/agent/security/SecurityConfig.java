package com.reverseengineer.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.exception.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Paths reachable without a bearer token. MCP ({@code /sse}, {@code /message}) stays open by design. */
    private static final String[] PUBLIC_PATHS = {
            "/", "/health", "/error",
            "/auth/register", "/auth/login",
            "/sse/**", "/mcp/**", "/message/**"
    };

    private final AppProperties props;

    public SecurityConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationConverter jwtAuthConverter,
                                            ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint entryPoint = (req, res, ex) ->
                writeError(res, objectMapper, HttpStatus.UNAUTHORIZED,
                        "Authentication required.", req.getRequestURI());
        AccessDeniedHandler deniedHandler = (req, res, ex) ->
                writeError(res, objectMapper, HttpStatus.FORBIDDEN,
                        "You do not have access to this resource.", req.getRequestURI());

        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(PUBLIC_PATHS).permitAll()
                    .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(deniedHandler)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)))
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            return role == null || role.isBlank()
                    ? List.of()
                    : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        cfg.setExposedHeaders(List.of("X-Request-Id"));
        cfg.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    /** Placeholder / obviously-weak values that must never sign real tokens. */
    private static final Set<String> BANNED_JWT_SECRETS = Set.of(
            "change-me-to-a-long-random-string-at-least-32-chars",
            "changeme", "change-me", "secret", "password", "test", "test-secret");

    private SecretKey secretKey() {
        String secret = props.auth() != null ? props.auth().jwtSecret() : null;
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.auth.jwt-secret (env JWT_SECRET) must be set to at least 32 characters.");
        }
        // Length alone is not enough: the .env.example placeholder is 48 chars and
        // would let anyone forge tokens. Reject known placeholders and low-entropy
        // strings so a copied-but-unedited secret fails fast at startup.
        String trimmed = secret.strip();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        boolean lowEntropy = trimmed.chars().distinct().count() < 10;
        if (BANNED_JWT_SECRETS.contains(normalized)
                || normalized.startsWith("change-me")
                || normalized.startsWith("your-")
                || lowEntropy) {
            throw new IllegalStateException(
                    "app.auth.jwt-secret is a placeholder or low-entropy value. Generate a real "
                    + "secret (e.g. `openssl rand -base64 48`) and set it via the JWT_SECRET "
                    + "environment variable.");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static void writeError(HttpServletResponse res, ObjectMapper mapper,
                                   HttpStatus status, String message, String path) {
        try {
            res.setStatus(status.value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getWriter(),
                    ApiErrorResponse.of(status.value(), status.getReasonPhrase(), message, path));
        } catch (Exception ignored) {
            res.setStatus(status.value());
        }
    }
}
