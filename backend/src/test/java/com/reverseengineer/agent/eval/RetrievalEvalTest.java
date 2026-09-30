package com.reverseengineer.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.reverseengineer.agent.TestcontainersConfiguration;
import com.reverseengineer.agent.service.DocumentEvidence;
import com.reverseengineer.agent.service.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures retrieval quality on a fixed question set ({@code eval/retrieval-eval.json}):
 * for each question, where the first chunk from an expected file ranks among the
 * top {@value #TOP_K} retrieved chunks. Reports Hit@1, Hit@5 and MRR@10 for
 * vector-only and hybrid retrieval side by side.
 *
 * <p>It clones real repositories and calls the OpenAI embeddings API (a few cents
 * per run; no chat completions), so it only runs when asked:</p>
 * <pre>
 * RUN_RETRIEVAL_EVAL=true OPENAI_API_KEY=sk-... mvn test -Dtest=RetrievalEvalTest
 * </pre>
 * <p>Needs Docker. The report is printed and written to
 * {@code target/retrieval-eval.md}. Run it before and after a chunking or
 * retrieval change to see whether the change helped.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "RUN_RETRIEVAL_EVAL", matches = "true")
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=${OPENAI_API_KEY}",
        "app.repo-dir=${java.io.tmpdir}/retrieval-eval-repos",
        "app.max-concurrent-ingests=1"
})
class RetrievalEvalTest {

    private static final int TOP_K = 10;
    private static final String IDENTITY = "retrieval-eval";

    record EvalQuestion(String question, List<String> expectedFiles) {}

    record EvalRepo(String repoUrl, List<EvalQuestion> questions) {}

    /** Rank (1-based) of the first chunk from an expected file, or 0 if none in the top K. */
    record Result(String repo, String question, int vectorRank, int hybridRank) {}

    /** Evidence chosen for a generated document: share of source-code chunks, key files found. */
    record EvidenceResult(String repo, String strategy, int chunks, double sourceShare,
                          int keyFilesFound, int keyFiles, List<String> topFiles) {}

    /** Chunks per document, the frontend's default. */
    private static final int DOCUMENT_K = 25;

    /** The single blended query documents were retrieved with before the per-topic searches. */
    private static final String LEGACY_DOCUMENT_QUERY = String.join(" ",
            "application entry points startup initialization routing controllers API endpoints",
            "overall architecture modules services components layers package structure",
            "data flow request flow business logic database persistence models schemas",
            "configuration environment variables secrets settings deployment dependencies",
            "authentication authorization security validation error handling external integrations",
            "important classes functions interfaces utilities background jobs clients");

    @Autowired
    private RagService ragService;

    @Autowired
    private VectorStore vectorStore;

