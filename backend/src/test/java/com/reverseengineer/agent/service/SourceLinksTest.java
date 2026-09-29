package com.reverseengineer.agent.service;

import com.reverseengineer.agent.model.ProjectInfo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceLinksTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void githubLinkIsPinnedToTheCommitWithLineRange() {
        assertEquals("https://github.com/psf/requests/blob/" + SHA + "/src/requests/sessions.py#L10-L42",
                SourceLinks.fileUrl("https://github.com/psf/requests", SHA, "src/requests/sessions.py", 10, 42));
    }

    @Test
    void trailingSlashAndGitSuffixAreIgnored() {
        assertEquals("https://github.com/psf/requests/blob/" + SHA + "/a.py#L3",
                SourceLinks.fileUrl("https://github.com/psf/requests.git/", SHA, "a.py", 3, 3));
    }

    @Test
    void missingLinesOrCommitStillLink() {
        assertEquals("https://github.com/o/r/blob/HEAD/a.py",
                SourceLinks.fileUrl("https://github.com/o/r", null, "a.py", null, null));
        assertEquals("https://github.com/o/r/blob/HEAD/a.py",
                SourceLinks.fileUrl("https://github.com/o/r", "not-a-sha", "a.py", -1, -1));
    }

    @Test
    void otherHostsUseTheirLayout() {
        assertEquals("https://gitlab.com/g/p/-/blob/" + SHA + "/a.py#L1-5",
                SourceLinks.fileUrl("https://gitlab.com/g/p", SHA, "a.py", 1, 5));
        assertEquals("https://bitbucket.org/t/r/src/" + SHA + "/a.py#lines-1:5",
                SourceLinks.fileUrl("https://bitbucket.org/t/r", SHA, "a.py", 1, 5));
        assertNull(SourceLinks.fileUrl("https://git.example.com/o/r", SHA, "a.py", 1, 5));
    }

    @Test
    void pathSegmentsAreEncoded() {
        assertEquals("https://github.com/o/r/blob/HEAD/docs/my%20notes/%23draft.md",
                SourceLinks.fileUrl("https://github.com/o/r", null, "docs/my notes/#draft.md", null, null));
    }

    @Test
    void unusableInputsGiveNoLink() {
        assertNull(SourceLinks.fileUrl(null, SHA, "a.py", 1, 1));
        assertNull(SourceLinks.fileUrl("https://github.com/o/r", SHA, " ", 1, 1));
        assertNull(SourceLinks.fileUrl("https://github.com", SHA, "a.py", 1, 1));
        assertNull(SourceLinks.fileUrl("not a url", SHA, "a.py", 1, 1));
    }

    @Test
    void citationsFollowSourceOrderAndOmitUnknownFields() {
        ProjectInfo project = new ProjectInfo("u1-p", "https://github.com/o/r", null, SHA, 1, 1, 1L);
        List<Document> documents = List.of(
                new Document("1", "x", Map.of("project_id", "u1-p", "file_path", "a.py",
                        "start_line", 4, "end_line", 9)),
                new Document("2", "y", Map.of("project_id", "u1-p", "file_path", "b.py",
                        "start_line", -1, "end_line", -1)),
                new Document("3", "z", Map.of("project_id", "gone", "file_path", "c.py")));

        List<Map<String, Object>> citations = RagService.citations(documents, List.of(project));

        assertEquals(Map.of("project_id", "u1-p", "file_path", "a.py", "start_line", 4, "end_line", 9,
                "url", "https://github.com/o/r/blob/" + SHA + "/a.py#L4-L9"), citations.get(0));
        assertEquals(Map.of("project_id", "u1-p", "file_path", "b.py",
                "url", "https://github.com/o/r/blob/" + SHA + "/b.py"), citations.get(1));
        assertEquals(Map.of("project_id", "gone", "file_path", "c.py"), citations.get(2));
    }
}
