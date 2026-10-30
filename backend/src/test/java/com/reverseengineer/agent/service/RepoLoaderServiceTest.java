package com.reverseengineer.agent.service;

import com.reverseengineer.agent.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RepoLoaderServiceTest {

    private final RepoLoaderService loader = new RepoLoaderService(new AppProperties(
            null, List.of(), null, 0, 524_288, 0, 0, 0, 0, 0, 0, 0,
            false, null, null, null, null, null, null, null));

    @Test
    void readsPlainReadme(@TempDir Path repo) throws IOException {
        Files.writeString(repo.resolve("README.md"), "# Hello\n");
        assertEquals("# Hello", loader.readReadme(repo, 1000));
    }

    @Test
    void ignoresReadmeSymlinkedOutsideTheRepo(@TempDir Path dir) throws IOException {
        Path repo = Files.createDirectory(dir.resolve("repo"));
        Path secret = Files.writeString(dir.resolve("secret.env"), "JWT_SECRET=leaked");
        assumeTrue(trySymlink(repo.resolve("README.md"), secret),
                "symlinks not permitted on this machine");

        assertNull(loader.readReadme(repo, 1000));
    }

    /** Windows only allows symlinks with Developer Mode or admin rights. */
    private static boolean trySymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
