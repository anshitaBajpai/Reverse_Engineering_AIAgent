package com.reverseengineer.agent.service;

import com.reverseengineer.agent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);
    private static final long STATUS_CACHE_TTL_MS = 60_000L;

    private final RestClient restClient;
    private final ConcurrentHashMap<String, CachedStatus> statusCache = new ConcurrentHashMap<>();

    public GitHubService(AppProperties props) {
        var builder = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");

        String token = props.githubToken();
        if (token != null && !token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
            log.info("GitHub integration: authenticated (token present).");
        } else {
            log.info("GitHub integration: unauthenticated (60 req/hr limit). "
                    + "Set GITHUB_TOKEN to raise the limit.");
        }

        this.restClient = builder.build();
    }

    public record RepoStatus(
            String defaultBranch,
            String latestCommitSha,
            int openPrCount,
            int branchCount,
            String pushedAt
    ) {
        static RepoStatus unknown() {
            return new RepoStatus(null, null, -1, -1, null);
        }
    }

    private record CachedStatus(RepoStatus status, long expiresAtMillis) {}

    public RepoStatus getStatus(String repoUrl) {
        CachedStatus cached = statusCache.get(repoUrl);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.status();
        }

        try {
            String[] coords = parseCoords(repoUrl);
            String owner = coords[0], repo = coords[1];

            @SuppressWarnings("unchecked")
            Map<String, Object> repoInfo = restClient.get()
                    .uri("/repos/{o}/{r}", owner, repo)
                    .retrieve()
                    .body(Map.class);

            String defaultBranch = repoInfo != null
                    ? (String) repoInfo.getOrDefault("default_branch", "main") : "main";
            String pushedAt = repoInfo != null ? (String) repoInfo.get("pushed_at") : null;

            List<Map<String, Object>> commits = restClient.get()
                    .uri("/repos/{o}/{r}/commits?per_page=1&sha={b}", owner, repo, defaultBranch)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            String latestSha = (commits != null && !commits.isEmpty())
                    ? (String) commits.get(0).get("sha") : null;

            List<?> prs = restClient.get()
                    .uri("/repos/{o}/{r}/pulls?state=open&per_page=100", owner, repo)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            int prCount = prs != null ? prs.size() : 0;

            List<?> branches = restClient.get()
                    .uri("/repos/{o}/{r}/branches?per_page=100", owner, repo)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            int branchCount = branches != null ? branches.size() : 0;

            RepoStatus status = new RepoStatus(defaultBranch, latestSha, prCount, branchCount, pushedAt);
            statusCache.put(repoUrl, new CachedStatus(status, now + STATUS_CACHE_TTL_MS));
            return status;

        } catch (Exception e) {
            log.warn("GitHub API unavailable for {}: {}", repoUrl, e.getMessage());
            return RepoStatus.unknown();
        }
    }

    static String[] parseCoords(String repoUrl) {
        String path = URI.create(repoUrl).getPath();
        String[] parts = path.replaceAll("^/+", "").split("/", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "Cannot parse owner/repo from URL: " + repoUrl);
        }
        String repoName = parts[1].replaceAll("\\.git$", "");
        return new String[]{parts[0], repoName};
    }
}
