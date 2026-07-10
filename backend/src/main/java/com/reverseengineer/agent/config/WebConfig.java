package com.reverseengineer.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

@Configuration
public class WebConfig implements WebMvcConfigurer {

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
                res.setHeader("Permissions-Policy",
                        "geolocation=(), microphone=(), camera=()");
                res.setHeader("Content-Security-Policy",
                        "default-src 'none'; connect-src 'self'");
                chain.doFilter(req, res);
            }
        };

        var reg = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(1);
        return reg;
    }
}
