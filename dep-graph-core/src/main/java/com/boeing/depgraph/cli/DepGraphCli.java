package com.boeing.depgraph.cli;

import com.boeing.depgraph.conflict.BomXmlWriter;
import com.boeing.depgraph.scanner.BuildPlaneScanner;
import com.boeing.depgraph.scanner.NotAMavenProjectException;
import com.boeing.depgraph.scanner.ProjectScanner;
import com.boeing.depgraph.scanner.ScanResult;

import java.io.PrintStream;

/**
 * Point it at any Maven project.
 *
 * <p>One required argument, and the analyser does not care whether what it finds is a
 * single pom or a two-hundred-module reactor — the same walk, the same effective models,
 * the same conflict table. A single-module project simply has nobody to disagree with
 * about a version, and what it gets instead is the hoist: every version it pins by hand,
 * collected into a block.
 *
 * <h2>Streams</h2>
 * Narration to stderr, data to stdout. That is what makes
 * {@code depgraph . --json > out.json} do what it looks like, and it is the convention
 * the rest of the estate's CLIs already follow.
 *
 * <h2>Exit codes</h2>
 * <pre>
 *   0  scanned
 *   1  bad usage
 *   2  not a Maven project
 *   3  the scan failed
 * </pre>
 * Distinct codes because a script that wraps this wants to tell "you typed the path
 * wrong" apart from "the tool broke", and a single non-zero code makes that impossible.
 */
public final class DepGraphCli {

    /** Code-plane edges included in JSON output. The counts beside them stay exact. */
    private static final int JSON_EDGE_LIMIT = 5_000;

    private DepGraphCli() {
    }

    /**
     * Logging config shipped inside the shaded jar.
     *
     * <p>Named so that it is never picked up automatically. A library that ships a
     * {@code logback.xml} decides the logging of everything that depends on it, and this
     * one is a dependency of the backend.
     */
    private static final String LOGBACK_CONFIG = "depgraph-cli-logback.xml";

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Must run before the first logger exists, which is why it is the first statement of
     * {@code run} and why this class holds no static logger of its own.
     */
    private static void configureLogging(boolean quiet) {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", LOGBACK_CONFIG);
        }
        if (quiet) {
            System.setProperty("depgraph.log.level", "ERROR");
        }
    }

    /** Separated from {@code main} so it is testable without exiting the JVM. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
            configureLogging(options.quiet());
        } catch (IllegalArgumentException e) {
            err.println("depgraph: " + e.getMessage());
            err.println();
            err.println(CliOptions.USAGE);
            return 1;
        }

        if (options.help()) {
            out.println(CliOptions.USAGE);
            return 0;
        }

        try {
            ScanResult result = scan(options, err);

            if (options.json()) {
                out.println(JsonReport.write(result, JSON_EDGE_LIMIT));
            } else {
                TextReport.print(result, out);
            }
            if (options.printBom()) {
                if (!options.json()) {
                    out.println();
                }
                out.println(BomXmlWriter.toXml(result.proposal()));
            }
            return 0;

        } catch (NotAMavenProjectException e) {
            // The single most likely thing to go wrong when someone types a path.
            err.println("depgraph: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            err.println("depgraph: scan failed - " + e);
            return 3;
        }
    }

    private static ScanResult scan(CliOptions options, PrintStream err) {
        if (!options.quiet()) {
            err.printf("Scanning %s%n", options.path());
            err.printf("Local repository: %s%s%n", options.effectiveLocalRepository(),
                options.offline() ? " (offline)" : "");
        }

        if (options.buildPlaneOnly()) {
            return BuildPlaneScanner
                .withLocalRepository(options.effectiveLocalRepository(), options.offline())
                .scan(options.path());
        }
        return ProjectScanner
            .withLocalRepository(options.effectiveLocalRepository(), options.offline())
            .scan(options.path());
    }
}