    @Test
    void evaluateRetrieval() throws Exception {
        List<Result> results = new ArrayList<>();
        List<EvidenceResult> evidence = new ArrayList<>();
        for (EvalRepo repo : loadCases()) {
            Map<String, Object> ingest = ragService.ingestRepo(repo.repoUrl(), IDENTITY, null);
            String projectId = (String) ingest.get("project_id");
            List<String> scope = List.of(projectId);
            for (EvalQuestion q : repo.questions()) {
                results.add(new Result(repo.repoUrl(), q.question(),
                        rankOfExpected(ragService.retrieve(q.question(), TOP_K, scope, null, false), q.expectedFiles()),
                        rankOfExpected(ragService.retrieve(q.question(), TOP_K, scope, null, true), q.expectedFiles())));
            }
            Set<String> keyFiles = new LinkedHashSet<>();
            repo.questions().forEach(q -> keyFiles.addAll(q.expectedFiles()));
            evidence.add(evaluateEvidence(repo.repoUrl(), "single query (before)",
                    legacyDocumentEvidence(projectId), keyFiles));
            evidence.add(evaluateEvidence(repo.repoUrl(), "per-topic + caps (now)",
                    ragService.retrieveDocumentEvidence(scope, DOCUMENT_K, null), keyFiles));
        }

        String report = report(results) + evidenceReport(evidence);
        System.out.println(report);
        Path out = Path.of("target", "retrieval-eval.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);

        assertTrue(results.stream().anyMatch(r -> r.hybridRank() > 0),
                "retrieval found no expected file for any question — the pipeline is likely broken");
    }

    /** The old document retrieval: one blended vector query, one chunk per file first. */
    private List<Document> legacyDocumentEvidence(String projectId) {
        List<Document> candidates = vectorStore.similaritySearch(SearchRequest.builder()
                .query(LEGACY_DOCUMENT_QUERY)
                .topK(Math.min(DOCUMENT_K * 3, 40))
                .filterExpression("owner_id == '__shared__' AND project_id == '" + projectId + "'")
                .build());
        Set<Document> selected = new LinkedHashSet<>();
        Set<String> files = new HashSet<>();
        for (Document d : candidates) {
            if (selected.size() < DOCUMENT_K && files.add(path(d))) selected.add(d);
        }
        for (Document d : candidates) {
            if (selected.size() < DOCUMENT_K) selected.add(d);
        }
        return new ArrayList<>(selected);
    }

    private static EvidenceResult evaluateEvidence(String repo, String strategy,
                                                   List<Document> chosen, Set<String> keyFiles) {
        long source = chosen.stream()
                .filter(d -> DocumentEvidence.classify(path(d)) == DocumentEvidence.Kind.SOURCE)
                .count();
        Set<String> paths = new LinkedHashSet<>();
        chosen.forEach(d -> paths.add(path(d)));
        int found = (int) keyFiles.stream().filter(k -> paths.stream().anyMatch(p -> p.endsWith(k))).count();
        return new EvidenceResult(repo, strategy, chosen.size(),
                chosen.isEmpty() ? 0 : (double) source / chosen.size(),
                found, keyFiles.size(), paths.stream().limit(8).toList());
    }

    private static String evidenceReport(List<EvidenceResult> evidence) {
        StringBuilder md = new StringBuilder("\n## Document evidence (k=" + DOCUMENT_K + ")\n\n")
                .append("| Repo | Strategy | Source-code share | Key files covered | First files |\n")
                .append("|---|---|---|---|---|\n");
        for (EvidenceResult e : evidence) {
            md.append("| ").append(e.repo().replace("https://github.com/", ""))
                    .append(" | ").append(e.strategy())
                    .append(" | ").append("%.0f%%".formatted(e.sourceShare() * 100))
                    .append(" | ").append(e.keyFilesFound()).append("/").append(e.keyFiles())
                    .append(" | ").append(String.join(", ", e.topFiles())).append(" |\n");
        }
        return md.toString();
    }

    private static String path(Document d) {
        return String.valueOf(d.getMetadata().get("file_path"));
    }

    private static int rankOfExpected(List<Document> retrieved, List<String> expectedFiles) {
        for (int i = 0; i < retrieved.size(); i++) {
            String path = String.valueOf(retrieved.get(i).getMetadata().get("file_path"));
            if (expectedFiles.stream().anyMatch(path::endsWith)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static String report(List<Result> results) {
        StringBuilder md = new StringBuilder("# Retrieval evaluation\n\n")
                .append("| Mode | Hit@1 | Hit@5 | MRR@10 |\n|---|---|---|---|\n")
                .append(summaryRow("vector", results.stream().map(Result::vectorRank).toList()))
                .append(summaryRow("hybrid", results.stream().map(Result::hybridRank).toList()))
                .append("\n| Repo | Question | Vector rank | Hybrid rank |\n|---|---|---|---|\n");
        for (Result r : results) {
            md.append("| ").append(r.repo().replace("https://github.com/", ""))
                    .append(" | ").append(r.question())
                    .append(" | ").append(rankLabel(r.vectorRank()))
                    .append(" | ").append(rankLabel(r.hybridRank())).append(" |\n");
        }
        return md.toString();
    }

    private static String summaryRow(String mode, List<Integer> ranks) {
        double n = ranks.size();
        double hit1 = ranks.stream().filter(r -> r == 1).count() / n;
        double hit5 = ranks.stream().filter(r -> r >= 1 && r <= 5).count() / n;
        double mrr = ranks.stream().mapToDouble(r -> r > 0 ? 1.0 / r : 0).sum() / n;
        return "| %s | %.2f | %.2f | %.3f |%n".formatted(mode, hit1, hit5, mrr);
    }

    private static String rankLabel(int rank) {
        return rank > 0 ? Integer.toString(rank) : "miss";
    }

    private static List<EvalRepo> loadCases() throws Exception {
        ObjectMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        try (InputStream in = RetrievalEvalTest.class.getResourceAsStream("/eval/retrieval-eval.json")) {
            return mapper.readValue(in, new TypeReference<>() {});
        }
    }
}
