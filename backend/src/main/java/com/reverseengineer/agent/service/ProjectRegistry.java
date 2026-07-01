package com.reverseengineer.agent.service;

import com.reverseengineer.agent.model.ProjectInfo;
import org.springframework.stereotype.Service;

import java.net.URI;
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

    private final ConcurrentHashMap<String, ProjectInfo> registry = new ConcurrentHashMap<>();

    // ── Write ─────────────────────────────────────────────────────────────────

    public void register(ProjectInfo info) {
        registry.put(info.projectId(), info);
    }

    public boolean remove(String projectId) {
        return registry.remove(projectId) != null;
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
