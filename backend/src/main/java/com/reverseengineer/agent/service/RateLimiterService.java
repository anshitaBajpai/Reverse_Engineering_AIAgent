package com.reverseengineer.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    private static final String TOKEN_BUCKET_LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_ms = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local bucket = redis.call('HMGET', key, 'tokens', 'lastRefill')
            local tokens = tonumber(bucket[1]) or capacity
            local lastRefill = tonumber(bucket[2]) or 0
            local elapsed = math.max(0, now - lastRefill)
            local refill = elapsed * capacity / refill_ms
            tokens = math.min(capacity, tokens + refill)

            if tokens < 1 then
                redis.call('HSET', key, 'tokens', tostring(tokens), 'lastRefill', tostring(now))
                redis.call('PEXPIRE', key, math.max(1000, refill_ms * 2))
                return 0
            end

            tokens = tokens - 1
            redis.call('HSET', key, 'tokens', tostring(tokens), 'lastRefill', tostring(now))
            redis.call('PEXPIRE', key, math.max(1000, refill_ms * 2))
            return 1
            """;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> tokenBucketScript;
    private final boolean redisEnabled;

    public enum Endpoint {
        INGEST(3, 30_000L),
        QUERY(30, 60_000L),
        DOCUMENT(5, 60_000L);

        final int maxRequests;
        final long windowMs;

        Endpoint(int maxRequests, long windowMs) {
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
        }
    }

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public RateLimiterService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.redisEnabled = this.redisTemplate != null;
        this.tokenBucketScript = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class);
    }

    public boolean isAllowed(String ip, Endpoint endpoint) {
        if (redisEnabled) {
            try {
                return isAllowedRedis(ip, endpoint);
            } catch (Exception e) {
                log.warn("Redis rate limiter unavailable, falling back to in-memory limiter: {}",
                        e.getMessage());
            }
        }
        return isAllowedInMemory(ip, endpoint);
    }

    private boolean isAllowedRedis(String ip, Endpoint endpoint) {
        String key = "rate:bucket:" + endpoint.name() + ":" + ip;
        List<String> keys = List.of(key);
        Long result = redisTemplate.execute(tokenBucketScript,
                keys,
                String.valueOf(endpoint.maxRequests),
                String.valueOf(endpoint.windowMs),
                String.valueOf(System.currentTimeMillis()));

        boolean allowed = result != null && result == 1L;
        if (!allowed) {
            log.warn("Rate limit exceeded via Redis: endpoint={} ip={}", endpoint, ip);
        }
        return allowed;
    }

    private boolean isAllowedInMemory(String ip, Endpoint endpoint) {
        String key = endpoint.name() + ":" + ip;
        Deque<Long> timestamps = windows.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            long now = System.currentTimeMillis();
            long cutoff = now - endpoint.windowMs;

            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= endpoint.maxRequests) {
                log.warn("Rate limit exceeded: endpoint={} ip={}", endpoint, ip);
                return false;
            }

            timestamps.addLast(now);
            return true;
        }
    }
}
