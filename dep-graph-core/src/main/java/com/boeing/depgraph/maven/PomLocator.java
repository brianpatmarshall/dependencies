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

    private PomLocator() {
    }

    public static List<Path> findPoms(Path root) throws IOException {
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
        log.info("Found {} pom files under {}", poms.size(), root);
        return poms;
    }
}
