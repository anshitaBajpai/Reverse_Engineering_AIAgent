package com.reverseengineer.agent.service;

import com.reverseengineer.agent.service.RateLimiterService.Endpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The in-memory sliding window (no Redis). The Redis token bucket is in RedisScriptsIntegrationTest. */
class RateLimiterServiceTest {

    private final RateLimiterService limiter =
            new RateLimiterService(FakeRedis.none(), UsageGuardServiceTest.props(0, 0));

    @Test
    void allowsUpToTheEndpointLimitThenRejects() {
        for (int i = 0; i < Endpoint.AUTH.maxRequests; i++) {
            assertTrue(limiter.isAllowed("10.0.0.1", Endpoint.AUTH), "request " + (i + 1));
        }

        assertFalse(limiter.isAllowed("10.0.0.1", Endpoint.AUTH));
    }

    @Test
    void clientsAreLimitedSeparately() {
        exhaust("10.0.0.1", Endpoint.DOCUMENT);

        assertTrue(limiter.isAllowed("10.0.0.2", Endpoint.DOCUMENT));
    }

    @Test
    void endpointsAreLimitedSeparately() {
        exhaust("10.0.0.1", Endpoint.INGEST);

        assertTrue(limiter.isAllowed("10.0.0.1", Endpoint.QUERY));
    }

    private void exhaust(String client, Endpoint endpoint) {
        while (limiter.isAllowed(client, endpoint)) {
            // drain the window
        }
    }
}
