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
        Usage usage,
        Auth auth,
        Redis redis,
        Limits limits
) {
    public int embeddingBatchSize() {
        return embeddingBatchSize > 0 ? embeddingBatchSize : 100;
    }

    public Long githubStatusTtlMs() {
        return githubStatusTtlMs != null ? githubStatusTtlMs : 60_000L;
    }

    public Redis redis() {
        return redis != null ? redis : new Redis(false);
    }

    /**
     * When {@code required} is true the app refuses to start if Redis is
     * unreachable, and a later Redis outage is logged at ERROR instead of
     * silently degrading to per-instance in-memory limits. Set it in production.
     */
    public record Redis(boolean required) {}

    public Limits limits() {
        return limits != null ? limits : new Limits(0, 0);
    }

    /**
     * Ceilings on ingested repositories ({@code 0} = unlimited). Each project
     * adds rows to the vector store, which is never auto-pruned, so an
     * unbounded number of projects means unbounded database growth.
     */
    public record Limits(int maxProjectsPerUser, int maxProjectsTotal) {}

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
            long globalDailyTokenBudget,
            double promptCostPer1kTokens,
            double completionCostPer1kTokens,
            Integer maxQueriesPerUser,
            Integer maxDocumentsPerUser
    ) {
        /** {@code /query} calls allowed per user per UTC day (default 2; 0 = unlimited). */
        public int queriesLimit() {
            return maxQueriesPerUser != null ? Math.max(0, maxQueriesPerUser) : 2;
        }

        /** {@code /document} calls allowed per user per UTC day (default 2; 0 = unlimited). */
        public int documentsLimit() {
            return maxDocumentsPerUser != null ? Math.max(0, maxDocumentsPerUser) : 2;
        }
    }

    /** Settings for the HMAC-signed JWTs this service issues and validates. */
    public record Auth(
            String jwtSecret,
            String jwtIssuer,
            long jwtTtlSeconds,
            String signupCode
    ) {
        public String jwtIssuer() {
            return jwtIssuer != null && !jwtIssuer.isBlank()
                    ? jwtIssuer : "reverse-engineering-agent";
        }

        public long jwtTtlSeconds() {
            return jwtTtlSeconds > 0 ? jwtTtlSeconds : 3600L;
        }

        /** When set, {@code /auth/register} requires a matching {@code signup_code}. */
        public boolean signupCodeRequired() {
            return signupCode != null && !signupCode.isBlank();
        }
    }
}
