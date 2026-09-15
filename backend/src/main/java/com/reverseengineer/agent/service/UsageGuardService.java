package com.reverseengineer.agent.service;

import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.exception.UsageBudgetExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


@Service
public class UsageGuardService {

    private static final Logger log = LoggerFactory.getLogger(UsageGuardService.class);
    private static final Duration KEY_TTL = Duration.ofHours(30);

    /** Synthetic identity that holds the combined all-accounts counter. */
    private static final String GLOBAL_IDENTITY = "__global__";

    /**
     * Atomically checks both the per-identity and global budget against the
     * reservation amount and, only if both pass, books the tokens against
     * both counters in the same round trip. This is what makes {@link #reserve}
     * safe under concurrent callers, unlike a plain GET-then-SET.
     */
    private static final String RESERVE_LUA = """
            local id_key = KEYS[1]
            local global_key = KEYS[2]
            local amount = tonumber(ARGV[1])
            local id_budget = tonumber(ARGV[2])
            local global_budget = tonumber(ARGV[3])
            local ttl_sec = tonumber(ARGV[4])

            local id_usage = tonumber(redis.call('GET', id_key) or '0')
            if id_budget > 0 and id_usage + amount > id_budget then
                return 0
            end

            local global_usage = tonumber(redis.call('GET', global_key) or '0')
            if global_budget > 0 and global_usage + amount > global_budget then
                return 0
            end

            redis.call('INCRBY', id_key, amount)
            redis.call('EXPIRE', id_key, ttl_sec)
            redis.call('INCRBY', global_key, amount)
            redis.call('EXPIRE', global_key, ttl_sec)
            return 1
            """;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> reserveScript;
    private final boolean redisEnabled;
    private final boolean redisRequired;
    private final AppProperties.Usage config;

    private final Map<String, AtomicLong> inMemoryUsage = new ConcurrentHashMap<>();
    /** Guards check-then-increment across both counters for the in-memory fallback path. */
    private final Object inMemoryLock = new Object();

    public UsageGuardService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                              AppProperties props) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.redisEnabled = this.redisTemplate != null;
        this.redisRequired = props.redis().required();
        this.config = props.usage();
        this.reserveScript = new DefaultRedisScript<>(RESERVE_LUA, Long.class);
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

    /**
     * Cheap, non-reserving peek at today's usage. Useful for an early, best-effort
     * rejection before doing any work, but callers that actually spend tokens must
     * use {@link #reserve} instead — this alone does not prevent concurrent callers
     * from all passing the check before any of them records usage.
     */
    public boolean isWithinBudget(String identity) {
        if (currentUsage(identity) >= config.dailyTokenBudget()) {
            return false;
        }
        long globalBudget = config.globalDailyTokenBudget();
        return globalBudget <= 0 || currentUsage(GLOBAL_IDENTITY) < globalBudget;
    }

    /**
     * Atomically books {@code estimatedTokens} against both the per-identity and
     * global daily budgets before an OpenAI call starts. Call {@link #adjust} once
     * the call finishes (with the real usage delta) or fails (with the negated
     * estimate, to release the reservation).
     *
     * @throws UsageBudgetExceededException if the reservation would exceed either budget
     */
    public void reserve(String identity, long estimatedTokens) {
        if (estimatedTokens <= 0) {
            return;
        }
        boolean allowed = redisEnabled
                ? reserveRedis(identity, estimatedTokens)
                : reserveInMemory(identity, estimatedTokens);
        if (!allowed) {
            log.warn("Usage budget reservation denied: identity={} amount={}", identity, estimatedTokens);
            throw new UsageBudgetExceededException(
                    "Daily OpenAI usage budget exceeded. Please try again tomorrow.");
        }
    }

    /** True up a prior {@link #reserve} with the real usage; {@code delta} may be negative. */
    public void adjust(String identity, long delta) {
        if (delta == 0) {
            return;
        }
        if (redisEnabled) {
            try {
                adjustRedis(identity, delta);
                return;
            } catch (Exception e) {
                logRedisFallback("usage adjustment", e);
            }
        }
        adjustInMemory(identity, delta);
    }

    private boolean reserveRedis(String identity, long amount) {
        try {
            List<String> keys = List.of(redisKey(identity), redisKey(GLOBAL_IDENTITY));
            Long result = redisTemplate.execute(reserveScript, keys,
                    String.valueOf(amount),
                    String.valueOf(config.dailyTokenBudget()),
                    String.valueOf(config.globalDailyTokenBudget()),
                    String.valueOf(KEY_TTL.toSeconds()));
            return result != null && result == 1L;
        } catch (Exception e) {
            logRedisFallback("usage reservation", e);
            return reserveInMemory(identity, amount);
        }
    }

    private boolean reserveInMemory(String identity, long amount) {
        synchronized (inMemoryLock) {
            long idBudget = config.dailyTokenBudget();
            if (idBudget > 0 && currentUsage(identity) + amount > idBudget) {
                return false;
            }
            long globalBudget = config.globalDailyTokenBudget();
            if (globalBudget > 0 && currentUsage(GLOBAL_IDENTITY) + amount > globalBudget) {
                return false;
            }
            inMemoryUsage.computeIfAbsent(inMemoryKey(identity), k -> new AtomicLong()).addAndGet(amount);
            inMemoryUsage.computeIfAbsent(inMemoryKey(GLOBAL_IDENTITY), k -> new AtomicLong()).addAndGet(amount);
            return true;
        }
    }

    private void adjustRedis(String identity, long delta) {
        String idKey = redisKey(identity);
        String globalKey = redisKey(GLOBAL_IDENTITY);
        redisTemplate.opsForValue().increment(idKey, delta);
        redisTemplate.expire(idKey, KEY_TTL);
        redisTemplate.opsForValue().increment(globalKey, delta);
        redisTemplate.expire(globalKey, KEY_TTL);
    }

    private void adjustInMemory(String identity, long delta) {
        synchronized (inMemoryLock) {
            inMemoryUsage.computeIfAbsent(inMemoryKey(identity), k -> new AtomicLong()).addAndGet(delta);
            inMemoryUsage.computeIfAbsent(inMemoryKey(GLOBAL_IDENTITY), k -> new AtomicLong()).addAndGet(delta);
        }
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
