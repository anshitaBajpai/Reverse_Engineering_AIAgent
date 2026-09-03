package com.reverseengineer.agent.service;

import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.model.Chunk;
import com.reverseengineer.agent.model.CodeFile;
import com.reverseengineer.agent.model.ProjectInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RepoLoaderService repoLoader;
    private final ChunkerService chunker;
    private final LlmService llm;
    private final AppProperties props;
    private final ProjectRegistry registry;
    private final ResponseCacheService responseCache;
    private final UsageGuardService usageGuard;

    private final ReentrantLock ingestLock = new ReentrantLock();
    private static final int MIN_DOCUMENT_RETRIEVAL_K = 12;

    // Rough bytes-per-token ratio for English/source text. Used only to bill an
    // ingest's embedding calls against the daily usage budget; the embedding API
    // does not return token counts through VectorStore.add().
    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    public RagService(VectorStore vectorStore,
                      JdbcTemplate jdbcTemplate,
                      RepoLoaderService repoLoader,
                      ChunkerService chunker,
                      LlmService llm,
                      AppProperties props,
                      ProjectRegistry registry,
                      ResponseCacheService responseCache,
                      UsageGuardService usageGuard) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.repoLoader = repoLoader;
        this.chunker = chunker;
        this.llm = llm;
        this.props = props;
        this.registry = registry;
        this.responseCache = responseCache;
        this.usageGuard = usageGuard;
    }

    @PostConstruct
    void checkExistingData() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT DISTINCT metadata->>'project_id' AS project_id, " +
                    "                metadata->>'repo_url'   AS repo_url, " +
                    "                metadata->>'owner_id'   AS owner_id " +
                    "FROM   vector_store " +
                    "WHERE  metadata->>'project_id' IS NOT NULL");

            for (Map<String, Object> row : rows) {
                String projectId = (String) row.get("project_id");
                String repoUrl   = (String) row.get("repo_url");
                if (projectId != null && repoUrl != null) {
                    registry.registerRecovered(new ProjectInfo(
                            projectId, repoUrl, null, null, 0, 0,
                            ownerIdFromKey((String) row.get("owner_id"))));
                }
            }
            if (!rows.isEmpty()) {
                log.info("Recovered {} project(s) from vector store on startup.", rows.size());
            }
        } catch (Exception e) {
            log.debug("Project recovery skipped (likely empty on first run): {}", e.getMessage());
        }
    }

    public Map<String, Object> ingestRepo(String repoUrl, String identity, Long ownerId) throws Exception {
        log.info(">>> INGEST START: {}", repoUrl);
        if (!ingestLock.tryLock()) {
            throw new IllegalStateException(
                    "An ingestion is already in progress. Please try again later.");
        }
        try {
            repoLoader.validateRepoUrl(repoUrl);
            String projectId = ProjectRegistry.toProjectId(repoUrl, ownerId);
            enforceProjectLimits(projectId, ownerId);
            String ownerKey = ownerKey(ownerId);
            Path localPath = repoLoader.projectPath(projectId);
            log.info("Cloning into {} (project_id={})", localPath, projectId);

            String commitSha = repoLoader.cloneRepo(repoUrl, localPath);
            List<CodeFile> files = repoLoader.loadCodeFiles(localPath);
            log.info("Loaded {} files", files.size());
            if (files.isEmpty()) {
                throw new RuntimeException("No supported code files found in the repository.");
            }

            List<Chunk> chunks = chunker.chunkCodeFiles(files);
            clearProject(projectId);

            List<Document> docs = chunks.stream()
                    .map(c -> new Document(c.text(), Map.of(
                            "project_id",  projectId,
                            "owner_id",    ownerKey,
                            "repo_url",    repoUrl,
                            "file_path",   c.filePath(),
                            "chunk_index", c.chunkIndex(),
                            "start_line",  Objects.requireNonNullElse(c.startLine(), -1),
                            "end_line",    Objects.requireNonNullElse(c.endLine(),   -1)
                    )))
                    .toList();

            int batchSize = props.embeddingBatchSize();
            for (int start = 0; start < docs.size(); start += batchSize) {
                int end = Math.min(start + batchSize, docs.size());
                vectorStore.add(docs.subList(start, end));
                log.info("Stored chunks {}-{} of {} in vector store.",
                        start + 1, end, docs.size());
            }

            recordEmbeddingUsage(identity, docs);

            registry.register(new ProjectInfo(
                    projectId, repoUrl, Instant.now(), commitSha,
                    files.size(), chunks.size(), ownerId));

            // The corpus just changed; drop any answers/documents built from the
            // previous state so a re-ingest can never serve a stale response.
            responseCache.clear();

            return Map.of(
                    "project_id",     projectId,
                    "commit_sha",     commitSha != null ? commitSha : "",
                    "files_loaded",   files.size(),
                    "chunks_created", chunks.size()
            );
        } finally {
            ingestLock.unlock();
        }
    }

    public Map<String, Object> askQuestion(String question, int k, List<String> projectIds,
                                            String identity, Long ownerId) {
        requireIngested(ownerId);
        List<String> normalizedProjectIds = normalizeProjectIds(projectIds);
        requireKnownProjects(normalizedProjectIds, ownerId);
        int effectiveK = Math.min(Math.max(k, 1), props.maxQueryK());
        String cacheKey = responseCacheKey(
                "question", normalizedProjectIds, question, effectiveK, "", ownerId);
        Optional<Map<String, Object>> cached = responseCache.get(cacheKey);
        if (cached.isPresent()) {
            log.info("Response cache hit for question (entries={}).", responseCache.size());
            return cached.get();
        }
        int searchK = retrievalCandidateCount(effectiveK, props.maxQueryK());
        SearchRequest request = buildSearchRequest(question, searchK, normalizedProjectIds, ownerId);
        List<Document> results = selectDiverseDocuments(
                deduplicateDocuments(vectorStore.similaritySearch(request)), effectiveK);
        List<String> formatted = results.stream().map(this::formatChunk).toList();
        String context = String.join("\n\n", formatted);
        String answer  = llm.askLlm(question, context, identity);
        Map<String, Object> response = Map.of("answer", answer, "sources", formatted);
        responseCache.put(cacheKey, response);
        return response;
    }

    public Map<String, Object> generateDocument(String projectName, int k,
                                                 List<String> projectIds, String identity, Long ownerId) {
        requireIngested(ownerId);
        List<String> normalizedProjectIds = normalizeProjectIds(projectIds);
        requireKnownProjects(normalizedProjectIds, ownerId);
        int effectiveK = Math.min(Math.max(k, 1), props.maxDocumentK());
        String cacheKey = responseCacheKey(
                "document", normalizedProjectIds, projectName, effectiveK, "document", ownerId);
        Optional<Map<String, Object>> cached = responseCache.get(cacheKey);
        if (cached.isPresent()) {
            log.info("Response cache hit for document (entries={}).", responseCache.size());
            return cached.get();
        }

        List<Document> retrieved = retrieveDocumentsForDocumentGeneration(
                normalizedProjectIds, effectiveK, ownerId);
        List<Document> deduped = selectDiverseDocuments(
                deduplicateDocuments(retrieved), effectiveK);

        List<ProjectInfo> scopedProjects = registry.findByIdsForOwner(normalizedProjectIds, ownerId);

        StringBuilder treeSection = new StringBuilder("## Repository Trees\n");
        for (ProjectInfo project : scopedProjects) {
            treeSection.append("\n### ").append(project.repoUrl()).append("\n");
            treeSection.append(repoLoader.buildRepoTree(repoLoader.projectPath(project.projectId())));
        }

        List<String> formatted = deduped.stream().map(this::formatChunk).toList();
        String context = treeSection + "\n\n## Retrieved Code Evidence\n"
                + String.join("\n\n", formatted);

        Map<String, Object> chainResult = llm.runReverseEngineeringChain(context, projectName, identity);

        Map<String, Object> response = Map.of(
                "document",    chainResult.get("document"),
                "chain_steps", chainResult.get("chain_steps"),
                "sources",     formatted
        );
        responseCache.put(cacheKey, response);
        return response;
    }

    public List<ProjectInfo> listProjects(Long ownerId) {
        return registry.findAllForOwner(ownerId);
    }

    /**
     * Deletes every project owned by {@code ownerId} — vector-store rows, registry
     * entries, and on-disk clones. Used when an account is removed.
     *
     * @return the number of projects removed
     */
    public int deleteAllProjectsForOwner(long ownerId) {
        int removed = 0;
        for (ProjectInfo info : registry.findAllForOwner(ownerId)) {
            if (deleteProject(info.projectId(), ownerId)) {
                removed++;
            }
        }
        return removed;
    }

    public boolean deleteProject(String projectId, Long ownerId) {
        if (registry.findByIdForOwner(projectId, ownerId).isEmpty()) {
            return false;
        }
        try {
            jdbcTemplate.update(
                    "DELETE FROM vector_store WHERE metadata->>'project_id' = ? "
                    + "AND metadata->>'owner_id' = ?",
                    projectId, ownerKey(ownerId));
            log.info("Deleted vector store data for project '{}'.", projectId);
        } catch (Exception e) {
            log.warn("Could not delete vector store data for '{}': {}", projectId, e.getMessage());
        }
        boolean removed = registry.remove(projectId, ownerId);
        if (removed) {
            repoLoader.deleteProjectClone(projectId);
        }
        // A delete followed by a same-SHA re-ingest must not resurrect the
        // deleted project's cached answers.
        responseCache.clear();
        return removed;
    }

    private static SearchRequest buildSearchRequest(String query, int k,
                                                     List<String> projectIds, Long ownerId) {
        var builder = SearchRequest.builder().query(query).topK(k);
        // owner_id and project_id values are [a-z0-9_-] only — safe to embed literally.
        String ownerClause = "owner_id == '" + ownerKey(ownerId) + "'";
        if (projectIds.isEmpty()) {
            builder.filterExpression(ownerClause);
        } else {
            String projectClause = projectIds.stream()
                    .distinct()
                    .map(id -> "project_id == '" + id + "'")
                    .collect(Collectors.joining(" OR "));
            builder.filterExpression(ownerClause + " AND (" + projectClause + ")");
        }
        return builder.build();
    }

    private List<String> normalizeProjectIds(List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return List.of();
        }
        return projectIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
    }

    private List<Document> retrieveDocumentsForDocumentGeneration(
            List<String> projectIds, int k, Long ownerId) {
        String retrievalQuery = String.join(" ",
                "application entry points startup initialization routing controllers API endpoints",
                "overall architecture modules services components layers package structure",
                "data flow request flow business logic database persistence models schemas",
                "configuration environment variables secrets settings deployment dependencies",
                "authentication authorization security validation error handling external integrations",
                "important classes functions interfaces utilities background jobs clients");

        int searchK = Math.min(Math.max(k * 3, MIN_DOCUMENT_RETRIEVAL_K), props.maxDocumentK());
        return vectorStore.similaritySearch(
                buildSearchRequest(retrievalQuery, searchK, projectIds, ownerId));
    }

    private static int retrievalCandidateCount(int requestedK, int maximumK) {
        // Over-fetching gives the diversity pass enough alternatives when the
        // closest chunks are concentrated in one large file.
        return Math.min(maximumK, Math.max(requestedK, requestedK * 3));
    }

    private String responseCacheKey(String type, List<String> projectIds,
                                    String prompt, int k, String variant, Long ownerId) {
        String projectVersion = registry.findByIdsForOwner(projectIds, ownerId).stream()
                .sorted(Comparator.comparing(ProjectInfo::projectId))
                .map(project -> project.projectId() + "="
                        + Objects.toString(project.lastCommitSha(), "unknown"))
                .collect(Collectors.joining(","));
        String raw = String.join("|", type, ownerKey(ownerId), projectVersion, variant,
                Integer.toString(k), Objects.toString(prompt, "").trim());
        return type + ":" + sha256(raw);
    }

    /** Metadata/filter representation of an owner: the numeric id, or a sentinel for shared/MCP data. */
    private static String ownerKey(Long ownerId) {
        return ownerId != null ? ownerId.toString() : "__shared__";
    }

    private static Long ownerIdFromKey(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank() || "__shared__".equals(ownerKey)) {
            return null;
        }
        try {
            return Long.parseLong(ownerKey.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                hex.append(String.format("%02x", part));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static List<Document> selectDiverseDocuments(List<Document> documents, int limit) {
        if (documents.size() <= limit) {
            return documents;
        }

        List<Document> selected = new ArrayList<>(limit);
        Set<String> selectedFiles = new HashSet<>();

        // First pass: cover as many distinct files as possible while keeping
        // the vector store's relevance ordering within each file.
        for (Document document : documents) {
            if (selected.size() == limit) {
                break;
            }
            if (selectedFiles.add(documentFileKey(document))) {
                selected.add(document);
            }
        }

        // Second pass: use the remaining slots for the next-best chunks.
        if (selected.size() < limit) {
            for (Document document : documents) {
                if (selected.size() == limit) {
                    break;
                }
                if (!selected.contains(document)) {
                    selected.add(document);
                }
            }
        }
        return selected;
    }

    private static String documentFileKey(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return Objects.toString(metadata.get("project_id"), "") + ":"
                + Objects.toString(metadata.get("file_path"), "");
    }

    String formatChunk(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String filePath  = String.valueOf(meta.getOrDefault("file_path", "unknown"));
        Object startLine = meta.get("start_line");
        Object endLine   = meta.get("end_line");
        Object chunkIdx  = meta.get("chunk_index");
        Object projectId = meta.get("project_id");

        String lineLabel = "";
        if (startLine != null && endLine != null
                && !"-1".equals(String.valueOf(startLine))) {
            lineLabel = ":" + startLine + "-" + endLine;
        } else if (chunkIdx != null) {
            lineLabel = " chunk " + chunkIdx;
        }

        String projectPrefix = projectId != null ? "[" + projectId + "] " : "";
        return "### File: " + projectPrefix + filePath + lineLabel + "\n" + doc.getText();
    }

    private void requireIngested(Long ownerId) {
        if (registry.isEmptyForOwner(ownerId)) {
            throw new IllegalArgumentException(
                    "No repository has been ingested yet. "
                    + "Call the /ingest endpoint first.");
        }
    }

    /**
     * Caps the number of ingested projects so the vector store cannot grow
     * without bound. Re-ingesting an existing project replaces it in place and
     * is always allowed.
     *
     * @throws IllegalStateException (mapped to HTTP 409) when a limit is hit
     */
    private void enforceProjectLimits(String projectId, Long ownerId) {
        if (registry.findByIdForOwner(projectId, ownerId).isPresent()) {
            return; // re-ingest, not a new slot
        }
        int perUser = props.limits().maxProjectsPerUser();
        if (ownerId != null && perUser > 0
                && registry.findAllForOwner(ownerId).size() >= perUser) {
            throw new IllegalStateException(
                    "You have reached the limit of " + perUser
                    + " projects. Delete one before ingesting another.");
        }
        int total = props.limits().maxProjectsTotal();
        if (total > 0 && registry.totalCount() >= total) {
            throw new IllegalStateException(
                    "The service is at project capacity. Please try again later.");
        }
    }

    private void requireKnownProjects(List<String> projectIds, Long ownerId) {
        if (projectIds == null || projectIds.isEmpty()) {
            return;
        }
        for (String projectId : projectIds) {
            if (registry.findByIdForOwner(projectId, ownerId).isEmpty()) {
                throw new IllegalArgumentException(
                        "Project '" + projectId + "' not found.");
            }
        }
    }
    

    
    private void recordEmbeddingUsage(String identity, List<Document> docs) {
        long estimatedTokens = docs.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .mapToLong(text -> (long) text.length())
                .sum() / CHARS_PER_TOKEN_ESTIMATE;
        int clamped = (int) Math.min(estimatedTokens, Integer.MAX_VALUE);
        usageGuard.recordUsage(identity, clamped, 0);
    }

    private void clearProject(String projectId) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM vector_store WHERE metadata->>'project_id' = ?",
                    projectId);
            if (deleted > 0) {
                log.info("Cleared {} existing chunks for project '{}'.", deleted, projectId);
            }
        } catch (Exception e) {
            log.debug("clearProject('{}') skipped: {}", projectId, e.getMessage());
        }
    }

    private static List<Document> deduplicateDocuments(List<Document> docs) {
        Set<String> seen = new LinkedHashSet<>();
        List<Document> unique = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            String key = Objects.toString(meta.get("project_id"), "") + ":"
                    + Objects.toString(meta.get("file_path"), "") + ":"
                    + Objects.toString(meta.get("chunk_index"), "") + ":"
                    + Objects.hashCode(doc.getText());
            if (seen.add(key)) {
                unique.add(doc);
            }
        }
        return unique;
    }
}
