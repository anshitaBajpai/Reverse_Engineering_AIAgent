package com.reverseengineer.agent.service;

import com.reverseengineer.agent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


@Service
public class UsageGuardService {

    private static final Logger log = LoggerFactory.getLogger(UsageGuardService.class);
    private static final Duration KEY_TTL = Duration.ofHours(30);

    /** Synthetic identity that holds the combined all-accounts counter. */
    private static final String GLOBAL_IDENTITY = "__global__";

    private final StringRedisTemplate redisTemplate;
    private final boolean redisEnabled;
    private final boolean redisRequired;
    private final AppProperties.Usage config;

    private final Map<String, AtomicLong> inMemoryUsage = new ConcurrentHashMap<>();

    public UsageGuardService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                              AppProperties props) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.redisEnabled = this.redisTemplate != null;
        this.redisRequired = props.redis().required();
        this.config = props.usage();
    }

    private void logRedisFallback(String action, Exception e) {
        if (redisRequired) {
            log.error("Redis {} failed while app.redis.required=true — temporarily using "
                    + "per-instance in-memory usage state: {}", action, e.getMessage());
        } else {
            log.warn("Redis {} failed, falling back to in-memory usage state: {}",
                    action, e.getMessage());
        }
    }

    /** Must be called before starting an LLM call; rejects once today's budget is already spent. */
    public boolean isWithinBudget(String identity) {
        if (currentUsage(identity) >= config.dailyTokenBudget()) {
            return false;
        }
        long globalBudget = config.globalDailyTokenBudget();
        return globalBudget <= 0 || currentUsage(GLOBAL_IDENTITY) < globalBudget;
    }

    /** Records actual token usage from a completed OpenAI call and logs estimated spend. */
    public void recordUsage(String identity, int promptTokens, int completionTokens) {
        long total = (long) promptTokens + completionTokens;
        long usageToday = redisEnabled ? recordRedis(identity, total) : recordInMemory(identity, total);
        long globalToday = redisEnabled
                ? recordRedis(GLOBAL_IDENTITY, total) : recordInMemory(GLOBAL_IDENTITY, total);

        double cost = (promptTokens / 1000.0) * config.promptCostPer1kTokens()
                + (completionTokens / 1000.0) * config.completionCostPer1kTokens();
        log.info("OpenAI usage: identity={} prompt_tokens={} completion_tokens={} "
                        + "est_cost_usd={} today_total_tokens={} daily_budget={} "
                        + "global_today={} global_budget={}",
                identity, promptTokens, completionTokens,
                String.format("%.5f", cost), usageToday, config.dailyTokenBudget(),
                globalToday, config.globalDailyTokenBudget());

        if (usageToday >= config.dailyTokenBudget()) {
            log.warn("Identity '{}' has reached its daily token budget ({} tokens).",
                    identity, config.dailyTokenBudget());
        }
        long globalBudget = config.globalDailyTokenBudget();
        if (globalBudget > 0 && globalToday >= globalBudget) {
            log.warn("GLOBAL daily token budget reached ({} tokens). "
                    + "All identities are blocked until 00:00 UTC.", globalBudget);
        }
    }

    private long currentUsage(String identity) {
        if (redisEnabled) {
            try {
                String raw = redisTemplate.opsForValue().get(redisKey(identity));
                return raw != null ? Long.parseLong(raw) : 0L;
            } catch (Exception e) {
                logRedisFallback("usage lookup", e);
            }
        }
        AtomicLong counter = inMemoryUsage.get(inMemoryKey(identity));
        return counter != null ? counter.get() : 0L;
    }

    private long recordRedis(String identity, long tokens) {
        try {
            String key = redisKey(identity);
            Long usage = redisTemplate.opsForValue().increment(key, tokens);
            redisTemplate.expire(key, KEY_TTL);
            return usage != null ? usage : tokens;
        } catch (Exception e) {
            logRedisFallback("usage tracking", e);
            return recordInMemory(identity, tokens);
        }
    }

    private long recordInMemory(String identity, long tokens) {
        return inMemoryUsage
                .computeIfAbsent(inMemoryKey(identity), k -> new AtomicLong())
                .addAndGet(tokens);
    }

    private static String redisKey(String identity) {
        return "usage:tokens:" + identity + ":" + today();
    }

    private static String inMemoryKey(String identity) {
        return identity + ":" + today();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
