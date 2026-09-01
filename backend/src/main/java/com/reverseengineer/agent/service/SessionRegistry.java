package com.reverseengineer.agent.service;

import com.reverseengineer.agent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the single active session id per user so a new login supersedes any
 * earlier one ("last login wins"). The id is embedded in the JWT as the
 * {@code sid} claim and checked on every request by {@code SessionTokenValidator};
 * once {@link #rotate} runs, tokens carrying the previous id stop working.
 *
 * <p>Backed by Redis (required in production) with a per-instance in-memory
 * fallback for local dev, mirroring {@link RateLimiterService} / {@link UsageGuardService}.
 */
@Service
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    private static final String KEY_PREFIX = "auth:sid:";
    /** Sentinel written on logout — never equal to a real (UUID) session id. */
    private static final String REVOKED = "revoked";

    private final StringRedisTemplate redis;
    private final boolean redisEnabled;
    private final Duration ttl;
    private final Map<Long, String> inMemory = new ConcurrentHashMap<>();

    public SessionRegistry(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                           AppProperties props) {
        this.redis = redisTemplateProvider.getIfAvailable();
        this.redisEnabled = this.redis != null;
        long ttlSeconds = props.auth() != null ? props.auth().jwtTtlSeconds() : 3600L;
        // Outlive any valid token so the record is present for the token's whole life.
        this.ttl = Duration.ofSeconds(ttlSeconds + 60);
    }

    /** Starts a fresh session and returns its id, invalidating tokens with the previous id. */
    public String rotate(long userId) {
        String sid = UUID.randomUUID().toString();
        if (redisEnabled) {
            try {
                redis.opsForValue().set(KEY_PREFIX + userId, sid, ttl);
                inMemory.remove(userId);
                return sid;
            } catch (Exception e) {
                log.warn("Redis unavailable for session rotate, using in-memory: {}", e.getMessage());
            }
        }
        inMemory.put(userId, sid);
        return sid;
    }

    /**
     * Ends the active session on logout. Writes a tombstone rather than deleting
     * so the outstanding token is actually rejected — a genuinely absent record
     * is treated as "no session yet" and fails open.
     */
    public void clear(long userId) {
        if (redisEnabled) {
            try {
                redis.opsForValue().set(KEY_PREFIX + userId, REVOKED, ttl);
                inMemory.remove(userId);
                return;
            } catch (Exception e) {
                log.warn("Redis unavailable for session clear, using in-memory: {}", e.getMessage());
            }
        }
        inMemory.put(userId, REVOKED);
    }

    /**
     * True when {@code sid} is the user's current session, or when nothing is on
     * record — fail-open so a Redis flush (or a token minted before this feature)
     * does not force a logout mid-lifetime. A logout tombstone is not "nothing",
     * so it returns false.
     */
    public boolean isCurrent(long userId, String sid) {
        String current = current(userId);
        return current == null || current.equals(sid);
    }

    private String current(long userId) {
        if (redisEnabled) {
            try {
                return redis.opsForValue().get(KEY_PREFIX + userId);
            } catch (Exception e) {
                log.warn("Redis unavailable for session lookup, using in-memory: {}", e.getMessage());
            }
        }
        return inMemory.get(userId);
    }
}
