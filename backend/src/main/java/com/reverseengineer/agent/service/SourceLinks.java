package com.reverseengineer.agent.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Builds browser links to a file (and line range) in a hosted repository.
 *
 * <p>Links are pinned to the ingested commit so the line numbers match the code
 * that was indexed, even after the branch moves on. Supports GitHub, GitLab and
 * Bitbucket URL layouts; other hosts get no link.</p>
 */
final class SourceLinks {

    private SourceLinks() {}

    /**
     * @param commitSha ingested commit; {@code null} links to the default branch head
     * @param startLine first line (1-based), or {@code null}/{@code < 1} for no line anchor
     * @param endLine   last line, or {@code null} for a single-line anchor
     * @return the URL, or {@code null} when the host is unsupported or the inputs are unusable
     */
    static String fileUrl(String repoUrl, String commitSha, String filePath,
                          Integer startLine, Integer endLine) {
        if (repoUrl == null || filePath == null || filePath.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(repoUrl.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
        String repoPath = uri.getPath() == null ? "" : uri.getPath()
                .replaceAll("/+$", "")
                .replaceAll("\\.git$", "");
        if (repoPath.isEmpty() || repoPath.equals("/")) {
            return null;
        }
        String ref = commitSha != null && commitSha.matches("[0-9a-fA-F]{7,64}") ? commitSha : "HEAD";
        String path = encodePath(filePath);
        boolean hasLines = startLine != null && startLine > 0;
        int end = endLine != null && endLine >= (hasLines ? startLine : 1) ? endLine : (hasLines ? startLine : 0);

        return switch (host) {
            case "github.com", "www.github.com" -> "https://github.com" + repoPath + "/blob/" + ref + "/" + path
                    + (hasLines ? "#L" + startLine + (end > startLine ? "-L" + end : "") : "");
            case "gitlab.com", "www.gitlab.com" -> "https://gitlab.com" + repoPath + "/-/blob/" + ref + "/" + path
                    + (hasLines ? "#L" + startLine + (end > startLine ? "-" + end : "") : "");
            case "bitbucket.org", "www.bitbucket.org" -> "https://bitbucket.org" + repoPath + "/src/" + ref + "/" + path
                    + (hasLines ? "#lines-" + startLine + (end > startLine ? ":" + end : "") : "");
            default -> null;
        };
    }

    /** Percent-encodes each path segment (spaces, '#', '?', ...) while keeping the slashes. */
    private static String encodePath(String filePath) {
        return Arrays.stream(filePath.replace('\\', '/').replaceAll("^/+", "").split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }
}
