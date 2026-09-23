package com.reverseengineer.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Cache for expensive LLM responses, shared across instances through Redis.
 *
 * <p>Keys are versioned by the caller: each includes the commit SHA and ingest
 * time of every project the response was built from, so a re-ingest or delete
 * makes that project's old entries unreachable (they then expire by TTL) while
 * entries for other projects stay valid. Nothing is ever explicitly evicted.</p>
 *
 * <p>When Redis is not configured or unreachable, a small bounded in-memory LRU
 * is used instead; it is per-instance and dropped on restart.</p>
 */
@Service
public class ResponseCacheService {

    private static final Logger log = LoggerFactory.getLogger(ResponseCacheService.class);

    private static final int MAX_LOCAL_ENTRIES = 100;
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String REDIS_PREFIX = "cache:response:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Map<String, CachedValue> local = new LinkedHashMap<>(16, 0.75f, true);

    public ResponseCacheService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    public Optional<Map<String, Object>> get(String key) {
        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(REDIS_PREFIX + key);
                return json != null ? Optional.of(mapper.readValue(json, MAP_TYPE)) : Optional.empty();
            } catch (Exception e) {
                log.warn("Redis response cache unavailable, using in-memory cache: {}", e.getMessage());
            }
        }
        return getLocal(key);
    }

    public void put(String key, Map<String, Object> value) {
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(REDIS_PREFIX + key, mapper.writeValueAsString(value), TTL);
                return;
            } catch (Exception e) {
                log.warn("Redis response cache unavailable, using in-memory cache: {}", e.getMessage());
            }
        }
        putLocal(key, value);
    }

    private synchronized Optional<Map<String, Object>> getLocal(String key) {
        CachedValue cached = local.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.expiresAtMillis() <= System.currentTimeMillis()) {
            local.remove(key);
            return Optional.empty();
        }
        return Optional.of(cached.value());
    }

    private synchronized void putLocal(String key, Map<String, Object> value) {
        long now = System.currentTimeMillis();
        local.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        local.put(key, new CachedValue(Map.copyOf(value), now + TTL.toMillis()));
        while (local.size() > MAX_LOCAL_ENTRIES) {
            Iterator<String> keys = local.keySet().iterator();
            keys.next();
            keys.remove();
        }
    }

    private record CachedValue(Map<String, Object> value, long expiresAtMillis) {}
}
