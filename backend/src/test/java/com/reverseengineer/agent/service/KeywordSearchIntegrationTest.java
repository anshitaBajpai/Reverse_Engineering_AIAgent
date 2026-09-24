package com.reverseengineer.agent.service;

import com.reverseengineer.agent.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real generated column and queries against pgvector (needs Docker). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class KeywordSearchIntegrationTest {

    @Autowired
    private KeywordSearchService keywordSearch;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'project_id' LIKE 'kw-%'");
        insert("1", "kw-p1", "src/limits/RateLimiterService.java",
                "File: src/limits/RateLimiterService.java\npublic class RateLimiterService {\n  boolean isAllowed(String ip) { return true; }\n}");
        insert("1", "kw-p1", "src/requests/sessions.py",
                "File: src/requests/sessions.py\ndef resolve_redirects(self, resp, req):\n    while resp.is_redirect:\n        yield resp");
        insert("1", "kw-p2", "README.md", "File: README.md\n# Widgets\nNothing about limits here.");
        insert("2", "kw-other", "src/RateLimiterService.java",
                "File: src/RateLimiterService.java\nclass RateLimiterService {}");
    }

    @Test
    void indexIsCreated() {
        assertTrue(keywordSearch.isAvailable());
    }

    @Test
    void findsCamelCaseIdentifierWholeAndByParts() {
        assertEquals(List.of("src/limits/RateLimiterService.java"),
                paths(keywordSearch.search("Where is RateLimiterService?", 10, List.of(), "1")));
        assertEquals(List.of("src/limits/RateLimiterService.java"),
                paths(keywordSearch.search("rate limiter", 10, List.of(), "1")));
    }

    @Test
    void findsSnakeCaseIdentifier() {
        assertEquals("src/requests/sessions.py",
                paths(keywordSearch.search("what does resolve_redirects do", 10, List.of(), "1")).get(0));
    }

    @Test
    void matchesFilePaths() {
        assertEquals("src/requests/sessions.py",
                paths(keywordSearch.search("sessions", 10, List.of(), "1")).get(0));
    }

    @Test
    void respectsOwnerAndProjectFilters() {
        assertEquals(List.of("src/RateLimiterService.java"),
                paths(keywordSearch.search("RateLimiterService", 10, List.of(), "2")));
        assertTrue(keywordSearch.search("RateLimiterService", 10, List.of("kw-p2"), "1").isEmpty());
        assertEquals(1, keywordSearch.search("RateLimiterService", 10, List.of("kw-p1", "kw-p2"), "1").size());
    }

    @Test
    void returnsMetadataAndText() {
        Document hit = keywordSearch.search("isAllowed", 10, List.of("kw-p1"), "1").get(0);

        assertEquals("kw-p1", hit.getMetadata().get("project_id"));
        assertTrue(hit.getText().contains("boolean isAllowed"));
        assertNotNull(hit.getId());
    }

    private void insert(String owner, String project, String path, String content) {
        jdbcTemplate.update(
                "INSERT INTO vector_store (id, content, metadata) VALUES (gen_random_uuid(), ?, ?::json)",
                content,
                "{\"owner_id\":\"" + owner + "\",\"project_id\":\"" + project + "\",\"file_path\":\"" + path + "\"}");
    }

    private static List<String> paths(List<Document> documents) {
        return documents.stream().map(d -> (String) d.getMetadata().get("file_path")).toList();
    }
}
