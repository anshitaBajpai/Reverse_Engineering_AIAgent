package com.reverseengineer.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.reverseengineer.agent.model.AsyncJobInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Runs background jobs and keeps their status readable for a bounded time.
 *
 * <p>Each job is held in memory on the instance that runs it and mirrored to
 * Redis (when available), so a status poll that lands on another instance, or
 * arrives after a restart, still finds it. Both copies expire: finished jobs
 * after {@link #FINISHED_RETENTION}, and any job after {@link #MAX_JOB_AGE}.</p>
 */
@Service
public class AsyncJobService {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobService.class);

    /** How long a finished job's status and result stay readable. */
    private static final Duration FINISHED_RETENTION = Duration.ofHours(1);
    /** Upper bound for any job, including one whose instance died mid-run. */
    private static final Duration MAX_JOB_AGE = Duration.ofHours(6);
    private static final String REDIS_PREFIX = "job:";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, StoredJob> jobs = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    /** A job plus the id of the user who submitted it, so status reads stay owner-scoped. */
    record StoredJob(Long ownerId, AsyncJobInfo job) {}

    public AsyncJobService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    public AsyncJobInfo submit(String type, Long ownerId, Supplier<Object> task) {
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AsyncJobInfo pending = new AsyncJobInfo(jobId, type, "PENDING", now, now, null, null);
        store(new StoredJob(ownerId, pending));

        CompletableFuture.runAsync(() -> runJob(jobId, type, task), executor);
        return pending;
    }

    /** Returns the job only when it was submitted by {@code ownerId}; empty otherwise (or if unknown). */
    public Optional<AsyncJobInfo> findByIdForOwner(String jobId, Long ownerId) {
        StoredJob stored = jobs.get(jobId);
        if (stored == null) {
            stored = loadFromRedis(jobId);
        }
        if (stored == null || !Objects.equals(stored.ownerId(), ownerId)) {
            return Optional.empty();
        }
        return Optional.of(stored.job());
    }

    /** Drops expired jobs from memory; Redis expires its copies on its own. */
    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
    public void sweepExpiredJobs() {
        Instant now = Instant.now();
        int before = jobs.size();
        jobs.values().removeIf(stored -> isExpired(stored.job(), now));
        int removed = before - jobs.size();
        if (removed > 0) {
            log.info("Async job sweep removed {} expired job(s).", removed);
        }
    }

    @PreDestroy
    void close() {
        executor.close();
    }

    private void runJob(String jobId, String type, Supplier<Object> task) {
        mark(jobId, type, "RUNNING", null, null);
        try {
            Object result = task.get();
            mark(jobId, type, "SUCCEEDED", result, null);
        } catch (Exception e) {
            log.error("Async job '{}' of type '{}' failed", jobId, type, e);
            mark(jobId, type, "FAILED", null,
                    e.getMessage() != null ? e.getMessage() : "Job failed");
        }
    }

    private void mark(String jobId, String type, String status, Object result, String error) {
        StoredJob current = jobs.get(jobId);
        Instant createdAt = current != null ? current.job().createdAt() : Instant.now();
        Long ownerId = current != null ? current.ownerId() : null;
        store(new StoredJob(ownerId, new AsyncJobInfo(
                jobId,
                type,
                status,
                createdAt,
                Instant.now(),
                result,
                error)));
    }

    private void store(StoredJob stored) {
        jobs.put(stored.job().jobId(), stored);
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(REDIS_PREFIX + stored.job().jobId(),
                    mapper.writeValueAsString(stored), timeToLive(stored.job()));
        } catch (Exception e) {
            log.warn("Could not mirror job '{}' to Redis; it is readable on this instance only: {}",
                    stored.job().jobId(), e.getMessage());
        }
    }

    private StoredJob loadFromRedis(String jobId) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(REDIS_PREFIX + jobId);
            return json != null ? mapper.readValue(json, StoredJob.class) : null;
        } catch (Exception e) {
            log.warn("Could not read job '{}' from Redis: {}", jobId, e.getMessage());
            return null;
        }
    }

    private static boolean isFinished(AsyncJobInfo job) {
        return "SUCCEEDED".equals(job.status()) || "FAILED".equals(job.status());
    }

    static boolean isExpired(AsyncJobInfo job, Instant now) {
        if (job.createdAt().plus(MAX_JOB_AGE).isBefore(now)) {
            return true;
        }
        return isFinished(job) && job.updatedAt().plus(FINISHED_RETENTION).isBefore(now);
    }

    private static Duration timeToLive(AsyncJobInfo job) {
        if (isFinished(job)) {
            return FINISHED_RETENTION;
        }
        Duration remaining = Duration.between(Instant.now(), job.createdAt().plus(MAX_JOB_AGE));
        return remaining.isNegative() || remaining.isZero() ? Duration.ofSeconds(1) : remaining;
    }
}
