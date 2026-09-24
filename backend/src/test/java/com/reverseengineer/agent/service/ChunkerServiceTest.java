package com.reverseengineer.agent.service;

import com.reverseengineer.agent.model.Chunk;
import com.reverseengineer.agent.model.CodeFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ChunkerServiceTest {

    private static final String JAVA = """
            package demo;

            import java.util.List;

            /** Keeps track of widgets. */
            public class WidgetService {

                private final List<String> widgets;

                /** Adds one widget. */
                @Transactional
                public void addWidget(String name) {
                    var trimmed = name.trim();
                    widgets.add(trimmed);
                }

                public int count() {
                    return widgets.size();
                }
            }
            """;

    @Test
    void javaChunksBreakAtMethodsAndKeepTheirDocComments() {
        List<Chunk> chunks = ChunkerService.chunkFile(new CodeFile("src/WidgetService.java", JAVA), 200, 40);

        Chunk add = chunkContaining(chunks, "public void addWidget");
        assertTrue(code(add).startsWith("    /** Adds one widget. */\n    @Transactional\n"),
                "doc comment and annotation stay with the method:\n" + add.text());
        assertFalse(code(add).contains("public int count()"));
        assertFalse(code(add).contains("public class WidgetService"));
        // Local `var` declarations are not boundaries: the method body stays whole.
        assertTrue(code(add).contains("widgets.add(trimmed);"));
    }

    @Test
    void chunksCarryFileAndScopeHeaders() {
        List<Chunk> chunks = ChunkerService.chunkFile(new CodeFile("src/WidgetService.java", JAVA), 200, 40);

        Chunk count = chunkContaining(chunks, "public int count()");
        assertTrue(count.text().startsWith("File: src/WidgetService.java\nScope: public class WidgetService {\n"),
                count.text());
        Chunk header = chunks.get(0);
        assertTrue(header.text().startsWith("File: src/WidgetService.java\npackage demo;"));
        assertFalse(header.text().contains("Scope:"));
    }

    @Test
    void lineNumbersAreExact() {
        List<String> lines = JAVA.lines().toList();
        for (Chunk chunk : ChunkerService.chunkFile(new CodeFile("src/WidgetService.java", JAVA), 200, 40)) {
            String expected = String.join("\n", lines.subList(chunk.startLine() - 1, chunk.endLine()));
            assertEquals(expected, code(chunk), "chunk " + chunk.chunkIndex());
        }
    }

    @Test
    void smallDeclarationsArePackedTogether() {
        List<Chunk> chunks = ChunkerService.chunkFile(new CodeFile("src/WidgetService.java", JAVA), 5_000, 150);

        assertEquals(1, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals((int) JAVA.lines().count(), chunks.get(0).endLine());
    }

    @Test
    void pythonBreaksAtFunctionsAndKeepsDecorators() {
        String py = """
                import os


                def load(path):
                    return open(path).read()


                @cache
                def parse(text):
                    return text.split()
                """;
        List<Chunk> chunks = ChunkerService.chunkFile(new CodeFile("app/io.py", py), 60, 10);

        Chunk parse = chunkContaining(chunks, "def parse");
        assertTrue(code(parse).startsWith("@cache\ndef parse(text):"), parse.text());
        assertFalse(code(parse).contains("def load"));
    }

    @Test
    void oversizedDeclarationIsSplitWithOverlap() {
        String body = IntStream.range(0, 60)
                .mapToObj(i -> "        total += value" + i + ";")
                .collect(Collectors.joining("\n"));
        String java = "class Big {\n    int sum() {\n" + body + "\n    }\n}\n";
        List<Chunk> chunks = ChunkerService.chunkFile(new CodeFile("Big.java", java), 400, 80);

        assertTrue(chunks.size() > 2);
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).startLine() <= chunks.get(i - 1).endLine(),
                    "consecutive pieces overlap by at least one line");
        }
        for (Chunk chunk : chunks) {
            assertTrue(code(chunk).length() <= 400 + 40, "chunk " + chunk.chunkIndex() + " too long");
        }
        assertEquals((int) java.lines().count(), chunks.get(chunks.size() - 1).endLine());
    }

    @Test
    void minifiedLineIsHardSplit() {
        String minified = "var a=1;".repeat(1_000);
        List<Chunk> chunks = ChunkerService.chunkFile(new CodeFile("dist/app.min.js", minified), 1_000, 100);

        assertEquals(8, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.startLine() == 1 && c.endLine() == 1));
    }

    @Test
    void unknownExtensionStillChunks() {
        String text = "alpha\n\nbeta\n\ngamma\n";
        List<Chunk> chunks = ChunkerService.chunkFile(new CodeFile("notes.txt", text), 1_000, 100);

        assertEquals(1, chunks.size());
        assertEquals("File: notes.txt\nalpha\n\nbeta\n\ngamma", chunks.get(0).text());
    }

    @Test
    void blankFileProducesNoChunks() {
        assertTrue(ChunkerService.chunkFile(new CodeFile("empty.py", "\n\n  \n"), 1_000, 100).isEmpty());
    }

    private static Chunk chunkContaining(List<Chunk> chunks, String needle) {
        return chunks.stream()
                .filter(c -> c.text().contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no chunk contains " + needle));
    }

    /** The chunk text without its File:/Scope: header lines. */
    private static String code(Chunk chunk) {
        String text = chunk.text();
        text = text.substring(text.indexOf('\n') + 1);
        if (text.startsWith("Scope: ")) {
            text = text.substring(text.indexOf('\n') + 1);
        }
        return text;
    }
}
