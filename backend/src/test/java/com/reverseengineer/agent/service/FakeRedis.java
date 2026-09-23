package com.reverseengineer.agent.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for Redis string GET/SET, so two service instances built on
 * the same {@code FakeRedis} behave like two app instances sharing one Redis.
 * Only {@code get(key)} and {@code set(key, value, Duration)} are supported.
 */
final class FakeRedis {

    final Map<String, String> values = new ConcurrentHashMap<>();
    final Map<String, Duration> ttls = new ConcurrentHashMap<>();
    volatile boolean down;

    ObjectProvider<StringRedisTemplate> provider() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = (ValueOperations<String, String>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ValueOperations.class},
                (proxy, method, args) -> {
                    if (down) {
                        throw new IllegalStateException("redis down");
                    }
                    if (method.getName().equals("get") && args.length == 1) {
                        return values.get((String) args[0]);
                    }
                    if (method.getName().equals("set") && args.length == 3 && args[2] instanceof Duration ttl) {
                        values.put((String) args[0], (String) args[1]);
                        ttls.put((String) args[0], ttl);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
        StringRedisTemplate template = new StringRedisTemplate() {
            @Override
            public ValueOperations<String, String> opsForValue() {
                return ops;
            }
        };
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("redisTemplate", template);
        return beans.getBeanProvider(StringRedisTemplate.class);
    }

    static ObjectProvider<StringRedisTemplate> none() {
        return new DefaultListableBeanFactory().getBeanProvider(StringRedisTemplate.class);
    }
}
