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
        int embeddingBatchSize,
        String githubToken,
        Long githubStatusTtlMs,
        Llm llm,
        Usage usage
) {
    public int embeddingBatchSize() {
        return embeddingBatchSize > 0 ? embeddingBatchSize : 100;
    }

    public Long githubStatusTtlMs() {
        return githubStatusTtlMs != null ? githubStatusTtlMs : 60_000L;
    }

    public record Llm(
            double queryTemperature,
            double chainTemperature,
            double synthesisTemperature,
            int queryMaxTokens,
            int chainMaxTokens,
            int synthesisMaxTokens
    ) {}

   
    public record Usage(
            long dailyTokenBudget,
            double promptCostPer1kTokens,
            double completionCostPer1kTokens
    ) {}
}
