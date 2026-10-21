package com.reverseengineer.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionRegistryTest {

    @Test
    void unknownUserFailsOpen() {
        SessionRegistry sessions = new SessionRegistry(FakeRedis.none(), UsageGuardServiceTest.props(0, 0));

        assertTrue(sessions.isCurrent(7, "anything"));
        assertTrue(sessions.isCurrent(7, null));
    }

    @Test
    void newLoginSupersedesThePreviousSession() {
        SessionRegistry sessions = new SessionRegistry(FakeRedis.none(), UsageGuardServiceTest.props(0, 0));
        String first = sessions.rotate(7);
        String second = sessions.rotate(7);

        assertNotEquals(first, second);
        assertFalse(sessions.isCurrent(7, first));
        assertTrue(sessions.isCurrent(7, second));
    }

    @Test
    void logoutRevokesTheCurrentSession() {
        SessionRegistry sessions = new SessionRegistry(FakeRedis.none(), UsageGuardServiceTest.props(0, 0));
        String sid = sessions.rotate(7);

        sessions.clear(7);

        assertFalse(sessions.isCurrent(7, sid));
        assertFalse(sessions.isCurrent(7, null));
    }

    @Test
    void sessionsAreSharedThroughRedisAcrossInstances() {
        FakeRedis redis = new FakeRedis();
        SessionRegistry a = new SessionRegistry(redis.provider(), UsageGuardServiceTest.props(0, 0));
        SessionRegistry b = new SessionRegistry(redis.provider(), UsageGuardServiceTest.props(0, 0));
        String old = a.rotate(7);

        String current = b.rotate(7);

        assertFalse(a.isCurrent(7, old));
        assertTrue(a.isCurrent(7, current));
    }

    @Test
    void redisRecordOutlivesTheTokenLifetime() {
        FakeRedis redis = new FakeRedis();
        SessionRegistry sessions = new SessionRegistry(redis.provider(), UsageGuardServiceTest.props(0, 0));

        sessions.rotate(7);

        assertTrue(redis.ttls.get("auth:sid:7").toSeconds() > 3600);
    }

    @Test
    void fallsBackToMemoryWhenRedisIsDown() {
        FakeRedis redis = new FakeRedis();
        redis.down = true;
        SessionRegistry sessions = new SessionRegistry(redis.provider(), UsageGuardServiceTest.props(0, 0));
        String first = sessions.rotate(7);
        String second = sessions.rotate(7);

        assertFalse(sessions.isCurrent(7, first));
        assertTrue(sessions.isCurrent(7, second));
    }
}
