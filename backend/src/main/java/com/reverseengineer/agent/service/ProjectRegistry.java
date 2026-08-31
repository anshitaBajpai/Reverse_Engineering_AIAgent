package com.reverseengineer.agent.service;

import com.reverseengineer.agent.model.ProjectInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of all successfully ingested projects, mirrored to the
 * {@code project_registry} table.
 *
 * <p>Every project has an {@code ownerId}: the id of the user who ingested it,
 * or {@code null} for shared / MCP-originated projects. All lookups are
 * owner-scoped so one user never sees another user's repositories.
 *
 * <p>Project IDs are URL-derived slugs containing only {@code [a-z0-9-]} and,
 * for owned projects, a {@code u<id>-} prefix. That keeps them globally unique
 * and safe to embed in Spring AI filter expressions.
 */
@Service
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, ProjectInfo> registry = new ConcurrentHashMap<>();

    public ProjectRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initialize() {
        try {
            loadFromDatabase();
        } catch (Exception e) {
            log.warn("Project registry database load skipped: {}", e.getMessage());
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    public void register(ProjectInfo info) {
        registry.put(info.projectId(), info);
        try {
            jdbcTemplate.update("""
                    INSERT INTO project_registry
                        (project_id, repo_url, ingested_at, last_commit_sha, files_loaded, chunks_created, owner_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (project_id) DO UPDATE SET
                        repo_url = EXCLUDED.repo_url,
                        ingested_at = EXCLUDED.ingested_at,
                        last_commit_sha = EXCLUDED.last_commit_sha,
                        files_loaded = EXCLUDED.files_loaded,
                        chunks_created = EXCLUDED.chunks_created,
                        owner_id = EXCLUDED.owner_id
                    """,
                    info.projectId(),
                    info.repoUrl(),
                    info.ingestedAt() != null ? Timestamp.from(info.ingestedAt()) : null,
                    info.lastCommitSha(),
                    info.filesLoaded(),
                    info.chunksCreated(),
                    info.ownerId());
        } catch (Exception e) {
            log.warn("Could not persist project '{}': {}", info.projectId(), e.getMessage());
        }
    }

    public void registerRecovered(ProjectInfo info) {
        ProjectInfo existing = registry.get(info.projectId());
        if (existing != null) {
            registry.put(info.projectId(), mergeRecovered(existing, info));
        } else {
            registry.put(info.projectId(), info);
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO project_registry
                        (project_id, repo_url, ingested_at, last_commit_sha, files_loaded, chunks_created, owner_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (project_id) DO UPDATE SET
                        repo_url = EXCLUDED.repo_url
                    """,
                    info.projectId(),
                    info.repoUrl(),
                    info.ingestedAt() != null ? Timestamp.from(info.ingestedAt()) : null,
                    info.lastCommitSha(),
                    info.filesLoaded(),
                    info.chunksCreated(),
                    info.ownerId());
        } catch (Exception e) {
            log.warn("Could not persist recovered project '{}': {}", info.projectId(), e.getMessage());
        }
    }

    /** Removes the project only if it belongs to {@code ownerId} (or is shared when {@code ownerId} is null). */
    public boolean remove(String projectId, Long ownerId) {
        ProjectInfo current = registry.get(projectId);
        if (current != null && !Objects.equals(current.ownerId(), ownerId)) {
            return false;
        }
        boolean removed = registry.remove(projectId) != null;
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM project_registry WHERE project_id = ? "
                    + "AND owner_id IS NOT DISTINCT FROM ?",
                    projectId, ownerId);
            removed = removed || deleted > 0;
        } catch (Exception e) {
            log.warn("Could not delete project '{}' from registry table: {}", projectId, e.getMessage());
        }
        return removed;
    }

    // ── Read (owner-scoped) ───────────────────────────────────────────────────

    public Optional<ProjectInfo> findByIdForOwner(String projectId, Long ownerId) {
        return Optional.ofNullable(registry.get(projectId))
                .filter(info -> Objects.equals(info.ownerId(), ownerId));
    }

    public List<ProjectInfo> findAllForOwner(Long ownerId) {
        return registry.values().stream()
                .filter(info -> Objects.equals(info.ownerId(), ownerId))
                .toList();
    }

    public List<ProjectInfo> findByIdsForOwner(List<String> projectIds, Long ownerId) {
        if (projectIds == null || projectIds.isEmpty()) {
            return findAllForOwner(ownerId);
        }
        return projectIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(registry::get)
                .filter(Objects::nonNull)
                .filter(info -> Objects.equals(info.ownerId(), ownerId))
                .toList();
    }

    public boolean isEmptyForOwner(Long ownerId) {
        return registry.values().stream()
                .noneMatch(info -> Objects.equals(info.ownerId(), ownerId));
    }

    /** Total ingested projects across every owner. */
    public int totalCount() {
        return registry.size();
    }

    /** Whether any owner has a project with this exact id (used by clone cleanup). */
    public boolean isKnownProjectId(String projectId) {
        return projectId != null && registry.containsKey(projectId);
    }

    // ── ID derivation ────────────────────────────────────────────────────────

    /** Owner-scoped project id: {@code u<ownerId>-<slug>}, or the bare slug for shared/MCP projects. */
    public static String toProjectId(String repoUrl, Long ownerId) {
        String slug = slugify(repoUrl);
        return ownerId != null ? "u" + ownerId + "-" + slug : slug;
    }

    private static String slugify(String repoUrl) {
        try {
            URI uri = URI.create(repoUrl);
            String path = uri.getHost() + uri.getPath();
            return path.toLowerCase()
                    .replaceAll("\\.git$", "")
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void loadFromDatabase() {
        List<ProjectInfo> projects = jdbcTemplate.query(
                "SELECT project_id, repo_url, ingested_at, last_commit_sha, files_loaded, chunks_created, owner_id "
                + "FROM project_registry",
                (rs, rowNum) -> {
                    long owner = rs.getLong("owner_id");
                    return new ProjectInfo(
                            rs.getString("project_id"),
                            rs.getString("repo_url"),
                            toInstant(rs.getTimestamp("ingested_at")),
                            rs.getString("last_commit_sha"),
                            rs.getInt("files_loaded"),
                            rs.getInt("chunks_created"),
                            rs.wasNull() ? null : owner);
                });
        for (ProjectInfo project : projects) {
            registry.put(project.projectId(), project);
        }
        if (!projects.isEmpty()) {
            log.info("Loaded {} project(s) from registry table.", projects.size());
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private static ProjectInfo mergeRecovered(ProjectInfo existing, ProjectInfo recovered) {
        return new ProjectInfo(
                existing.projectId(),
                recovered.repoUrl() != null ? recovered.repoUrl() : existing.repoUrl(),
                existing.ingestedAt() != null ? existing.ingestedAt() : recovered.ingestedAt(),
                existing.lastCommitSha() != null ? existing.lastCommitSha() : recovered.lastCommitSha(),
                existing.filesLoaded() > 0 ? existing.filesLoaded() : recovered.filesLoaded(),
                existing.chunksCreated() > 0 ? existing.chunksCreated() : recovered.chunksCreated(),
                existing.ownerId() != null ? existing.ownerId() : recovered.ownerId());
    }
}
