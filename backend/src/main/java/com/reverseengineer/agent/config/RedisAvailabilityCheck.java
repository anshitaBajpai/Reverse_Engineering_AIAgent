package com.reverseengineer.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * When {@code app.redis.required=true} (production), verifies at startup that
 * Redis is actually reachable and fails the context otherwise. This stops a
 * misconfigured deployment from running with the rate limiter and usage budgets
 * silently degraded to per-instance in-memory state.
 */
@Component
public class RedisAvailabilityCheck {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailabilityCheck.class);

    public RedisAvailabilityCheck(AppProperties props,
                                  ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        if (!props.redis().required()) {
            return;
        }
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        RedisConnectionFactory factory =
                redisTemplate != null ? redisTemplate.getConnectionFactory() : null;
        if (factory == null) {
            throw new IllegalStateException(
                    "app.redis.required=true but no Redis connection is configured "
                    + "(check spring.data.redis.* / REDIS_HOST).");
        }
        try (RedisConnection connection = factory.getConnection()) {
            connection.ping();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "app.redis.required=true but Redis is unreachable: " + e.getMessage(), e);
        }
        log.info("Redis connectivity verified at startup (app.redis.required=true).");
    }
}
