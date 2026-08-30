package com.reverseengineer.agent.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small bounded cache for expensive LLM responses.
 *
 * <p>The cache is deliberately local and best-effort. A restart simply drops
 * cached responses, while the repository commit in each key prevents a
 * refreshed index from reusing an older answer.</p>
 */
@Service
public class ResponseCacheService {

    private static final int MAX_ENTRIES = 100;
    private static final Duration TTL = Duration.ofMinutes(10);

    private final Map<String, CachedValue> values = new LinkedHashMap<>(16, 0.75f, true);

    public synchronized Optional<Map<String, Object>> get(String key) {
        CachedValue cached = values.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.expiresAtMillis() <= System.currentTimeMillis()) {
            values.remove(key);
            return Optional.empty();
        }
        return Optional.of(cached.value());
    }

    public synchronized void put(String key, Map<String, Object> value) {
        removeExpired();
        values.put(key, new CachedValue(
                Map.copyOf(value), System.currentTimeMillis() + TTL.toMillis()));
        while (values.size() > MAX_ENTRIES) {
            Iterator<String> keys = values.keySet().iterator();
            keys.next();
            keys.remove();
        }
    }

    public synchronized int size() {
        removeExpired();
        return values.size();
    }

    /**
     * Drops every cached response. Called whenever the underlying corpus changes
     * (a project is ingested, re-ingested, or deleted) so a stale answer or
     * document can never outlive the data it was built from.
     */
    public synchronized void clear() {
        values.clear();
    }

    private void removeExpired() {
        long now = System.currentTimeMillis();
        values.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private record CachedValue(Map<String, Object> value, long expiresAtMillis) {}
}
