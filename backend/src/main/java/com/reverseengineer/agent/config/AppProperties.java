package com.reverseengineer.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String repoDir,
        List<String> allowedOrigins,
        Set<String> allowedRepoHosts,
        int maxFilesToLoad,
        long maxFileBytes,
        int maxTotalSourceChars,
        int maxQueryK,
        int maxDocumentK,
        int maxQuestionLength,
        int maxProjectNameLength,
        String githubToken,
        Llm llm
) {
    public record Llm(
            double queryTemperature,
            double chainTemperature,
            double synthesisTemperature,
            int queryMaxTokens,
            int chainMaxTokens,
            int synthesisMaxTokens
    ) {}
}
