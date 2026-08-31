package com.reverseengineer.agent.service;

import com.reverseengineer.agent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * Housekeeping for the local clone directory. {@link RepoLoaderService} deletes a
 * clone on explicit project delete and overwrites it on re-ingest, but an ingest
 * that fails after cloning (or a project deleted straight from the database)
 * leaves an orphan directory behind. This sweep removes clone directories that no
 * longer correspond to a registered project.
 */
@Service
public class RepoMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(RepoMaintenanceService.class);

    /** Skip very recent directories so an in-progress ingest is never touched. */
    private static final Duration MIN_AGE = Duration.ofMinutes(30);

    private final AppProperties props;
    private final ProjectRegistry registry;
    private final RepoLoaderService repoLoader;

    public RepoMaintenanceService(AppProperties props,
                                  ProjectRegistry registry,
                                  RepoLoaderService repoLoader) {
        this.props = props;
        this.registry = registry;
        this.repoLoader = repoLoader;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    public void sweepOrphanClones() {
        Path root = Path.of(props.repoDir());
        if (!Files.isDirectory(root)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - MIN_AGE.toMillis();
        int removed = 0;
        try (Stream<Path> entries = Files.list(root)) {
            for (Path dir : (Iterable<Path>) entries::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String projectId = dir.getFileName().toString();
                if (registry.isKnownProjectId(projectId)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(dir).toMillis() > cutoff) {
                        continue; // still fresh — possibly an ingest in flight
                    }
                } catch (IOException e) {
                    continue;
                }
                repoLoader.deleteProjectClone(projectId);
                removed++;
            }
        } catch (IOException e) {
            log.warn("Orphan clone sweep failed: {}", e.getMessage());
            return;
        }
        if (removed > 0) {
            log.info("Orphan clone sweep removed {} directory(ies).", removed);
        }
    }
}
