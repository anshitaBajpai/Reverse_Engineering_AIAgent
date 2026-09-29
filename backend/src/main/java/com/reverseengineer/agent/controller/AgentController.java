package com.reverseengineer.agent.controller;

import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.exception.UsageBudgetExceededException;
import com.reverseengineer.agent.model.*;
import com.reverseengineer.agent.security.CurrentUser;
import com.reverseengineer.agent.service.*;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.*;

@RestController
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1f\\x7f]+");
    /** Upper bound for one streamed response; a document chain can take several minutes. */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);

    private final RagService ragService;
    private final AppProperties props;
    private final RateLimiterService rateLimiter;
    private final GitHubService gitHub;
    private final ProjectRegistry registry;
    private final AsyncJobService asyncJobs;
    private final UsageGuardService usageGuard;
    private final UserQuotaService userQuota;
    /** Runs streamed questions/documents off the request thread; each blocks on OpenAI. */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AgentController(RagService ragService,
                           AppProperties props,
                           RateLimiterService rateLimiter,
                           GitHubService gitHub,
                           ProjectRegistry registry,
                           AsyncJobService asyncJobs,
                           UsageGuardService usageGuard,
                           UserQuotaService userQuota) {
        this.ragService   = ragService;
        this.props        = props;
        this.rateLimiter  = rateLimiter;
        this.gitHub       = gitHub;
        this.registry     = registry;
        this.asyncJobs    = asyncJobs;
        this.usageGuard   = usageGuard;
        this.userQuota    = userQuota;
    }

    @PreDestroy
    void close() {
        streamExecutor.close();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(302).location(URI.create("/health")).build();
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest body) {
        long ownerId = CurrentUser.id();
        String identity = CurrentUser.identity();
        checkRateLimit(identity, RateLimiterService.Endpoint.INGEST);
        checkUsageBudget(identity);
        log.info(">>> CONTROLLER: ingest called for {} by {}", body.repoUrl(), identity);
        try {
            Map<String, Object> result = ragService.ingestRepo(body.repoUrl(), identity, ownerId);
            return ResponseEntity.ok(new IngestResponse(
                    "Repository ingested successfully.",
                    (String) result.get("project_id"),
                    (String) result.get("commit_sha"),
                    (int)    result.get("files_loaded"),
                    (int)    result.get("chunks_created")
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("Ingestion failed", e);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR,
                    "Repository ingestion failed.");
        }
    }

    @PostMapping("/ingest/async")
    public ResponseEntity<AsyncJobInfo> ingestAsync(@Valid @RequestBody IngestRequest body) {
        long ownerId = CurrentUser.id();
        String identity = CurrentUser.identity();
        checkRateLimit(identity, RateLimiterService.Endpoint.INGEST);
        checkUsageBudget(identity);
        String repoUrl = body.repoUrl();
        AsyncJobInfo job = asyncJobs.submit("ingest", ownerId, () -> {
            try {
                return ragService.ingestRepo(repoUrl, identity, ownerId);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("Repository ingestion failed.", e);
            }
        });
        return ResponseEntity.accepted()
                .location(URI.create("/jobs/" + job.jobId()))
                .body(job);
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<AsyncJobInfo> jobStatus(@PathVariable String jobId) {
        long ownerId = CurrentUser.id();
        AsyncJobInfo job = asyncJobs.findByIdForOwner(jobId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Job '" + jobId + "' not found."));
        return ResponseEntity.ok(job);
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest body) {
        long ownerId = CurrentUser.id();
        String identity = CurrentUser.identity();
        int k = checkQuery(body, identity);

        userQuota.reserveQuery(ownerId);
        try {
            Map<String, Object> result = ragService.askQuestion(
                    body.question(), k, body.projectIds(), identity, ownerId);
            return ResponseEntity.ok(toQueryResponse(result));
        } catch (IllegalArgumentException e) {
            userQuota.refundQuery(ownerId);
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        } catch (UsageBudgetExceededException e) {
            userQuota.refundQuery(ownerId);
            throw new ResponseStatusException(TOO_MANY_REQUESTS, e.getMessage());
        } catch (RuntimeException e) {
            userQuota.refundQuery(ownerId);
            log.error("Query failed", e);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Query failed.");
        }
    }

    @PostMapping("/document")
    public ResponseEntity<DocumentResponse> document(@Valid @RequestBody DocumentRequest body) {
        long ownerId = CurrentUser.id();
        String identity = CurrentUser.identity();
        String projectName = checkDocument(body, identity);
        int k = Math.min(body.k(), props.maxDocumentK());

        userQuota.reserveDocument(ownerId);
        try {
            Map<String, Object> result = ragService.generateDocument(
                    projectName, k, body.projectIds(), identity, ownerId);
            return ResponseEntity.ok(toDocumentResponse(result));
        } catch (IllegalArgumentException e) {
            userQuota.refundDocument(ownerId);
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        } catch (UsageBudgetExceededException e) {
            userQuota.refundDocument(ownerId);
            throw new ResponseStatusException(TOO_MANY_REQUESTS, e.getMessage());
        } catch (RuntimeException e) {
            userQuota.refundDocument(ownerId);
            log.error("Document generation failed", e);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR,
                    "Document generation failed.");
        }
    }

    /**
     * {@code /query} as server-sent events: the answer arrives as it is written.
     * Validation, rate limits and quota are checked up front, so those failures
     * are ordinary JSON error responses; later failures arrive as an {@code error}
     * event. See {@link SseResponseStream} for the event format.
     */
    @PostMapping("/query/stream")
    public SseEmitter queryStream(@Valid @RequestBody QueryRequest body, HttpServletResponse response) {
        long ownerId = CurrentUser.id();
        String identity = CurrentUser.identity();
        int k = checkQuery(body, identity);

        userQuota.reserveQuery(ownerId);
        return stream(response, "Query", () -> userQuota.refundQuery(ownerId), listener ->
                toQueryResponse(ragService.askQuestion(
                        body.question(), k, body.projectIds(), identity, ownerId, listener)));
    }

    /** {@code /document} as server-sent events; see {@link #queryStream}. */
    @PostMapping("/document/stream")
    public SseEmitter documentStream(@Valid @RequestBody DocumentRequest body, HttpServletResponse response) {
        long ownerId = CurrentUser.id();
        String identity = CurrentUser.identity();
        String projectName = checkDocument(body, identity);
        int k = Math.min(body.k(), props.maxDocumentK());

        userQuota.reserveDocument(ownerId);
        return stream(response, "Document generation", () -> userQuota.refundDocument(ownerId), listener ->
                toDocumentResponse(ragService.generateDocument(
                        projectName, k, body.projectIds(), identity, ownerId, listener)));
    }

    /**
     * Runs {@code work} on a background thread, streaming its progress. The quota
     * slot is refunded on failure, and on disconnect only if no text had reached
     * the client yet (otherwise the tokens were already spent on the user's behalf).
     */
    private SseEmitter stream(HttpServletResponse response, String label, Runnable refund,
                              Function<SseResponseStream, Object> work) {
        // Keep reverse proxies (nginx, Render) from buffering the stream.
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        SseResponseStream events = new SseResponseStream(emitter);
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        streamExecutor.execute(() -> {
            if (mdc != null) MDC.setContextMap(mdc);
            try {
                events.done(work.apply(events));
            } catch (SseResponseStream.ClientGoneException e) {
                log.info("{} stream abandoned by client.", label);
                if (!events.hasSentText()) refund.run();
            } catch (IllegalArgumentException | UsageBudgetExceededException e) {
                refund.run();
                events.fail(e.getMessage());
            } catch (RuntimeException e) {
                refund.run();
                log.error("{} failed", label, e);
                events.fail(label + " failed.");
            } finally {
                MDC.clear();
            }
        });
        return emitter;
    }

    /** Rate limit, budget and length checks for a question; returns the effective k. */
    private int checkQuery(QueryRequest body, String identity) {
        checkRateLimit(identity, RateLimiterService.Endpoint.QUERY);
        checkUsageBudget(identity);
        if (body.question().length() > props.maxQuestionLength()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "question exceeds maximum length of " + props.maxQuestionLength());
        }
        return Math.min(body.k(), props.maxQueryK());
    }

    /** Rate limit and budget checks for a document; returns the sanitized project name. */
    private String checkDocument(DocumentRequest body, String identity) {
        checkRateLimit(identity, RateLimiterService.Endpoint.DOCUMENT);
        checkUsageBudget(identity);

        String projectName = CONTROL_CHARS.matcher(body.projectName()).replaceAll(" ")
                .replaceAll(" {2,}", " ").strip();
        if (projectName.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "project_name must contain printable characters.");
        }
        if (projectName.length() > props.maxProjectNameLength()) {
            projectName = projectName.substring(0, props.maxProjectNameLength());
        }
        return projectName;
    }

    @SuppressWarnings("unchecked")
    private static QueryResponse toQueryResponse(Map<String, Object> result) {
        return new QueryResponse(
                (String) result.get("answer"),
                (List<String>) result.get("sources"),
                (List<Map<String, Object>>) result.getOrDefault("citations", List.of()));
    }

    @SuppressWarnings("unchecked")
    private static DocumentResponse toDocumentResponse(Map<String, Object> result) {
        return new DocumentResponse(
                (String) result.get("document"),
                (List<Map<String, String>>) result.get("chain_steps"),
                (List<String>) result.get("sources"),
                (List<Map<String, Object>>) result.getOrDefault("citations", List.of()));
    }

    @GetMapping("/projects")
    public ResponseEntity<List<ProjectInfo>> listProjects() {
        return ResponseEntity.ok(ragService.listProjects(CurrentUser.id()));
    }

    @DeleteMapping("/projects/{projectId}")
    public ResponseEntity<Map<String, String>> deleteProject(@PathVariable String projectId) {
        long ownerId = CurrentUser.id();
        checkRateLimit(CurrentUser.identity(), RateLimiterService.Endpoint.INGEST);
        boolean removed = ragService.deleteProject(projectId, ownerId);
        if (!removed) {
            throw new ResponseStatusException(NOT_FOUND,
                    "Project '" + projectId + "' not found.");
        }
        return ResponseEntity.ok(
                Map.of("message", "Project '" + projectId + "' deleted."));
    }

    @GetMapping("/projects/{projectId}/status")
    public ResponseEntity<StatusResponse> projectStatus(@PathVariable String projectId) {
        long ownerId = CurrentUser.id();
        checkRateLimit(CurrentUser.identity(), RateLimiterService.Endpoint.QUERY);

        var info = registry.findByIdForOwner(projectId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Project '" + projectId + "' not found."));

        GitHubService.RepoStatus ghStatus = gitHub.getStatus(info.repoUrl());

        boolean hasNewCommits = info.lastCommitSha() != null
                && ghStatus.latestCommitSha() != null
                && !info.lastCommitSha().equals(ghStatus.latestCommitSha());

        var githubInfo = new StatusResponse.GitHubInfo(
                ghStatus.defaultBranch(),
                ghStatus.latestCommitSha(),
                hasNewCommits,
                ghStatus.openPrCount(),
                ghStatus.branchCount(),
                ghStatus.pushedAt()
        );

        return ResponseEntity.ok(new StatusResponse(
                info.projectId(),
                info.repoUrl(),
                info.ingestedAt(),
                info.lastCommitSha(),
                info.filesLoaded(),
                info.chunksCreated(),
                githubInfo
        ));
    }

    @PostMapping("/projects/{projectId}/refresh")
    public ResponseEntity<Map<String, Object>> refreshProject(@PathVariable String projectId) {
        long ownerId = CurrentUser.id();
        String identity = CurrentUser.identity();
        checkRateLimit(identity, RateLimiterService.Endpoint.INGEST);
        checkUsageBudget(identity);

        var info = registry.findByIdForOwner(projectId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Project '" + projectId + "' not found."));

        GitHubService.RepoStatus ghStatus = gitHub.getStatus(info.repoUrl());
        if (ghStatus.latestCommitSha() == null) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Could not determine latest GitHub commit for project '" + projectId + "'.");
        }

        boolean hasNew = info.lastCommitSha() != null
                && ghStatus.latestCommitSha() != null
                && !info.lastCommitSha().equals(ghStatus.latestCommitSha());

        if (!hasNew) {
            return ResponseEntity.ok(Map.of(
                    "refreshed", false,
                    "message", "Already up-to-date.",
                    "commit_sha", info.lastCommitSha() != null ? info.lastCommitSha() : ""));
        }

        try {
            Map<String, Object> result = ragService.ingestRepo(info.repoUrl(), identity, ownerId);
            gitHub.invalidateStatus(info.repoUrl());
            return ResponseEntity.ok(Map.of(
                    "refreshed",      true,
                    "message",        "Re-ingested successfully.",
                    "project_id",     result.get("project_id"),
                    "commit_sha",     result.get("commit_sha"),
                    "files_loaded",   result.get("files_loaded"),
                    "chunks_created", result.get("chunks_created")
            ));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("Refresh failed for project '{}'", projectId, e);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR,
                    "Refresh failed for project '" + projectId + "'.");
        }
    }

    private void checkRateLimit(String identity, RateLimiterService.Endpoint endpoint) {
        if (!rateLimiter.isAllowed(identity, endpoint)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Please try again later.");
        }
    }

    private void checkUsageBudget(String identity) {
        if (!usageGuard.isWithinBudget(identity)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                    "Daily OpenAI usage budget exceeded. Please try again tomorrow.");
        }
    }
}
