package com.reverseengineer.agent.service;

import com.reverseengineer.agent.model.Chunk;
import com.reverseengineer.agent.model.CodeFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits source files into chunks along code structure.
 *
 * <p>Each file is cut into segments at declaration boundaries (classes,
 * functions, methods, Markdown headings, ...) found by per-language line
 * patterns. Comments, annotations and decorators directly above a declaration
 * stay with it. Small neighbouring segments are packed into one chunk up to
 * {@code chunkSize} characters; a segment larger than that is split at blank
 * lines where possible, with a few lines of overlap between the pieces.</p>
 *
 * <p>Chunks always cover whole lines, so {@code startLine}/{@code endLine} are
 * exact. Each chunk's text starts with a {@code File:} header line and, for
 * code nested inside a class or similar, a {@code Scope:} line naming it, so the
 * embedding and keyword index both see where the code lives.</p>
 */
@Service
public class ChunkerService {

    private static final Logger log = LoggerFactory.getLogger(ChunkerService.class);

    public static final int DEFAULT_CHUNK_SIZE = 1500;
    public static final int DEFAULT_OVERLAP    = 150;

    static final String FILE_HEADER_PREFIX  = "File: ";
    static final String SCOPE_HEADER_PREFIX = "Scope: ";

    /** Lines that start a declaration worth a chunk boundary, by file extension. */
    private static final Map<String, Pattern> DECLARATION_PATTERNS = buildDeclarationPatterns();

    /** Languages where a leading {@code #} line is a comment (not a heading or preprocessor line). */
    private static final Set<String> HASH_COMMENT_EXTENSIONS =
            Set.of("py", "rb", "sh", "bash", "yaml", "yml", "toml", "ps1");

    /** Keywords that look like a call/definition but are control flow. */
    private static final Pattern CONTROL_FLOW = Pattern.compile(
            "^\\s*(if|for|foreach|while|switch|catch|return|else|do|try|using|lock|synchronized|when|match)\\b");

