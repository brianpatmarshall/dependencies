package com.boeing.depgraph.code;

import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.Gav;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the jar behind each declared dependency, in the local repository.
 *
 * <p>Pure path arithmetic against the standard {@code group/artifact/version} layout — no
 * resolution, no network, no download. The build plane has already decided which version
 * each dependency is on, so the only question left is where that file sits, and the answer
 * is a formula.
 *
 * <p>A jar that is not there is skipped silently. An unbuilt or never-downloaded artifact
 * is an ordinary state for a read-only mounted repository, and failing a scan over one
 * would make the code plane hostage to whatever the last build happened to fetch.
 */
public final class ArtifactJars {

    private static final Logger log = LoggerFactory.getLogger(ArtifactJars.class);

    private ArtifactJars() {
    }

    /**
     * @param localRepository the {@code ~/.m2/repository} or read-only {@code /m2} mount
     * @return one jar per distinct artifact version, deduplicated and existing
     */
    public static List<Path> resolve(List<DeclaredDependency> dependencies, Path localRepository) {
        Set<Gav> distinct = new LinkedHashSet<>();
        for (DeclaredDependency dependency : dependencies) {
            // Snapshots of the project's own modules are read from target/classes instead;
            // a stale installed snapshot would describe code nobody is looking at.
            if (dependency.gav().version() != null && !dependency.gav().version().endsWith("-SNAPSHOT")) {
                distinct.add(dependency.gav());
            }
        }

        Set<Path> jars = new LinkedHashSet<>();
        int missing = 0;
        for (Gav gav : distinct) {
            Path jar = pathOf(gav, localRepository);
            if (Files.isRegularFile(jar)) {
                jars.add(jar);
            } else {
                missing++;
            }
        }

        log.info("Resolved {} of {} dependency jars under {}", jars.size(), distinct.size(), localRepository);
        if (missing > 0) {
            log.debug("{} artifact(s) had no jar in the local repository", missing);
        }
        return List.copyOf(jars);
    }

    static Path pathOf(Gav gav, Path localRepository) {
        return localRepository
            .resolve(gav.groupId().replace('.', '/'))
            .resolve(gav.artifactId())
            .resolve(gav.version())
            .resolve(gav.artifactId() + "-" + gav.version() + ".jar");
    }
}
