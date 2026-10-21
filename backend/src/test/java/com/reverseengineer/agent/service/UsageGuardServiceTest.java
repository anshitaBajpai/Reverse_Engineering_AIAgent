package com.reverseengineer.agent.service;

import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.exception.UsageBudgetExceededException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The in-memory path of the daily token budget (no Redis). The Redis path is in RedisScriptsIntegrationTest. */
class UsageGuardServiceTest {

    @Test
    void reservesUpToTheIdentityBudgetThenRejects() {
        UsageGuardService guard = guard(1_000, 0);

        guard.reserve("alice", 600);
        guard.reserve("alice", 400);

        assertThrows(UsageBudgetExceededException.class, () -> guard.reserve("alice", 1));
        assertFalse(guard.isWithinBudget("alice"));
        assertTrue(guard.isWithinBudget("bob"));
    }

    @Test
    void rejectedReservationBooksNothing() {
        UsageGuardService guard = guard(1_000, 0);
        guard.reserve("alice", 900);

        assertThrows(UsageBudgetExceededException.class, () -> guard.reserve("alice", 200));

        assertDoesNotThrow(() -> guard.reserve("alice", 100));
    }

    @Test
    void globalBudgetIsSharedAcrossIdentities() {
        UsageGuardService guard = guard(1_000, 1_500);
        guard.reserve("alice", 1_000);

        assertThrows(UsageBudgetExceededException.class, () -> guard.reserve("bob", 600));
        guard.reserve("bob", 500);
        assertFalse(guard.isWithinBudget("carol"));
    }

    @Test
    void negativeAdjustmentReleasesAReservation() {
        UsageGuardService guard = guard(1_000, 1_000);
        guard.reserve("alice", 1_000);

        guard.adjust("alice", -1_000);

        assertTrue(guard.isWithinBudget("alice"));
        assertDoesNotThrow(() -> guard.reserve("alice", 1_000));
    }

    @Test
    void recordedUsageCountsAgainstTheBudget() {
        UsageGuardService guard = guard(1_000, 0);

        guard.recordUsage("alice", 700, 300);

        assertFalse(guard.isWithinBudget("alice"));
        assertThrows(UsageBudgetExceededException.class, () -> guard.reserve("alice", 1));
    }

    @Test
    void zeroBudgetsAreUnlimited() {
        UsageGuardService guard = guard(0, 0);

        guard.reserve("alice", 10_000_000);
        guard.recordUsage("alice", 5_000_000, 5_000_000);

        assertTrue(guard.isWithinBudget("alice"));
        assertDoesNotThrow(() -> guard.reserve("alice", 10_000_000));
    }

    @Test
    void nonPositiveReservationIsANoOp() {
        UsageGuardService guard = guard(1, 1);

        guard.reserve("alice", 0);
        guard.reserve("alice", -5);

        assertTrue(guard.isWithinBudget("alice"));
    }

    static UsageGuardService guard(long dailyBudget, long globalBudget) {
        return new UsageGuardService(FakeRedis.none(), props(dailyBudget, globalBudget));
    }

    static AppProperties props(long dailyBudget, long globalBudget) {
        AppProperties.Usage usage = new AppProperties.Usage(dailyBudget, globalBudget, 0, 0, null, null);
        return new AppProperties(null, List.of(), null, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                false, null, null, null, usage, null, null, null);
    }
}
