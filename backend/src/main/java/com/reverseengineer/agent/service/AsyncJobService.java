package com.reverseengineer.agent.service;

import com.reverseengineer.agent.model.AsyncJobInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

@Service
public class AsyncJobService {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobService.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, AsyncJobInfo> jobs = new ConcurrentHashMap<>();
    /** jobId -> id of the user who submitted it, so status reads stay owner-scoped. */
    private final Map<String, Long> jobOwners = new ConcurrentHashMap<>();

    public AsyncJobInfo submit(String type, Long ownerId, Supplier<Object> task) {
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AsyncJobInfo pending = new AsyncJobInfo(jobId, type, "PENDING", now, now, null, null);
        jobs.put(jobId, pending);
        if (ownerId != null) {
            jobOwners.put(jobId, ownerId);
        }

        CompletableFuture.runAsync(() -> runJob(jobId, type, task), executor);
        return pending;
    }

    /** Returns the job only when it was submitted by {@code ownerId}; empty otherwise (or if unknown). */
    public Optional<AsyncJobInfo> findByIdForOwner(String jobId, Long ownerId) {
        if (!Objects.equals(jobOwners.get(jobId), ownerId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(jobs.get(jobId));
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
        AsyncJobInfo current = jobs.get(jobId);
        Instant createdAt = current != null ? current.createdAt() : Instant.now();
        jobs.put(jobId, new AsyncJobInfo(
                jobId,
                type,
                status,
                createdAt,
                Instant.now(),
                result,
                error));
    }
}
