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
 * In-memory registry of all successfully ingested projects.
 *
 * <p>State is lost on restart but is recovered by
 * {@link RagService#checkExistingData()} which queries the vector store for
 * distinct {@code project_id} metadata values on startup.
 *
 * <p>Project IDs are URL-derived slugs containing only {@code [a-z0-9-]},
 * which makes them safe to embed in Spring AI filter expressions.
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
                        (project_id, repo_url, ingested_at, last_commit_sha, files_loaded, chunks_created)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (project_id) DO UPDATE SET
                        repo_url = EXCLUDED.repo_url,
                        ingested_at = EXCLUDED.ingested_at,
                        last_commit_sha = EXCLUDED.last_commit_sha,
                        files_loaded = EXCLUDED.files_loaded,
                        chunks_created = EXCLUDED.chunks_created
                    """,
                    info.projectId(),
                    info.repoUrl(),
                    info.ingestedAt() != null ? Timestamp.from(info.ingestedAt()) : null,
                    info.lastCommitSha(),
                    info.filesLoaded(),
                    info.chunksCreated());
        } catch (Exception e) {
            log.warn("Could not persist project '{}': {}", info.projectId(), e.getMessage());
        }
    }

    public boolean remove(String projectId) {
        boolean removed = registry.remove(projectId) != null;
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM project_registry WHERE project_id = ?",
                    projectId);
            removed = removed || deleted > 0;
        } catch (Exception e) {
            log.warn("Could not delete project '{}' from registry table: {}", projectId, e.getMessage());
        }
        return removed;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Optional<ProjectInfo> findById(String projectId) {
        return Optional.ofNullable(registry.get(projectId));
    }

    public List<ProjectInfo> findAll() {
        return List.copyOf(registry.values());
    }

    public boolean isEmpty() {
        return registry.isEmpty();
    }

    private void loadFromDatabase() {
        List<ProjectInfo> projects = jdbcTemplate.query(
                "SELECT project_id, repo_url, ingested_at, last_commit_sha, files_loaded, chunks_created FROM project_registry",
                (rs, rowNum) -> new ProjectInfo(
                        rs.getString("project_id"),
                        rs.getString("repo_url"),
                        toInstant(rs.getTimestamp("ingested_at")),
                        rs.getString("last_commit_sha"),
                        rs.getInt("files_loaded"),
                        rs.getInt("chunks_created")));
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

   
    public static String toProjectId(String repoUrl) {
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
}
