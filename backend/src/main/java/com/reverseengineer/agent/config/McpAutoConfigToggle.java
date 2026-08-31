package com.reverseengineer.agent.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes {@code MCP_ENABLED=false} (a.k.a. {@code spring.ai.mcp.server.enabled=false})
 * actually disable the MCP server.
 *
 * <p>In Spring AI 1.0.0-M6 that flag only backs off {@code MpcServerAutoConfiguration};
 * {@code MpcWebMvcServerAutoConfiguration} ignores it and then fails because the
 * {@code McpServerProperties} bean is gone. When the flag is false we add all three
 * MCP auto-configurations to {@code spring.autoconfigure.exclude} so none of them load.
 */
public class McpAutoConfigToggle implements EnvironmentPostProcessor {

    private static final List<String> MCP_AUTO_CONFIGURATIONS = List.of(
            "org.springframework.ai.autoconfigure.mcp.server.MpcServerAutoConfiguration",
            "org.springframework.ai.autoconfigure.mcp.server.MpcWebMvcServerAutoConfiguration",
            "org.springframework.ai.autoconfigure.mcp.server.MpcWebFluxServerAutoConfiguration");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty("spring.ai.mcp.server.enabled", Boolean.class, true)) {
            return; // MCP stays on — nothing to do
        }

        Set<String> excludes = new LinkedHashSet<>(MCP_AUTO_CONFIGURATIONS);
        String existing = environment.getProperty("spring.autoconfigure.exclude");
        if (existing != null && !existing.isBlank()) {
            for (String name : existing.split(",")) {
                if (!name.isBlank()) {
                    excludes.add(name.trim());
                }
            }
        }

        List<String> merged = new ArrayList<>(excludes);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "mcp-auto-config-toggle",
                Map.of("spring.autoconfigure.exclude", String.join(",", merged))));
    }
}
