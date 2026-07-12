package com.reverseengineer.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.UUID;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger requestLog = LoggerFactory.getLogger("http.request");

    private final AppProperties props;

    public WebConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = props.allowedOrigins().toArray(String[]::new);
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(false);
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> requestLoggingFilter() {
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req,
                                            HttpServletResponse res,
                                            FilterChain chain)
                    throws ServletException, IOException {
                String requestId = requestId(req);
                long started = System.nanoTime();
                MDC.put("request_id", requestId);
                res.setHeader("X-Request-Id", requestId);
                try {
                    chain.doFilter(req, res);
                } finally {
                    long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                    requestLog.info("{} {} -> {} ({} ms)",
                            req.getMethod(),
                            req.getRequestURI(),
                            res.getStatus(),
                            elapsedMs);
                    MDC.remove("request_id");
                }
            }
        };

        var reg = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(-10);
        return reg;
    }

    
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> requestSizeLimitFilter() {
        var filter = new OncePerRequestFilter() {
            private static final long MAX_BODY_BYTES = 1_048_576L;

            @Override
            protected void doFilterInternal(HttpServletRequest req,
                                            HttpServletResponse res,
                                            FilterChain chain)
                    throws jakarta.servlet.ServletException, IOException {
                String cl = req.getHeader("Content-Length");
                if (cl != null) {
                    try {
                        long size = Long.parseLong(cl.strip());
                        if (size > MAX_BODY_BYTES) {
                            res.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                            res.setContentType("application/json");
                            res.getWriter().write("""
                                    {"status":413,"error":"Payload Too Large","message":"Request body too large (max 1 MB)","path":"%s","fields":{}}
                                    """.formatted(req.getRequestURI()));
                            return;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                chain.doFilter(req, res);
            }
        };

        var reg = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(0);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> securityHeadersFilter() {
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req,
                                            HttpServletResponse res,
                                            FilterChain chain)
                    throws ServletException, IOException {
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("X-Frame-Options", "DENY");
                res.setHeader("Referrer-Policy", "no-referrer");
                res.setHeader("Cache-Control", "no-store");
                res.setHeader("Cross-Origin-Resource-Policy", "same-origin");
                res.setHeader("Permissions-Policy",
                        "geolocation=(), microphone=(), camera=()");
                res.setHeader("Content-Security-Policy",
                        "default-src 'none'; connect-src 'self'");
                if (req.isSecure()) {
                    res.setHeader("Strict-Transport-Security",
                            "max-age=31536000; includeSubDomains");
                }
                chain.doFilter(req, res);
            }
        };

        var reg = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(1);
        return reg;
    }

    private static String requestId(HttpServletRequest req) {
        String header = req.getHeader("X-Request-Id");
        if (header != null && header.matches("[A-Za-z0-9._-]{8,80}")) {
            return header;
        }
        return UUID.randomUUID().toString();
    }
}
