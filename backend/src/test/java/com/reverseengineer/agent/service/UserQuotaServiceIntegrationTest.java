package com.reverseengineer.agent.service;

import com.reverseengineer.agent.RequiresDocker;
import com.reverseengineer.agent.TestcontainersConfiguration;
import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.exception.QuotaExceededException;
import com.reverseengineer.agent.model.QuotaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the conditional UPDATEs behind the per-user daily caps against real Postgres (needs Docker). */
@RequiresDocker
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UserQuotaServiceIntegrationTest {

    private static final int QUERY_LIMIT = 3;
    private static final int DOCUMENT_LIMIT = 2;

    @Autowired
    private JdbcTemplate jdbc;

    private UserQuotaService quota;
    private long userId;

    @BeforeEach
    void setUp() {
        quota = quotaWithLimits(QUERY_LIMIT, DOCUMENT_LIMIT);
        userId = jdbc.queryForObject(
                "INSERT INTO users (username, password_hash) VALUES (?, 'x') RETURNING id",
                Long.class, "quota-" + UUID.randomUUID());
    }

    @Test
    void reservesUpToTheLimitThenRejects() {
        for (int i = 0; i < QUERY_LIMIT; i++) {
            quota.reserveQuery(userId);
        }

        assertThrows(QuotaExceededException.class, () -> quota.reserveQuery(userId));
        QuotaSnapshot snapshot = quota.snapshot(userId);
        assertEquals(QUERY_LIMIT, snapshot.queriesUsed());
        assertTrue(snapshot.queriesExhausted());
    }

    @Test
    void queryAndDocumentCountersAreIndependent() {
        for (int i = 0; i < DOCUMENT_LIMIT; i++) {
            quota.reserveDocument(userId);
        }
        assertThrows(QuotaExceededException.class, () -> quota.reserveDocument(userId));

        quota.reserveQuery(userId);

        assertEquals(new QuotaSnapshot(1, QUERY_LIMIT, DOCUMENT_LIMIT, DOCUMENT_LIMIT),
                quota.snapshot(userId));
    }

    @Test
    void refundFreesASlot() {
        for (int i = 0; i < QUERY_LIMIT; i++) {
            quota.reserveQuery(userId);
        }

        quota.refundQuery(userId);

        assertEquals(QUERY_LIMIT - 1, quota.snapshot(userId).queriesUsed());
        assertDoesNotThrow(() -> quota.reserveQuery(userId));
    }

    @Test
    void refundNeverGoesBelowZero() {
        quota.refundDocument(userId);
        quota.reserveDocument(userId);
        quota.refundDocument(userId);
        quota.refundDocument(userId);

        assertEquals(0, quota.snapshot(userId).documentsUsed());
    }

    @Test
    void refundDoesNotTouchAnEarlierDay() {
        setCounters(LocalDate.now(ZoneOffset.UTC).minusDays(1), 2, 1);

        quota.refundQuery(userId);

        assertEquals(2, jdbc.queryForObject("SELECT queries_used FROM users WHERE id = ?", Integer.class, userId));
    }

    @Test
    void newDayResetsBothCounters() {
        setCounters(LocalDate.now(ZoneOffset.UTC).minusDays(1), QUERY_LIMIT, DOCUMENT_LIMIT);

        quota.reserveQuery(userId);

        assertEquals(new QuotaSnapshot(1, QUERY_LIMIT, 0, DOCUMENT_LIMIT), quota.snapshot(userId));
        assertDoesNotThrow(() -> quota.reserveDocument(userId));
    }

    @Test
    void snapshotIgnoresCountersFromAnEarlierDay() {
        setCounters(LocalDate.now(ZoneOffset.UTC).minusDays(1), QUERY_LIMIT, DOCUMENT_LIMIT);

        QuotaSnapshot snapshot = quota.snapshot(userId);

        assertEquals(0, snapshot.queriesUsed());
        assertEquals(0, snapshot.documentsUsed());
        assertFalse(snapshot.queriesExhausted());
    }

    @Test
    void zeroLimitIsUnlimited() {
        UserQuotaService unlimited = quotaWithLimits(0, 0);

        for (int i = 0; i < 50; i++) {
            unlimited.reserveQuery(userId);
            unlimited.reserveDocument(userId);
        }

        QuotaSnapshot snapshot = unlimited.snapshot(userId);
        assertFalse(snapshot.queriesExhausted());
        assertFalse(snapshot.documentsExhausted());
    }

    @Test
    void concurrentReservationsCannotExceedTheLimit() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        quota.reserveQuery(userId);
                        return true;
                    } catch (QuotaExceededException e) {
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
            assertEquals(QUERY_LIMIT, granted);
            assertEquals(QUERY_LIMIT, quota.snapshot(userId).queriesUsed());
        } finally {
            pool.shutdownNow();
        }
    }

    private void setCounters(LocalDate period, int queriesUsed, int documentsUsed) {
        jdbc.update("UPDATE users SET usage_period_date = ?, queries_used = ?, documents_used = ? WHERE id = ?",
                period, queriesUsed, documentsUsed, userId);
    }

    private UserQuotaService quotaWithLimits(int queries, int documents) {
        AppProperties.Usage usage = new AppProperties.Usage(0, 0, 0, 0, queries, documents);
        return new UserQuotaService(jdbc, new AppProperties(null, List.of(), null, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                false, null, null, null, usage, null, null, null));
    }
}
