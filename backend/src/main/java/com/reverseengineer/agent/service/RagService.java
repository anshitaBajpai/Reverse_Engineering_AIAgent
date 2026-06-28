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

    private final ReentrantLock ingestLock = new ReentrantLock();

    public RagService(VectorStore vectorStore,
                      JdbcTemplate jdbcTemplate,
                      RepoLoaderService repoLoader,
                      ChunkerService chunker,
                      LlmService llm,
                      AppProperties props,
                      ProjectRegistry registry) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.repoLoader = repoLoader;
        this.chunker = chunker;
        this.llm = llm;
        this.props = props;
        this.registry = registry;
    }

    @PostConstruct
    void checkExistingData() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT DISTINCT metadata->>'project_id' AS project_id, " +
                    "                metadata->>'repo_url'   AS repo_url " +
                    "FROM   vector_store " +
                    "WHERE  metadata->>'project_id' IS NOT NULL");

            for (Map<String, Object> row : rows) {
                String projectId = (String) row.get("project_id");
                String repoUrl   = (String) row.get("repo_url");
                if (projectId != null && repoUrl != null) {
                    registry.register(new ProjectInfo(
                            projectId, repoUrl, null, null, 0, 0));
                }
            }
            if (!rows.isEmpty()) {
                log.info("Recovered {} project(s) from vector store on startup.", rows.size());
            }
        } catch (Exception e) {
            log.debug("Project recovery skipped (likely empty on first run): {}", e.getMessage());
        }
    }

    public Map<String, Object> ingestRepo(String repoUrl) throws Exception {
        log.info(">>> INGEST START: {}", repoUrl);
        if (!ingestLock.tryLock()) {
            throw new IllegalStateException(
                    "An ingestion is already in progress. Please try again later.");
        }
        try {
            repoLoader.validateRepoUrl(repoUrl);
            String projectId = ProjectRegistry.toProjectId(repoUrl);
            Path localPath = Path.of(props.repoDir());
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
                            "repo_url",    repoUrl,
                            "file_path",   c.filePath(),
                            "chunk_index", c.chunkIndex(),
                            "start_line",  Objects.requireNonNullElse(c.startLine(), -1),
                            "end_line",    Objects.requireNonNullElse(c.endLine(),   -1)
                    )))
                    .toList();

            vectorStore.add(docs);
            log.info("Stored {} chunks in vector store.", docs.size());

            registry.register(new ProjectInfo(
                    projectId, repoUrl, Instant.now(), commitSha,
                    files.size(), chunks.size()));

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

    public Map<String, Object> askQuestion(String question, int k, List<String> projectIds) {
        requireIngested();
        SearchRequest request = buildSearchRequest(question, k, projectIds);
        List<Document> results = vectorStore.similaritySearch(request);
        List<String> formatted = results.stream().map(this::formatChunk).toList();
        String context = String.join("\n\n", formatted);
        String answer  = llm.askLlm(question, context);
        return Map.of("answer", answer, "sources", formatted);
    }

    public Map<String, Object> generateDocument(String projectName, int k,
                                                 List<String> projectIds) {
        requireIngested();

        String[] retrievalQueries = {
                "application entry points startup initialization routing controllers API endpoints",
                "overall architecture modules services components layers package structure",
                "data flow request flow business logic database persistence models schemas",
                "configuration environment variables secrets settings deployment dependencies",
                "authentication authorization security validation error handling external integrations",
                "important classes functions interfaces utilities background jobs clients"
        };

        int perQueryK = Math.max(4, Math.min(k, 12));
        List<Document> retrieved = new ArrayList<>();
        for (String q : retrievalQueries) {
            retrieved.addAll(vectorStore.similaritySearch(
                    buildSearchRequest(q, perQueryK, projectIds)));
        }

        List<Document> deduped = deduplicateDocuments(retrieved);
        int cap = Math.min(k, Math.min(deduped.size(), props.maxDocumentK()));
        deduped = deduped.subList(0, cap);

        List<String> scopedUrls = projectIds.isEmpty()
                ? registry.findAll().stream().map(ProjectInfo::repoUrl).toList()
                : registry.findAll().stream()
                        .filter(p -> projectIds.contains(p.projectId()))
                        .map(ProjectInfo::repoUrl).toList();

        StringBuilder treeSection = new StringBuilder("## Repository Trees\n");
        for (String url : scopedUrls) {
            treeSection.append("\n### ").append(url).append("\n");
            treeSection.append(repoLoader.buildRepoTree(Path.of(props.repoDir())));
        }

        List<String> formatted = deduped.stream().map(this::formatChunk).toList();
        String context = treeSection + "\n\n## Retrieved Code Evidence\n"
                + String.join("\n\n", formatted);

        Map<String, Object> chainResult = llm.runReverseEngineeringChain(context, projectName);

        return Map.of(
                "document",    chainResult.get("document"),
                "chain_steps", chainResult.get("chain_steps"),
                "sources",     formatted
        );
    }

    public List<ProjectInfo> listProjects() {
        return registry.findAll();
    }

    public boolean deleteProject(String projectId) {
        try {
            jdbcTemplate.update(
                    "DELETE FROM vector_store WHERE metadata->>'project_id' = ?",
                    projectId);
            log.info("Deleted vector store data for project '{}'.", projectId);
        } catch (Exception e) {
            log.warn("Could not delete vector store data for '{}': {}", projectId, e.getMessage());
        }
        return registry.remove(projectId);
    }

    private static SearchRequest buildSearchRequest(String query, int k,
                                                     List<String> projectIds) {
        var builder = SearchRequest.builder().query(query).topK(k);
        if (!projectIds.isEmpty()) {
            // project_id values are [a-z0-9-] only — safe to embed in filter expression
            String filter = projectIds.stream()
                    .map(id -> "project_id == '" + id + "'")
                    .collect(Collectors.joining(" OR "));
            builder.filterExpression(filter);
        }
        return builder.build();
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

    private void requireIngested() {
        if (registry.isEmpty()) {
            throw new IllegalArgumentException(
                    "No repository has been ingested yet. "
                    + "Call the /ingest endpoint first.");
        }
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
        List<Document> unique = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            String key = meta.getOrDefault("project_id", "") + ":"
                    + meta.getOrDefault("file_path", "") + ":"
                    + meta.getOrDefault("chunk_index", "");
            if (seen.add(key)) unique.add(doc);
        }
        return unique;
    }
}
