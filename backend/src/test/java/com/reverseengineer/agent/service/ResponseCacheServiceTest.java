package com.reverseengineer.agent.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResponseCacheServiceTest {

    private static final Map<String, Object> DOCUMENT = Map.of(
            "document", "# Report",
            "chain_steps", List.of(Map.of("name", "architecture", "description", "d")),
            "sources", List.of("### File: a.java\nclass A {}"));

    @Test
    void entriesAreSharedAcrossInstancesThroughRedis() {
        FakeRedis redis = new FakeRedis();
        ResponseCacheService writer = new ResponseCacheService(redis.provider());
        ResponseCacheService reader = new ResponseCacheService(redis.provider());

        writer.put("document:abc", DOCUMENT);

        assertEquals(DOCUMENT, reader.get("document:abc").orElseThrow());
        assertTrue(reader.get("document:other").isEmpty());
        assertEquals(Duration.ofMinutes(10), redis.ttls.get("cache:response:document:abc"));
    }

    @Test
    void fallsBackToLocalCacheWhenRedisIsDown() {
        FakeRedis redis = new FakeRedis();
        redis.down = true;
        ResponseCacheService cache = new ResponseCacheService(redis.provider());

        cache.put("question:q", Map.of("answer", "42"));

        assertEquals(Map.of("answer", "42"), cache.get("question:q").orElseThrow());
        assertTrue(redis.values.isEmpty());
    }

    @Test
    void worksWithoutRedis() {
        ResponseCacheService cache = new ResponseCacheService(FakeRedis.none());

        cache.put("question:q", Map.of("answer", "42"));

        assertEquals(Map.of("answer", "42"), cache.get("question:q").orElseThrow());
    }
}
