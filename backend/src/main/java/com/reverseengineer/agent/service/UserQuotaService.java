package com.reverseengineer.agent.service;

import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.exception.QuotaExceededException;
import com.reverseengineer.agent.model.QuotaSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Enforces the per-user, per-day caps on the token-expensive endpoints.
 *
 * <p>Each user may run {@code /query} and {@code /document} a limited number of
 * times per UTC day (default 2 each); the counters live in {@code users} next to
 * {@code usage_period_date}, the day they belong to. The first request of a new
 * day resets both counters before the limit is applied.
 *
 * <p>A slot is <em>reserved</em> with a single atomic conditional UPDATE before
 * the LLM call (so two concurrent requests cannot both slip through) and
 * <em>refunded</em> if that call fails. A configured limit of {@code 0} disables
 * the cap for that endpoint.
 */
@Service
public class UserQuotaService {

    private static final Logger log = LoggerFactory.getLogger(UserQuotaService.class);

    private final JdbcTemplate jdbc;
    private final AppProperties.Usage config;

    public UserQuotaService(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.config = props.usage();
    }

    /** @throws QuotaExceededException if the user has used today's allowance */
    public void reserveQuery(long userId) {
        reserve(userId, "queries_used", "documents_used", config.queriesLimit(),
                "You have used all your questions for today. The limit resets tomorrow.");
    }

    public void refundQuery(long userId) {
        refund(userId, "queries_used");
    }

    /** @throws QuotaExceededException if the user has used today's allowance */
    public void reserveDocument(long userId) {
        reserve(userId, "documents_used", "queries_used", config.documentsLimit(),
                "You have used all your technical documents for today. The limit resets tomorrow.");
    }

    public void refundDocument(long userId) {
        refund(userId, "documents_used");
    }

    public QuotaSnapshot snapshot(long userId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return jdbc.query(
                "SELECT queries_used, documents_used, usage_period_date FROM users WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return new QuotaSnapshot(0, config.queriesLimit(),
                                0, config.documentsLimit());
                    }
                    boolean currentPeriod = today.equals(
                            rs.getObject("usage_period_date", LocalDate.class));
                    int queriesUsed   = currentPeriod ? rs.getInt("queries_used")   : 0;
                    int documentsUsed = currentPeriod ? rs.getInt("documents_used") : 0;
                    return new QuotaSnapshot(
                            queriesUsed, config.queriesLimit(),
                            documentsUsed, config.documentsLimit());
                },
                userId);
    }

    /**
     * Atomically: roll the period over to today if it is stale (zeroing both
     * counters), then bump {@code incrementedColumn} if it is still under
     * {@code limit}. {@code incrementedColumn} / {@code otherColumn} are
     * hard-coded literals, never user input.
     */
    private void reserve(long userId, String incrementedColumn, String otherColumn,
                         int limit, String message) {
        if (limit <= 0) {
            return; // unlimited
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int updated = jdbc.update(
                "UPDATE users SET "
                + incrementedColumn + " = CASE WHEN usage_period_date IS NOT DISTINCT FROM ? "
                +     "THEN " + incrementedColumn + " + 1 ELSE 1 END, "
                + otherColumn + " = CASE WHEN usage_period_date IS NOT DISTINCT FROM ? "
                +     "THEN " + otherColumn + " ELSE 0 END, "
                + "usage_period_date = ? "
                + "WHERE id = ? "
                + "AND (usage_period_date IS DISTINCT FROM ? OR " + incrementedColumn + " < ?)",
                today, today, today, userId, today, limit);
        if (updated == 0) {
            throw new QuotaExceededException(message);
        }
    }

    private void refund(long userId, String column) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            jdbc.update(
                    "UPDATE users SET " + column + " = " + column + " - 1 "
                    + "WHERE id = ? AND usage_period_date IS NOT DISTINCT FROM ? AND " + column + " > 0",
                    userId, today);
        } catch (Exception e) {
            log.warn("Could not refund {} slot for user {}: {}", column, userId, e.getMessage());
        }
    }
}
