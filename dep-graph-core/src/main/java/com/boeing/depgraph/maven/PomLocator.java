package com.boeing.depgraph.maven;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds every pom under a scan root.
 *
 * <p>Every pom, not just the ones a root aggregator lists. A large estate is a set of
 * repositories that were never a single reactor — this one is seven — and walking
 * {@code <modules>} from a single root would find nothing at all. The tree is the source
 * of truth about what is there; the poms say how it is built.
 */
public final class PomLocator {

    private static final Logger log = LoggerFactory.getLogger(PomLocator.class);

    /**
     * Directories never worth descending into.
     *
     * <p>{@code target} matters most: a build copies poms into {@code target/classes} and
     * into unpacked dependencies, and counting those would report the same module several
     * times at several versions — a fabricated conflict, which is the worst kind of
     * finding this tool could produce.
     */
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
        "target", "build", "out", "bin",
        ".git", ".svn", ".hg", ".idea", ".vscode", ".mvn",
        "node_modules", "dist", ".venv", "venv", "__pycache__");

    private static final int MAX_DEPTH = 24;

    /**
     * Directory pairs that mark a test fixture rather than a module of the project.
     *
     * <p>A scanner's test resources contain deliberately broken and deliberately conflicting
     * poms - that is their job. Counted as modules they are indistinguishable from real
     * ones, and on this estate they supplied three of five reported version conflicts and
     * nine of nine unreadable poms. The estate's other two scanners settled this the same
     * way: test types are skipped entirely.
     *
     * <p>Matched on the path <em>relative to the scan root</em>, which is what makes it safe
     * to point the tool straight at a fixture. Scan {@code .../sample-estate} and its poms
     * are the project; scan the repository above it and they are fixtures again.
     */
    private static final List<String> TEST_PATH_SEGMENTS = List.of(
        "src/test", "src/it", "src/integration-test");

    private PomLocator() {
    }

    public static List<Path> findPoms(Path root) throws IOException {
        return findPoms(root, false);
    }

    /**
     * @param includeTestFixtures true to keep poms under {@code src/test} and friends. Off
     *                            by default; worth switching on to analyse a fixture estate
     *                            in place without pointing the scan root at it
     */
    public static List<Path> findPoms(Path root, boolean includeTestFixtures) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Scan root is not a directory: " + root);
        }

        List<Path> poms = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString();
                return SKIP_DIRECTORIES.contains(name) || name.startsWith(".")
                    ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if ("pom.xml".equals(file.getFileName().toString())) {
                    poms.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            /**
             * An unreadable directory is skipped, not fatal. Scan roots are mounted
             * read-only and frequently contain something the container's user cannot see.
             */
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                log.debug("Skipping unreadable path {}: {}", file, e.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        poms.sort(Path::compareTo);

        if (includeTestFixtures) {
            log.info("Found {} pom files under {}", poms.size(), root);
            return poms;
        }

        Path base = root.toAbsolutePath().normalize();
        List<Path> kept = new ArrayList<>();
        int fixtures = 0;
        for (Path pom : poms) {
            if (isTestFixture(base, pom)) {
                fixtures++;
            } else {
                kept.add(pom);
            }
        }
        if (fixtures > 0) {
            log.info("Found {} pom files under {}, skipping {} test fixture(s)",
                poms.size(), root, fixtures);
        } else {
            log.info("Found {} pom files under {}", poms.size(), root);
        }
        return kept;
    }

    static boolean isTestFixture(Path root, Path pom) {
        String relative;
        try {
            relative = root.relativize(pom.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            // Different roots - a symlink out of the tree. Nothing to judge it against.
            return false;
        }
        String normalised = relative.replace('\\', '/');
        for (String segment : TEST_PATH_SEGMENTS) {
            if (normalised.startsWith(segment + "/") || normalised.contains("/" + segment + "/")) {
                return true;
            }
        }
        return false;
    }
}
