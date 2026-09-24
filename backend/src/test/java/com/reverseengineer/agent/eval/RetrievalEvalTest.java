package com.reverseengineer.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.reverseengineer.agent.TestcontainersConfiguration;
import com.reverseengineer.agent.service.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @Autowired
    private RagService ragService;

    @Test
    void evaluateRetrieval() throws Exception {
        List<Result> results = new ArrayList<>();
        for (EvalRepo repo : loadCases()) {
            Map<String, Object> ingest = ragService.ingestRepo(repo.repoUrl(), IDENTITY, null);
            List<String> scope = List.of((String) ingest.get("project_id"));
            for (EvalQuestion q : repo.questions()) {
                results.add(new Result(repo.repoUrl(), q.question(),
                        rankOfExpected(ragService.retrieve(q.question(), TOP_K, scope, null, false), q.expectedFiles()),
                        rankOfExpected(ragService.retrieve(q.question(), TOP_K, scope, null, true), q.expectedFiles())));
            }
        }

        String report = report(results);
        System.out.println(report);
        Path out = Path.of("target", "retrieval-eval.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);

        assertTrue(results.stream().anyMatch(r -> r.hybridRank() > 0),
                "retrieval found no expected file for any question — the pipeline is likely broken");
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