    public List<Chunk> chunkCodeFiles(List<CodeFile> files) {
        return chunkCodeFiles(files, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    public List<Chunk> chunkCodeFiles(List<CodeFile> files, int chunkSize, int overlap) {
        List<Chunk> all = files.parallelStream()
                .flatMap(file -> chunkFile(file, chunkSize, overlap).stream())
                .toList();
        log.info("Chunked {} files → {} chunks", files.size(), all.size());
        return all;
    }

    static List<Chunk> chunkFile(CodeFile file, int chunkSize, int overlap) {
        List<String> lines = file.content().lines().toList();
        if (lines.stream().allMatch(String::isBlank)) {
            return List.of();
        }
        Pattern declaration = declarationPattern(file.path());
        boolean[] isDeclaration = markDeclarations(lines, declaration);

        List<int[]> ranges = new ArrayList<>(); // [startLine, endLineExclusive], 0-based
        for (int[] segment : segments(lines, isDeclaration, file.path())) {
            ranges.addAll(splitSegment(lines, segment[0], segment[1], chunkSize, overlap));
        }
        List<int[]> packed = pack(lines, ranges, chunkSize);

        List<Chunk> chunks = new ArrayList<>(packed.size());
        for (int[] range : packed) {
            if (isBlankRange(lines, range[0], range[1])) {
                continue;
            }
            String code = String.join("\n", lines.subList(range[0], range[1]));
            if (code.length() > chunkSize * 2) {
                // A single huge line (minified code, data blobs): hard-split it.
                for (int start = 0; start < code.length(); start += chunkSize) {
                    String piece = code.substring(start, Math.min(start + chunkSize, code.length()));
                    chunks.add(buildChunk(file.path(), null, piece, chunks.size(), range[0], range[1]));
                }
                continue;
            }
            String scope = enclosingScope(lines, isDeclaration, range[0]);
            chunks.add(buildChunk(file.path(), scope, code, chunks.size(), range[0], range[1]));
        }
        return chunks;
    }

    // ── Segmentation ─────────────────────────────────────────────────────────

    private static boolean[] markDeclarations(List<String> lines, Pattern declaration) {
        boolean[] marks = new boolean[lines.size()];
        if (declaration == null) {
            return marks;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            marks[i] = declaration.matcher(line).find() && !CONTROL_FLOW.matcher(line).find();
        }
        return marks;
    }

    /** Declaration-delimited [start, end) line ranges, with leading comments pulled into each declaration. */
    private static List<int[]> segments(List<String> lines, boolean[] isDeclaration, String path) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 1; i < lines.size(); i++) {
            if (isDeclaration[i]) {
                int start = i;
                while (start - 1 > starts.get(starts.size() - 1)
                        && !isDeclaration[start - 1]
                        && isLeadingDecoration(lines.get(start - 1), path)) {
                    start--;
                }
                if (start > starts.get(starts.size() - 1)) {
                    starts.add(start);
                }
            }
        }
        List<int[]> segments = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : lines.size();
            segments.add(new int[]{starts.get(i), end});
        }
        return segments;
    }

    /** Comment, annotation, decorator or attribute lines that belong to the declaration below them. */
    private static boolean isLeadingDecoration(String line, String path) {
        String t = line.strip();
        if (t.isEmpty()) {
            return false;
        }
        if (t.startsWith("@") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
                || t.startsWith("#[") || t.startsWith("--") || t.startsWith("[")) {
            return true;
        }
        return t.startsWith("#") && HASH_COMMENT_EXTENSIONS.contains(extension(path));
    }

    /** Splits a segment longer than {@code chunkSize} into overlapping line windows. */
    private static List<int[]> splitSegment(List<String> lines, int start, int end,
                                            int chunkSize, int overlap) {
        if (rangeLength(lines, start, end) <= chunkSize) {
            return List.of(new int[]{start, end});
        }
        List<int[]> pieces = new ArrayList<>();
        int pieceStart = start;
        while (pieceStart < end) {
            int length = 0;
            int cut = pieceStart;
            int lastBlank = -1;
            while (cut < end && (cut == pieceStart || length + lines.get(cut).length() + 1 <= chunkSize)) {
                length += lines.get(cut).length() + 1;
                if (lines.get(cut).isBlank() && cut > pieceStart) {
                    lastBlank = cut;
                }
                cut++;
            }
            // Prefer ending at a blank line if it keeps at least half the window.
            if (cut < end && lastBlank > pieceStart
                    && rangeLength(lines, pieceStart, lastBlank) >= chunkSize / 2) {
                cut = lastBlank + 1;
            }
            pieces.add(new int[]{pieceStart, cut});
            if (cut >= end) {
                break;
            }
            int next = cut;
            int carried = 0;
            while (next - 1 > pieceStart && carried + lines.get(next - 1).length() + 1 <= overlap) {
                carried += lines.get(next - 1).length() + 1;
                next--;
            }
            pieceStart = next;
        }
        return pieces;
    }

    /** Greedily merges adjacent, non-overlapping ranges while they fit in {@code chunkSize}. */
    private static List<int[]> pack(List<String> lines, List<int[]> ranges, int chunkSize) {
        List<int[]> packed = new ArrayList<>();
        int[] current = null;
        for (int[] range : ranges) {
            if (current != null
                    && range[0] == current[1]
                    && rangeLength(lines, current[0], range[1]) <= chunkSize) {
                current[1] = range[1];
                continue;
            }
            if (current != null) {
                packed.add(current);
            }
            current = new int[]{range[0], range[1]};
        }
        if (current != null) {
            packed.add(current);
        }
        return packed;
    }

    /**
     * The nearest declaration above {@code line} with less indentation, i.e. the
     * class/module/impl the chunk sits in, or {@code null} at top level.
     */
    private static String enclosingScope(List<String> lines, boolean[] isDeclaration, int line) {
        int indent = firstCodeIndent(lines, line);
        if (indent <= 0) {
            return null;
        }
        for (int i = line - 1; i >= 0; i--) {
            if (isDeclaration[i] && indentOf(lines.get(i)) < indent) {
                String text = lines.get(i).strip();
                return text.length() > 160 ? text.substring(0, 160) : text;
            }
        }
        return null;
    }

    private static int firstCodeIndent(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return indentOf(lines.get(i));
            }
        }
        return 0;
    }

    /** Leading whitespace width, counting a tab as four columns. */
    private static int indentOf(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                width++;
            } else if (c == '\t') {
                width += 4;
            } else {
                break;
            }
        }
        return width;
    }

    private static Chunk buildChunk(String path, String scope, String code, int index,
                                    int startLine, int endLineExclusive) {
        StringBuilder text = new StringBuilder(FILE_HEADER_PREFIX).append(path).append('\n');
        if (scope != null) {
            text.append(SCOPE_HEADER_PREFIX).append(scope).append('\n');
        }
        text.append(code);
        return new Chunk(text.toString(), path, index, startLine + 1, endLineExclusive);
    }

    private static int rangeLength(List<String> lines, int start, int end) {
        int length = 0;
        for (int i = start; i < end; i++) {
            length += lines.get(i).length() + 1;
        }
        return length;
    }

    private static boolean isBlankRange(List<String> lines, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!lines.get(i).isBlank()) {
                return false;
            }
        }
        return true;
    }

    // ── Language patterns ────────────────────────────────────────────────────

    private static Pattern declarationPattern(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.equals("dockerfile")) {
            return DECLARATION_PATTERNS.get("dockerfile");
        }
        return DECLARATION_PATTERNS.get(extension(path));
    }

    private static String extension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private static Map<String, Pattern> buildDeclarationPatterns() {
        String jvmModifiers = "(?:(?:public|private|protected|internal|static|final|abstract|sealed|non-sealed"
                + "|open|override|suspend|data|inline|value|enum|annotation|companion|lateinit|async|virtual"
                + "|partial|readonly|unsafe|extern|synchronized|native|default|implicit|case|lazy)\\s+)*";
        Pattern jvm = Pattern.compile("^\\s*" + jvmModifiers
                + "(?:class|interface|enum|record|object|trait|struct|fun|def"
                + "|namespace|@interface)\\s+\\w+"
                // Methods: at least one modifier, a return type, a name and an open paren.
                + "|^\\s*(?:(?:public|private|protected|internal|static|final|abstract|synchronized"
                + "|default|override|virtual|async|native)\\s+)+[\\w<>\\[\\],.?\\s]*?\\w+\\s*\\(");
        Pattern swift = Pattern.compile("^\\s*" + jvmModifiers
                + "(?:fileprivate\\s+|mutating\\s+|@\\w+\\s+)*"
                + "(?:class|struct|enum|protocol|extension|func|actor|init)\\b");
        Pattern python = Pattern.compile("^\\s*(?:async\\s+def|def|class)\\s+\\w+");
        Pattern js = Pattern.compile(
                "^(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:abstract\\s+)?(?:async\\s+)?"
                + "(?:function\\*?|class|interface|type|enum|const|let|var|namespace|module)\\s+[\\w$]+"
                // Class members and object methods: name(args) { at modest indentation.
                + "|^\\s{1,8}(?:(?:public|private|protected|static|readonly|async|get|set|override)\\s+)*"
                + "[\\w$]+\\s*(?:<[^>]*>)?\\s*\\([^)]*\\)\\s*(?::\\s*[^={]+)?\\{\\s*$");
        Pattern go = Pattern.compile("^(?:func|type)\\s+");
        Pattern rust = Pattern.compile(
                "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+|const\\s+|unsafe\\s+|extern\\s+\"[^\"]*\"\\s+)*"
                + "(?:fn|struct|enum|trait|impl|mod|type|union|macro_rules!)\\b");
        Pattern cFamily = Pattern.compile(
                "^(?:class|struct|namespace|enum|union|typedef|template)\\b"
                // Top-level function definitions: type name(...) without a trailing semicolon.
                + "|^[A-Za-z_][\\w\\s\\*&:<>,]*[\\s\\*&]+[\\w:~]+\\s*\\([^;]*$");
        Pattern ruby = Pattern.compile("^\\s*(?:def|class|module)\\s+");
        Pattern php = Pattern.compile(
                "^\\s*(?:(?:public|private|protected|static|abstract|final|readonly)\\s+)*"
                + "(?:function|class|interface|trait|enum)\\s+\\w+");
        Pattern sql = Pattern.compile("(?i)^\\s*(?:create|alter|drop|insert|update|delete|with|select)\\b");
        Pattern markdown = Pattern.compile("^#{1,4}\\s+\\S");
        Pattern shell = Pattern.compile("^(?:function\\s+)?[\\w-]+\\s*\\(\\)\\s*\\{?|^function\\s+[\\w-]+");
        Pattern yaml = Pattern.compile("^[A-Za-z_][\\w.-]*\\s*:");
        Pattern toml = Pattern.compile("^\\[\\[?[^\\]]+\\]\\]?\\s*$");
        Pattern dockerfile = Pattern.compile("(?i)^FROM\\s+");

        return Map.ofEntries(
                Map.entry("java", jvm), Map.entry("kt", jvm), Map.entry("kts", jvm),
                Map.entry("scala", jvm), Map.entry("cs", jvm), Map.entry("swift", swift),
                Map.entry("py", python),
                Map.entry("js", js), Map.entry("jsx", js), Map.entry("ts", js), Map.entry("tsx", js),
                Map.entry("go", go), Map.entry("rs", rust),
                Map.entry("c", cFamily), Map.entry("h", cFamily),
                Map.entry("cpp", cFamily), Map.entry("hpp", cFamily),
                Map.entry("rb", ruby), Map.entry("php", php), Map.entry("sql", sql),
                Map.entry("md", markdown), Map.entry("sh", shell), Map.entry("bash", shell),
                Map.entry("yaml", yaml), Map.entry("yml", yaml), Map.entry("toml", toml),
                Map.entry("dockerfile", dockerfile));
    }
}
