package com.reverseengineer.agent.mcp;

import com.reverseengineer.agent.model.ProjectInfo;
import com.reverseengineer.agent.service.GitHubService;
import com.reverseengineer.agent.service.ProjectRegistry;
import com.reverseengineer.agent.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AgentMcpTools {

    private static final Logger log = LoggerFactory.getLogger(AgentMcpTools.class);

    // The MCP transport has no per-caller HTTP identity, so all MCP-originated
    // LLM usage is tracked and budgeted as a single shared identity.
    private static final String MCP_IDENTITY = "mcp";

    private final RagService ragService;
    private final GitHubService gitHub;
    private final ProjectRegistry registry;

    public AgentMcpTools(RagService ragService,
                          GitHubService gitHub,
                          ProjectRegistry registry) {
        this.ragService = ragService;
        this.gitHub     = gitHub;
        this.registry   = registry;
    }

    @Tool(description = """
            Clone and ingest a GitHub repository into the vector store.
            Must be called before ask_question or generate_document.
            Each repo gets a stable project_id derived from its URL.
            Multiple repos can be ingested (each stored separately).
            Re-ingesting the same URL replaces its previous data.""")
    public String ingestRepo(
            @ToolParam(description =
                    "Full HTTPS URL of the repository, e.g. https://github.com/owner/repo")
            String repoUrl) {

        log.info("[MCP] ingest_repo: {}", repoUrl);
        try {
            Map<String, Object> r = ragService.ingestRepo(repoUrl);
            return ("Ingested successfully. project_id=%s  commit=%s  files=%s  chunks=%s")
                    .formatted(r.get("project_id"), r.get("commit_sha"),
                               r.get("files_loaded"), r.get("chunks_created"));
        } catch (Exception e) {
            log.error("[MCP] ingest_repo failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = """
            Ask a question about ingested codebase(s) using RAG.
            Retrieves the most relevant code chunks and produces an
            evidence-based answer with file references.
            Pass project_ids to scope the question to specific repos,
            or leave it empty to search across all ingested projects.""")
    public String askQuestion(
            @ToolParam(description =
                    "Natural-language question about the codebase architecture, "
                    + "data flow, or implementation details")
            String question,

            @ToolParam(description =
                    "Number of code chunks to retrieve (1–20). "
                    + "Use 5 for focused questions, up to 20 for broad ones. "
                    + "Pass 0 for the default (5).")
            Integer k,

            @ToolParam(description =
                    "Comma-separated project IDs to restrict the search. "
                    + "Pass empty string to search ALL ingested projects.")
            String projectIds) {

        log.info("[MCP] ask_question");
        int topK = (k == null || k <= 0) ? 5 : k;
        List<String> ids = parseIds(projectIds);
        try {
            Map<String, Object> result = ragService.askQuestion(question, topK, ids, MCP_IDENTITY);
            return (String) result.get("answer");
        } catch (Exception e) {
            log.error("[MCP] ask_question failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = """
            Generate a professional Markdown reverse-engineering document.
            Runs a 4-step LLM chain: architecture → behaviour → risk → synthesis.
            Pass project_ids to generate a single-repo document, or leave empty
            to produce a CROSS-REPO analysis spanning all ingested projects.""")
    public String generateDocument(
            @ToolParam(description =
                    "Human-readable project name used in the report heading.")
            String projectName,

            @ToolParam(description =
                    "Total code chunks to retrieve (5–40). Use 25 for a balanced report. "
                    + "Pass 0 for the default (25).")
            Integer k,

            @ToolParam(description =
                    "Comma-separated project IDs to scope the document. "
                    + "Pass empty string to include ALL ingested projects (cross-repo).")
            String projectIds) {

        log.info("[MCP] generate_document");
        String name = (projectName == null || projectName.isBlank())
                ? "Ingested Repository" : projectName;
        int topK = (k == null || k <= 0) ? 25 : k;
        List<String> ids = parseIds(projectIds);
        try {
            Map<String, Object> result = ragService.generateDocument(name, topK, ids, MCP_IDENTITY);
            return (String) result.get("document");
        } catch (Exception e) {
            log.error("[MCP] generate_document failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = """
            List all repositories currently ingested into the vector store.
            Returns project_id, repo URL, ingest timestamp, HEAD commit SHA,
            file count, and chunk count for each project.""")
    public String listProjects() {
        log.info("[MCP] list_projects");
        List<ProjectInfo> projects = ragService.listProjects();
        if (projects.isEmpty()) {
            return "No projects ingested yet. Call ingest_repo first.";
        }
        return projects.stream()
                .map(p -> "• %s  |  %s  |  ingested=%s  |  sha=%s  |  files=%d  |  chunks=%d"
                        .formatted(p.projectId(), p.repoUrl(),
                                   p.ingestedAt() != null ? p.ingestedAt() : "unknown",
                                   p.lastCommitSha() != null
                                           ? p.lastCommitSha().substring(0, 7) : "?",
                                   p.filesLoaded(), p.chunksCreated()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = """
            Check whether a GitHub repository has new commits since it was last ingested.
            Compares the stored HEAD SHA with the current HEAD on GitHub.
            Returns whether re-ingestion is recommended.""")
    public String checkUpdates(
            @ToolParam(description =
                    "project_id of the ingested project (from list_projects or ingest_repo)")
            String projectId) {

        log.info("[MCP] check_updates: {}", projectId);
        var infoOpt = registry.findById(projectId);
        if (infoOpt.isEmpty()) {
            return "Project '" + projectId
                    + "' not found. Use list_projects to see available projects.";
        }
        var info = infoOpt.get();
        GitHubService.RepoStatus gh = gitHub.getStatus(info.repoUrl());

        if (gh.latestCommitSha() == null) {
            return "Could not reach the GitHub API. "
                    + "Check your GITHUB_TOKEN or network connection.";
        }

        boolean hasNew = info.lastCommitSha() != null
                && !info.lastCommitSha().equals(gh.latestCommitSha());

        if (hasNew) {
            return ("New commits detected for '%s'.\n"
                    + "  Ingested SHA : %s\n"
                    + "  Current SHA  : %s\n"
                    + "  Open PRs     : %d  |  Branches: %d\n"
                    + "Re-run ingest_repo('%s') to update.")
                    .formatted(projectId,
                               info.lastCommitSha().substring(0, 7),
                               gh.latestCommitSha().substring(0, 7),
                               gh.openPrCount(), gh.branchCount(),
                               info.repoUrl());
        }
        return ("'%s' is up-to-date (SHA %s).  Open PRs: %d  |  Branches: %d.")
                .formatted(projectId,
                           gh.latestCommitSha().substring(0, 7),
                           gh.openPrCount(), gh.branchCount());
    }

    private static List<String> parseIds(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) return List.of();
        return List.of(commaSeparated.split(",")).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}




