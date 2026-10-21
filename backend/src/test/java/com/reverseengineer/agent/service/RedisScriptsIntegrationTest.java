package com.reverseengineer.agent.service;

import com.reverseengineer.agent.RequiresDocker;
import com.reverseengineer.agent.exception.UsageBudgetExceededException;
import com.reverseengineer.agent.service.RateLimiterService.Endpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the Lua scripts behind the token budget and the rate limiter against a
 * real Redis (needs Docker). Two service instances share one Redis, like two app
 * instances in production.
 */
@RequiresDocker
class RedisScriptsIntegrationTest {

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate template;

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        connections = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connections.afterPropertiesSet();
        connections.start();
        template = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void stopRedis() {
        if (connections != null) {
            connections.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void flush() {
        template.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Test
    void budgetIsEnforcedAcrossInstances() {
        UsageGuardService a = new UsageGuardService(provider(), UsageGuardServiceTest.props(1_000, 0));
        UsageGuardService b = new UsageGuardService(provider(), UsageGuardServiceTest.props(1_000, 0));

        a.reserve("alice", 700);

        assertThrows(UsageBudgetExceededException.class, () -> b.reserve("alice", 400));
        b.reserve("alice", 300);
        assertFalse(a.isWithinBudget("alice"));
    }

    @Test
    void globalBudgetCapsAllIdentities() {
        UsageGuardService guard = new UsageGuardService(provider(), UsageGuardServiceTest.props(1_000, 1_500));
        guard.reserve("alice", 1_000);

        assertThrows(UsageBudgetExceededException.class, () -> guard.reserve("bob", 600));
        guard.reserve("bob", 500);
    }

    @Test
    void adjustReleasesAReservation() {
        UsageGuardService guard = new UsageGuardService(provider(), UsageGuardServiceTest.props(1_000, 1_000));
        guard.reserve("alice", 1_000);

        guard.adjust("alice", -1_000);

        assertDoesNotThrow(() -> guard.reserve("alice", 1_000));
    }

    @Test
    void zeroBudgetsAreUnlimited() {
        UsageGuardService guard = new UsageGuardService(provider(), UsageGuardServiceTest.props(0, 0));

        guard.reserve("alice", 10_000_000);

        assertTrue(guard.isWithinBudget("alice"));
    }

    @Test
    void concurrentReservationsCannotOverspend() throws Exception {
        UsageGuardService guard = new UsageGuardService(provider(), UsageGuardServiceTest.props(1_000, 0));
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        guard.reserve("alice", 100);
                        return true;
                    } catch (UsageBudgetExceededException e) {
                        return false;
                    }
                }));
            }
            start.countDown();

            int granted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    granted++;
                }
            }
            assertEquals(10, granted);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tokenBucketLimitsAcrossInstances() {
        RateLimiterService a = new RateLimiterService(provider(), UsageGuardServiceTest.props(0, 0));
        RateLimiterService b = new RateLimiterService(provider(), UsageGuardServiceTest.props(0, 0));

        int allowed = 0;
        for (int i = 0; i < Endpoint.AUTH.maxRequests; i++) {
            if ((i % 2 == 0 ? a : b).isAllowed("10.0.0.1", Endpoint.AUTH)) {
                allowed++;
            }
        }

        assertEquals(Endpoint.AUTH.maxRequests, allowed);
        assertFalse(a.isAllowed("10.0.0.1", Endpoint.AUTH));
        assertFalse(b.isAllowed("10.0.0.1", Endpoint.AUTH));
        assertTrue(a.isAllowed("10.0.0.2", Endpoint.AUTH));
        assertTrue(a.isAllowed("10.0.0.1", Endpoint.QUERY));
    }

    private static ObjectProvider<StringRedisTemplate> provider() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("redisTemplate", template);
        return beans.getBeanProvider(StringRedisTemplate.class);
    }
}
