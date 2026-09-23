package com.reverseengineer.agent.service;

import com.reverseengineer.agent.model.AsyncJobInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AsyncJobServiceTest {

    @Test
    void finishedJobIsVisibleOnlyToItsOwner() throws Exception {
        AsyncJobService service = new AsyncJobService(FakeRedis.none());

        AsyncJobInfo job = service.submit("ingest", 1L, () -> Map.of("project_id", "p"));
        AsyncJobInfo done = awaitFinished(service, job.jobId(), 1L);

        assertEquals("SUCCEEDED", done.status());
        assertEquals(Map.of("project_id", "p"), done.result());
        assertTrue(service.findByIdForOwner(job.jobId(), 2L).isEmpty());
        assertTrue(service.findByIdForOwner(job.jobId(), null).isEmpty());
    }

    @Test
    void jobIsReadableFromAnotherInstanceThroughRedis() throws Exception {
        FakeRedis redis = new FakeRedis();
        AsyncJobService runner = new AsyncJobService(redis.provider());
        AsyncJobService other = new AsyncJobService(redis.provider());

        AsyncJobInfo job = runner.submit("ingest", 7L, () -> Map.of("files_loaded", 3));
        awaitFinished(runner, job.jobId(), 7L);

        AsyncJobInfo seen = other.findByIdForOwner(job.jobId(), 7L).orElseThrow();
        assertEquals("SUCCEEDED", seen.status());
        assertEquals(Map.of("files_loaded", 3), seen.result());
        assertEquals(job.createdAt(), seen.createdAt());
        assertTrue(other.findByIdForOwner(job.jobId(), 8L).isEmpty());
        assertEquals(Duration.ofHours(1), redis.ttls.get("job:" + job.jobId()));
    }

    @Test
    void failedJobKeepsItsErrorMessage() throws Exception {
        AsyncJobService service = new AsyncJobService(FakeRedis.none());

        AsyncJobInfo job = service.submit("ingest", 1L, () -> {
            throw new IllegalStateException("already being ingested");
        });
        AsyncJobInfo done = awaitFinished(service, job.jobId(), 1L);

        assertEquals("FAILED", done.status());
        assertEquals("already being ingested", done.error());
    }

    @Test
    void jobStillWorksWhenRedisIsDown() throws Exception {
        FakeRedis redis = new FakeRedis();
        redis.down = true;
        AsyncJobService service = new AsyncJobService(redis.provider());

        AsyncJobInfo job = service.submit("ingest", 1L, () -> "ok");

        assertEquals("SUCCEEDED", awaitFinished(service, job.jobId(), 1L).status());
    }

    @Test
    void expiryRules() {
        Instant now = Instant.now();

        assertFalse(AsyncJobService.isExpired(job("RUNNING", now.minusSeconds(3 * 3600), now.minusSeconds(3 * 3600)), now));
        assertTrue(AsyncJobService.isExpired(job("RUNNING", now.minusSeconds(7 * 3600), now.minusSeconds(7 * 3600)), now));
        assertFalse(AsyncJobService.isExpired(job("SUCCEEDED", now.minusSeconds(3600), now.minusSeconds(30 * 60)), now));
        assertTrue(AsyncJobService.isExpired(job("SUCCEEDED", now.minusSeconds(2 * 3600), now.minusSeconds(61 * 60)), now));
        assertTrue(AsyncJobService.isExpired(job("FAILED", now.minusSeconds(2 * 3600), now.minusSeconds(61 * 60)), now));
    }

    private static AsyncJobInfo job(String status, Instant createdAt, Instant updatedAt) {
        return new AsyncJobInfo("id", "ingest", status, createdAt, updatedAt, null, null);
    }

    private static AsyncJobInfo awaitFinished(AsyncJobService service, String jobId, Long ownerId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            AsyncJobInfo job = service.findByIdForOwner(jobId, ownerId).orElseThrow();
            if (job.status().equals("SUCCEEDED") || job.status().equals("FAILED")) {
                return job;
            }
            Thread.sleep(10);
        }
        return fail("job " + jobId + " did not finish");
    }
}
