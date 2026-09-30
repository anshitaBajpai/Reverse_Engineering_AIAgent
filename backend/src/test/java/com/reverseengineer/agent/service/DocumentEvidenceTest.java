package com.reverseengineer.agent.service;

import com.reverseengineer.agent.service.DocumentEvidence.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentEvidenceTest {

    @Test
    void classifiesByPath() {
        assertEquals(Kind.SOURCE, DocumentEvidence.classify("src/itsdangerous/signer.py"));
        assertEquals(Kind.SOURCE, DocumentEvidence.classify("backend/src/main/java/app/RagService.java"));
        assertEquals(Kind.SOURCE, DocumentEvidence.classify("src/latest.py"));

        assertEquals(Kind.TEST, DocumentEvidence.classify("tests/test_itsdangerous/test_timed.py"));
        assertEquals(Kind.TEST, DocumentEvidence.classify("src/test/java/app/RagServiceTest.java"));
        assertEquals(Kind.TEST, DocumentEvidence.classify("web/src/api.test.js"));
        assertEquals(Kind.TEST, DocumentEvidence.classify("pkg/server_test.go"));

        assertEquals(Kind.CONFIG, DocumentEvidence.classify("pyproject.toml"));
        assertEquals(Kind.CONFIG, DocumentEvidence.classify("backend/src/main/resources/application.properties"));
        assertEquals(Kind.CONFIG, DocumentEvidence.classify("requirements.txt"));
        assertEquals(Kind.CONFIG, DocumentEvidence.classify("Dockerfile"));

        assertEquals(Kind.DOCS, DocumentEvidence.classify("README.md"));
        assertEquals(Kind.DOCS, DocumentEvidence.classify("docs/api.rst"));

        assertEquals(Kind.META, DocumentEvidence.classify("LICENSE.txt"));
        assertEquals(Kind.META, DocumentEvidence.classify(".github/workflows/tests.yaml"));
        assertEquals(Kind.META, DocumentEvidence.classify(".devcontainer/on-create-command.sh"));
        assertEquals(Kind.META, DocumentEvidence.classify(".pre-commit-config.yaml"));
        assertEquals(Kind.META, DocumentEvidence.classify("frontend/package-lock.json"));
    }

    @Test
    void capsNonSourceKindsAndPrefersNewFiles() {
        // Ranked: boilerplate and config first, as the old generic query produced.
        List<Document> ranked = List.of(
                doc("LICENSE.txt", 0), doc(".github/workflows/ci.yaml", 0),
                doc("pyproject.toml", 0), doc("setup.cfg", 0), doc("tox.ini", 0),
                doc("tests/test_a.py", 0), doc("tests/test_b.py", 0),
                doc("src/a.py", 0), doc("src/a.py", 1), doc("src/b.py", 0), doc("src/c.py", 0),
                doc("README.md", 0), doc("docs/guide.md", 0));

        List<String> picked = paths(DocumentEvidence.select(ranked, 10));

        assertEquals(10, picked.size());
        assertEquals(2, picked.stream().filter(p -> DocumentEvidence.classify(p) == Kind.CONFIG).count());
        assertEquals(1, picked.stream().filter(p -> DocumentEvidence.classify(p) == Kind.TEST).count());
        assertEquals(1, picked.stream().filter(p -> DocumentEvidence.classify(p) == Kind.DOCS).count());
        assertTrue(picked.containsAll(List.of("src/a.py", "src/b.py", "src/c.py")));
        // Second chunk of a.py is used before boilerplate.
        assertEquals(2, picked.stream().filter("src/a.py"::equals).count());
        // Boilerplate only fills what is left after everything else.
        assertEquals(List.of("LICENSE.txt", ".github/workflows/ci.yaml"), picked.subList(8, 10));
    }

    @Test
    void smallRepositoriesStillFillTheLimit() {
        List<Document> ranked = List.of(doc("LICENSE.txt", 0), doc("pyproject.toml", 0), doc("app.py", 0));

        assertEquals(List.of("pyproject.toml", "app.py", "LICENSE.txt"),
                paths(DocumentEvidence.select(ranked, 5)));
    }

    @Test
    void neverReturnsMoreThanTheLimit() {
        List<Document> ranked = new ArrayList<>();
        for (int i = 0; i < 50; i++) ranked.add(doc("src/f" + i + ".py", 0));

        assertEquals(25, DocumentEvidence.select(ranked, 25).size());
    }

    private static Document doc(String path, int chunk) {
        return new Document(path + "#" + chunk, "text " + path + chunk,
                Map.of("project_id", "p", "file_path", path, "chunk_index", chunk));
    }

    private static List<String> paths(List<Document> documents) {
        return documents.stream().map(d -> (String) d.getMetadata().get("file_path")).toList();
    }
}
