package com.boeing.depgraph.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * What the user typed.
 *
 * <p>Hand-parsed rather than picocli or commons-cli. The option set is small and stable,
 * and a dependency whose only job is to read eight flags would be carried by every
 * consumer of {@code dep-graph-core} - including the backend, which will never parse an
 * argument in its life.
 *
 * @param path the one required argument: a repository root, or a pom.xml. Any Maven
 *             project, single-module or multi-module - the analyser does not care which
 *             and neither does this
 */
public record CliOptions(
    Path path,
    Path localRepository,
    boolean offline,
    boolean buildPlaneOnly,
    boolean json,
    boolean printBom,
    boolean quiet,
    boolean help
) {

    public static final String USAGE = """
        depgraph - analyse a Maven project's dependencies

        USAGE
            depgraph [options] <path>

        ARGUMENTS
            <path>                 Repository root, or a pom.xml. Required.
                                   Works on any Maven project: a single pom or a
                                   multi-module reactor.

        OPTIONS
            --local-repo <path>    Local Maven repository to resolve parents and BOMs
                                   from. Default: $M2_REPO, else ~/.m2/repository
            --offline              Never reach the network. Faster and reproducible, but
                                   fails on any parent or BOM not already in the local
                                   repository
            --build-plane-only     Read poms only; skip the bytecode. Use for a project
                                   that has not been compiled
            --bom                  Print the proposed <dependencyManagement> block
            --json                 Machine-readable output on stdout
            --quiet                No progress narration on stderr
            -h, --help             This text

        OUTPUT
            Narration goes to stderr, data to stdout, so `depgraph . --json > out.json`
            does what it looks like.

        EXAMPLES
            depgraph ~/src/my-service
            depgraph ~/src/my-service/pom.xml --bom
            depgraph ~/src/monorepo --build-plane-only --json > deps.json
        """;

    /**
     * @throws IllegalArgumentException with a message meant to be printed as-is
     */
    public static CliOptions parse(String[] args) {
        Path path = null;
        Path localRepository = null;
        boolean offline = false;
        boolean buildPlaneOnly = false;
        boolean json = false;
        boolean printBom = false;
        boolean quiet = false;

        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    return new CliOptions(null, null, false, false, false, false, false, true);
                }
                case "--offline" -> offline = true;
                case "--build-plane-only" -> buildPlaneOnly = true;
                case "--json" -> json = true;
                case "--bom" -> printBom = true;
                case "--quiet" -> quiet = true;
                case "--local-repo" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--local-repo needs a path");
                    }
                    localRepository = Paths.get(args[i]);
                }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    positional.add(arg);
                }
            }
        }

        if (positional.isEmpty()) {
            throw new IllegalArgumentException("No path given. Point depgraph at a Maven project.");
        }
        if (positional.size() > 1) {
            // Almost always an unquoted glob or a typo'd flag. Saying which extra
            // arguments were seen is more use than "too many arguments".
            throw new IllegalArgumentException(
                "Expected one path but got " + positional.size() + ": " + String.join(", ", positional));
        }
        path = Paths.get(positional.get(0));

        return new CliOptions(path, localRepository, offline, buildPlaneOnly, json,
            printBom, quiet, false);
    }

    /** The local repository to use, resolving the default if none was given. */
    public Path effectiveLocalRepository() {
        if (localRepository != null) {
            return localRepository;
        }
        String configured = System.getenv("M2_REPO");
        return configured != null && !configured.isBlank()
            ? Paths.get(configured)
            : Paths.get(System.getProperty("user.home"), ".m2", "repository");
    }
}
