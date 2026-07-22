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

    private final StringRedisTemplate redisTemplate;
    private final boolean redisEnabled;
    private final AppProperties.Usage config;

    private final Map<String, AtomicLong> inMemoryUsage = new ConcurrentHashMap<>();

    public UsageGuardService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                              AppProperties props) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.redisEnabled = this.redisTemplate != null;
        this.config = props.usage();
    }

    /** Must be called before starting an LLM call; rejects once today's budget is already spent. */
    public boolean isWithinBudget(String identity) {
        return currentUsage(identity) < config.dailyTokenBudget();
    }

    /** Records actual token usage from a completed OpenAI call and logs estimated spend. */
    public void recordUsage(String identity, int promptTokens, int completionTokens) {
        long total = (long) promptTokens + completionTokens;
        long usageToday = redisEnabled ? recordRedis(identity, total) : recordInMemory(identity, total);

        double cost = (promptTokens / 1000.0) * config.promptCostPer1kTokens()
                + (completionTokens / 1000.0) * config.completionCostPer1kTokens();
        log.info("OpenAI usage: identity={} prompt_tokens={} completion_tokens={} "
                        + "est_cost_usd={} today_total_tokens={} daily_budget={}",
                identity, promptTokens, completionTokens,
                String.format("%.5f", cost), usageToday, config.dailyTokenBudget());

        if (usageToday >= config.dailyTokenBudget()) {
            log.warn("Identity '{}' has reached its daily token budget ({} tokens).",
                    identity, config.dailyTokenBudget());
        }
    }

    private long currentUsage(String identity) {
        if (redisEnabled) {
            try {
                String raw = redisTemplate.opsForValue().get(redisKey(identity));
                return raw != null ? Long.parseLong(raw) : 0L;
            } catch (Exception e) {
                log.warn("Redis usage lookup failed, falling back to in-memory: {}", e.getMessage());
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
            log.warn("Redis usage tracking unavailable, falling back to in-memory: {}", e.getMessage());
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
