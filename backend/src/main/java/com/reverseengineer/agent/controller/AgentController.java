package com.reverseengineer.agent.controller;

import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.model.*;
import com.reverseengineer.agent.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.net.URI;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.*;

@RestController
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1f\\x7f]+");

    private final RagService ragService;
    private final AppProperties props;
    private final RateLimiterService rateLimiter;
    private final GitHubService gitHub;
    private final ProjectRegistry registry;
    private final AsyncJobService asyncJobs;
    private final UsageGuardService usageGuard;

    public AgentController(RagService ragService,
                           AppProperties props,
                           RateLimiterService rateLimiter,
                           GitHubService gitHub,
                           ProjectRegistry registry,
                           AsyncJobService asyncJobs,
                           UsageGuardService usageGuard) {
        this.ragService   = ragService;
        this.props        = props;
        this.rateLimiter  = rateLimiter;
        this.gitHub       = gitHub;
        this.registry     = registry;
        this.asyncJobs    = asyncJobs;
        this.usageGuard   = usageGuard;
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
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest body,
                                                  HttpServletRequest httpReq) {
        checkRateLimit(httpReq, RateLimiterService.Endpoint.INGEST);
        log.info(">>> CONTROLLER: ingest called for {}", body.repoUrl());
        try {
            Map<String, Object> result = ragService.ingestRepo(body.repoUrl());
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
    public ResponseEntity<AsyncJobInfo> ingestAsync(@Valid @RequestBody IngestRequest body,
                                                      HttpServletRequest httpReq) {
        checkRateLimit(httpReq, RateLimiterService.Endpoint.INGEST);
        String repoUrl = body.repoUrl();
        AsyncJobInfo job = asyncJobs.submit("ingest", () -> {
            try {
                return ragService.ingestRepo(repoUrl);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
        return ResponseEntity.accepted()
                .location(URI.create("/jobs/" + job.jobId()))
                .body(job);
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<AsyncJobInfo> jobStatus(@PathVariable String jobId) {
        AsyncJobInfo job = asyncJobs.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Job '" + jobId + "' not found."));
        return ResponseEntity.ok(job);
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest body,
                                                HttpServletRequest httpReq) {
        checkRateLimit(httpReq, RateLimiterService.Endpoint.QUERY);
        String identity = getClientIp(httpReq);
        checkUsageBudget(identity);
        int k = Math.min(body.k(), props.maxQueryK());

        if (body.question().length() > props.maxQuestionLength()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "question exceeds maximum length of " + props.maxQuestionLength());
        }

        try {
            Map<String, Object> result = ragService.askQuestion(
                    body.question(), k, body.projectIds(), identity);
            @SuppressWarnings("unchecked")
            List<String> sources = (List<String>) result.get("sources");
            return ResponseEntity.ok(new QueryResponse(
                    (String) result.get("answer"), sources));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Query failed", e);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Query failed.");
        }
    }

    @PostMapping("/document")
    public ResponseEntity<DocumentResponse> document(@Valid @RequestBody DocumentRequest body,
                                                      HttpServletRequest httpReq) {
        checkRateLimit(httpReq, RateLimiterService.Endpoint.DOCUMENT);
        String identity = getClientIp(httpReq);
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

        int k = Math.min(body.k(), props.maxDocumentK());

        try {
            Map<String, Object> result = ragService.generateDocument(
                    projectName, k, body.projectIds(), identity);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> chainSteps =
                    (List<Map<String, String>>) result.get("chain_steps");
            @SuppressWarnings("unchecked")
            List<String> sources = (List<String>) result.get("sources");
            return ResponseEntity.ok(new DocumentResponse(
                    (String) result.get("document"), chainSteps, sources));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Document generation failed", e);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR,
                    "Document generation failed.");
        }
    }

    @GetMapping("/projects")
    public ResponseEntity<List<ProjectInfo>> listProjects() {
        return ResponseEntity.ok(ragService.listProjects());
    }

    @DeleteMapping("/projects/{projectId}")
    public ResponseEntity<Map<String, String>> deleteProject(
            @PathVariable String projectId,
            HttpServletRequest httpReq) {

        checkRateLimit(httpReq, RateLimiterService.Endpoint.INGEST);
        boolean removed = ragService.deleteProject(projectId);
        if (!removed) {
            throw new ResponseStatusException(NOT_FOUND,
                    "Project '" + projectId + "' not found.");
        }
        return ResponseEntity.ok(
                Map.of("message", "Project '" + projectId + "' deleted."));
    }

    @GetMapping("/projects/{projectId}/status")
    public ResponseEntity<StatusResponse> projectStatus(
            @PathVariable String projectId,
            HttpServletRequest httpReq) {

        checkRateLimit(httpReq, RateLimiterService.Endpoint.QUERY);

        var info = registry.findById(projectId)
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
    public ResponseEntity<Map<String, Object>> refreshProject(
            @PathVariable String projectId,
            HttpServletRequest httpReq) {

        checkRateLimit(httpReq, RateLimiterService.Endpoint.INGEST);

        var info = registry.findById(projectId)
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
            Map<String, Object> result = ragService.ingestRepo(info.repoUrl());
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

    private void checkRateLimit(HttpServletRequest req, RateLimiterService.Endpoint endpoint) {
        if (!rateLimiter.isAllowed(getClientIp(req), endpoint)) {
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

    private static String getClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (isTrustedProxy(req.getRemoteAddr()) && forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return req.getRemoteAddr();
    }

    private static boolean isTrustedProxy(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::1".equals(remoteAddr);
    }
}


