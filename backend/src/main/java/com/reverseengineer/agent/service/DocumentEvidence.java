package com.reverseengineer.agent.service;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chooses the code evidence a generated document is written from.
 *
 * <p>A reverse-engineering document should be built mostly from source code.
 * Left alone, broad retrieval queries also pull in build files, CI config,
 * licences and tests, which crowd out the code. So candidates are sorted into
 * {@link Kind}s and each non-source kind gets a share cap. Boilerplate
 * ({@link Kind#META}) is only used when nothing else is left; the file tree and
 * README excerpt in the prompt already describe the repository layout.</p>
 */
public final class DocumentEvidence {

    private DocumentEvidence() {}

    /**
     * Topics the document covers, each searched separately so every section
     * gets evidence instead of one blended query matching mostly generic files.
     */
    static final List<String> ASPECTS = List.of(
            "application entry point main function startup bootstrap initialization",
            "HTTP routes controllers request handlers API endpoints public interface CLI commands",
            "core business logic services domain classes main algorithms",
            "data models schemas database persistence storage repository queries",
            "configuration settings environment variables options loading",
            "authentication authorization security permissions validation secrets",
            "error handling exceptions retries logging",
            "external integrations HTTP clients third-party APIs messaging background jobs");

    public enum Kind {
        /** Application and library code. Uncapped. */
        SOURCE(1.0),
        /** Build, dependency and runtime configuration. */
        CONFIG(0.2),
        /** Tests and fixtures. */
        TEST(0.15),
        /** Prose documentation. */
        DOCS(0.15),
        /** Licences, CI/devcontainer/editor setup, lockfiles: filler only. */
        META(0.0);

        final double share;

        Kind(double share) {
            this.share = share;
        }
    }

    private static final Set<String> META_FILES = Set.of(
            "license", "license.txt", "license.md", "licence", "copying", "notice",
            "code_of_conduct.md", "contributing.md", "contributing.rst", "security.md",
            "changes.md", "changes.rst", "changelog.md", "changelog", "history.md",
            ".gitignore", ".gitattributes", ".editorconfig", ".pre-commit-config.yaml",
            ".readthedocs.yaml", ".readthedocs.yml", ".prettierrc", ".eslintignore",
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock", "pipfile.lock",
            "cargo.lock", "go.sum", "gemfile.lock", "composer.lock", "uv.lock", "requirements-dev.txt");

    private static final List<String> META_DIRS = List.of(
            ".github/", ".gitlab/", ".circleci/", ".devcontainer/", ".vscode/", ".idea/", ".husky/");

    private static final Pattern TEST_PATH = Pattern.compile(
            "(^|/)(tests?|__tests__|spec|specs|testing|fixtures|e2e)/"
            + "|(^|/)test_[^/]+$|_test\\.[a-z]+$|\\.(test|spec)\\.[a-z]+$|(^|/)[^/]+Tests?\\.(java|kt|cs|scala)$"
            + "|(^|/)conftest\\.py$");

    private static final Set<String> CONFIG_FILES = Set.of(
            "pyproject.toml", "setup.py", "setup.cfg", "package.json", "tsconfig.json", "pom.xml",
            "build.gradle", "build.gradle.kts", "settings.gradle", "cargo.toml", "go.mod", "gemfile",
            "composer.json", "dockerfile", "docker-compose.yml", "docker-compose.yaml", "makefile",
            "requirements.txt", "cmakelists.txt", "tox.ini", "vite.config.js", "vite.config.ts", "webpack.config.js",
            ".env.example", "procfile", "render.yaml", "fly.toml", "netlify.toml", "vercel.json");

    private static final Set<String> CONFIG_EXTENSIONS = Set.of(
            "yml", "yaml", "toml", "ini", "cfg", "properties", "conf", "env", "gradle", "xml");

    private static final Set<String> DOC_EXTENSIONS = Set.of("md", "rst", "txt", "adoc", "markdown");

    public static Kind classify(String filePath) {
        String path = Objects.toString(filePath, "").replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = path.substring(path.lastIndexOf('/') + 1);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";

        if (META_FILES.contains(name) || META_DIRS.stream().anyMatch(dir -> path.startsWith(dir) || path.contains("/" + dir))) {
            return Kind.META;
        }
        // TEST_PATH checks the original case for Java-style FooTest.java names.
        if (TEST_PATH.matcher(Objects.toString(filePath, "").replace('\\', '/')).find()
                || TEST_PATH.matcher(path).find()) {
            return Kind.TEST;
        }
        if (CONFIG_FILES.contains(name)) {
            return Kind.CONFIG;
        }
        if (DOC_EXTENSIONS.contains(ext) || path.startsWith("docs/") || path.startsWith("doc/")) {
            return Kind.DOCS;
        }
        return CONFIG_EXTENSIONS.contains(ext) ? Kind.CONFIG : Kind.SOURCE;
    }

    /**
     * Picks up to {@code limit} chunks from {@code ranked} (best first):
     * <ol>
     *   <li>one chunk per file, respecting the per-kind caps, to cover many files;</li>
     *   <li>further chunks from already-chosen files, still within the caps;</li>
     *   <li>if the repository is too small to fill {@code limit} that way, the
     *       remaining candidates in rank order regardless of kind.</li>
     * </ol>
     */
    static List<Document> select(List<Document> ranked, int limit) {
        Map<Kind, Integer> caps = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            caps.put(kind, kind == Kind.SOURCE ? limit : (int) Math.floor(limit * kind.share));
        }
        Map<Kind, Integer> used = new EnumMap<>(Kind.class);
        Set<Document> selected = new LinkedHashSet<>();
        Set<String> files = new HashSet<>();

        for (boolean firstPerFile : new boolean[]{true, false}) {
            for (Document document : ranked) {
                if (selected.size() >= limit) break;
                if (selected.contains(document)) continue;
                String file = fileKey(document);
                if (firstPerFile && files.contains(file)) continue;
                Kind kind = classify(Objects.toString(document.getMetadata().get("file_path"), ""));
                if (used.getOrDefault(kind, 0) >= caps.get(kind)) continue;
                selected.add(document);
                files.add(file);
                used.merge(kind, 1, Integer::sum);
            }
        }
        for (Document document : ranked) {
            if (selected.size() >= limit) break;
            selected.add(document);
        }
        return new ArrayList<>(selected);
    }

    private static String fileKey(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return Objects.toString(metadata.get("project_id"), "") + ":"
                + Objects.toString(metadata.get("file_path"), "");
    }
}
