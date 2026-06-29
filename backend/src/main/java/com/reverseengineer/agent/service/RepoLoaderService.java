package com.reverseengineer.agent.service;

import com.reverseengineer.agent.config.AppProperties;
import com.reverseengineer.agent.model.CodeFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Service
public class RepoLoaderService {

    private static final Logger log = LoggerFactory.getLogger(RepoLoaderService.class);

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx",
            ".go", ".rs", ".cs", ".cpp", ".c", ".h", ".hpp",
            ".rb", ".php", ".swift", ".kt", ".kts", ".scala",
            ".html", ".css", ".scss", ".sass", ".less",
            ".json", ".yaml", ".yml", ".xml", ".toml",
            ".md", ".txt", ".sql", ".sh", ".bash", ".ps1",
            ".gradle", ".properties", ".env", ".cfg", ".ini",
            ".dockerfile", ".makefile"
    );

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "venv", ".venv", "__pycache__",
            "target", "build", "dist", ".gradle", ".idea", ".vscode",
            "vendor", ".bundle", "Pods", ".next", ".nuxt",
            "coverage", ".coverage", "htmlcov", "site-packages"
    );

    private final AppProperties props;

    public RepoLoaderService(AppProperties props) {
        this.props = props;
    }

    public void validateRepoUrl(String repoUrl) {
        URI uri = URI.create(repoUrl);
        String host = uri.getHost();
        if (host == null || !props.allowedRepoHosts().contains(host)) {
            throw new IllegalArgumentException(
                    "Repository host '%s' is not allowed.".formatted(host));
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only HTTPS repository URLs are allowed.");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Repository URLs must not include credentials.");
        }
        // Block non-standard ports to prevent SSRF against internal services.
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new IllegalArgumentException(
                    "Non-standard ports are not allowed in repository URLs.");
        }
    }

    public String cloneRepo(String repoUrl, Path localPath) throws GitAPIException, IOException {
        if (Files.exists(localPath)) {
            log.info("Removing existing repo at {}", localPath);
            deleteDirectory(localPath);
        }
        Files.createDirectories(localPath);

        log.info("Cloning {} into {} ...", repoUrl, localPath);
        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(localPath.toFile())
                .setDepth(1)
                .setNoTags()
                .setTimeout(60)
                .call()) {
            var headId = git.getRepository().resolve("HEAD");
            String sha = headId != null ? headId.getName() : null;
            log.info("Clone complete, HEAD={}", sha);
            return sha;
        }
    }

    public List<CodeFile> loadCodeFiles(Path repoPath) throws IOException {
        List<CodeFile> result = new ArrayList<>();
        int[] totalChars = {0};
        Path canonicalRepo = repoPath.toRealPath();

        Files.walkFileTree(repoPath, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Guard against symlinks that escape the repo directory (path traversal).
                try {
                    if (!file.toRealPath().startsWith(canonicalRepo)) {
                        log.warn("Skipping symlink that escapes repo boundary: {}", file);
                        return FileVisitResult.CONTINUE;
                    }
                } catch (IOException e) {
                    log.debug("Cannot resolve real path for {}, skipping: {}", file, e.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                if (result.size() >= props.maxFilesToLoad()) return FileVisitResult.TERMINATE;
                if (!isCodeFile(file)) return FileVisitResult.CONTINUE;
                if (attrs.size() > props.maxFileBytes()) {
                    log.debug("Skipping oversized file: {}", file);
                    return FileVisitResult.CONTINUE;
                }

                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (totalChars[0] + content.length() > props.maxTotalSourceChars()) {
                        log.info("Total source-char limit reached; stopping file load.");
                        return FileVisitResult.TERMINATE;
                    }
                    totalChars[0] += content.length();
                    String relPath = repoPath.relativize(file).toString().replace('\\', '/');
                    result.add(new CodeFile(relPath, content));
                } catch (MalformedInputException e) {
                    log.debug("Skipping binary/non-UTF8 file: {}", file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Cannot read {}: {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("Loaded {} source files ({} chars total)", result.size(), totalChars[0]);
        return result;
    }

    public String buildRepoTree(Path repoPath) {
        if (!Files.exists(repoPath)) return "Repository tree unavailable.";

        var sb = new StringBuilder();
        int[] count = {0};

        try {
            Files.walkFileTree(repoPath, Set.of(), 3, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                    if (count[0]++ >= 250) return FileVisitResult.TERMINATE;
                    int depth = repoPath.relativize(dir).getNameCount();
                    sb.append("  ".repeat(depth)).append(name).append("/\n");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (count[0]++ >= 250) return FileVisitResult.TERMINATE;
                    int depth = repoPath.relativize(file).getNameCount();
                    sb.append("  ".repeat(depth - 1)).append(file.getFileName()).append("\n");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not build repo tree: {}", e.getMessage());
            return "Repository tree unavailable.";
        }

        return sb.toString();
    }

    private static boolean isCodeFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && CODE_EXTENSIONS.contains(name.substring(dot))) return true;
        return name.equals("dockerfile") || name.equals("makefile")
                || name.equals("gemfile") || name.equals("rakefile");
    }

    static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                file.toFile().setWritable(true); // git objects are read-only on Windows
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
