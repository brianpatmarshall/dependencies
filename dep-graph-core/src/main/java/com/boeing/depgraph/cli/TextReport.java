package com.boeing.depgraph.cli;

import com.boeing.depgraph.model.ArtifactUsage;
import com.boeing.depgraph.model.BomProposal;
import com.boeing.depgraph.model.ModuleRelation;
import com.boeing.depgraph.model.TypeEdge;
import com.boeing.depgraph.model.TypeRelation;
import com.boeing.depgraph.model.VersionUse;
import com.boeing.depgraph.scanner.ScanResult;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The human-readable report.
 *
 * <p>Ordered by what someone would act on, not by what was easiest to compute:
 * conflicts that need a decision come before counts, and the artifacts the proposal
 * cannot fix come before the ones it can. A report whose first screen is statistics is a
 * report people learn to scroll past.
 */
final class TextReport {

    private TextReport() {
    }

    static void print(ScanResult result, PrintStream out) {
        out.printf("%s%n%s%n", result.projectName(), result.projectPath());

        buildPlane(result, out);
        conflicts(result, out);
        proposal(result, out);
        codePlane(result, out);
        problems(result, out);
    }

    // ------------------------------------------------------------------

    private static void buildPlane(ScanResult result, PrintStream out) {
        ScanResult.ScanStats stats = result.stats();
        long aggregators = result.modules().stream().filter(m -> m.aggregator()).count();

        out.printf("%nBUILD PLANE%n");
        out.printf("  %-24s %d%s%n", "modules", stats.modulesRead(),
            aggregators > 0 ? " (" + aggregators + " aggregator)" : "");
        out.printf("  %-24s %d%n", "dependencies", stats.dependencyCount());
        out.printf("  %-24s %d (%d BOM imports)%n", "managed versions",
            stats.managedCount(), stats.bomImportCount());
        out.printf("  %-24s %d%n", "distinct artifacts", stats.artifactCount());

        Map<ModuleRelation, Long> byRelation = result.links().stream()
            .collect(Collectors.groupingBy(l -> l.relation(), TreeMap::new, Collectors.counting()));
        if (!byRelation.isEmpty()) {
            out.printf("  %-24s %s%n", "pom relationships", byRelation.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", ")));
        }
        out.printf("  %-24s %d ms%n", "scanned in", stats.durationMillis());
    }

    private static void conflicts(ScanResult result, PrintStream out) {
        List<ArtifactUsage> conflicts = result.artifacts().stream()
            .filter(ArtifactUsage::isConflict)
            .sorted(Comparator.comparingInt((ArtifactUsage a) -> a.severity().weight()).reversed())
            .toList();

        out.printf("%nCONFLICTS  %d%n", conflicts.size());
        if (conflicts.isEmpty()) {
            // Worth saying out loud. In a single-module project it is the only possible
            // answer, and silence would read as a tool that failed to look.
            out.printf("  Every artifact is on one version.%n");
            return;
        }
        for (ArtifactUsage usage : conflicts) {
            out.printf("  [%s] %s%s%n", usage.severity(), usage.key(),
                usage.held() ? "  (held)" : "");
            for (VersionUse version : usage.versions()) {
                out.printf("      %-20s %s%n", version.version(),
                    abbreviate(version.modules(), 3));
            }
        }
    }

    private static void proposal(ScanResult result, PrintStream out) {
        BomProposal proposal = result.proposal();
        out.printf("%nPROPOSED BOM  %d entries, %d need review%n",
            proposal.entries().size(), proposal.reviewCount());

        proposal.entries().stream()
            .sorted(Comparator.comparingInt((BomProposal.Entry e) -> e.severity().weight()).reversed())
            .limit(20)
            .forEach(e -> out.printf("  %-8s %-50s %s%n", e.severity(), e.key(), e.version()));
        if (proposal.entries().size() > 20) {
            out.printf("  ... %d more (--bom prints them all as XML)%n",
                proposal.entries().size() - 20);
        }

        if (!proposal.excluded().isEmpty()) {
            out.printf("%n  NOT FIXED BY THIS BOM  %d%n", proposal.excluded().size());
            proposal.excluded().forEach(e -> out.printf("    %s%n      %s%n", e.key(), e.reason()));
        }
    }

    private static void codePlane(ScanResult result, PrintStream out) {
        if (!result.hasCodePlane()) {
            out.printf("%nCODE PLANE  not read (--build-plane-only)%n");
            return;
        }
        var stats = result.code().stats();
        out.printf("%nCODE PLANE%n");
        out.printf("  %-24s %d (%d from jars, read surface-only)%n", "types",
            stats.typesFound(), stats.thirdPartyTypes());
        out.printf("  %-24s %d methods, %d fields%n", "members",
            stats.methodsFound(), stats.fieldsFound());
        out.printf("  %-24s %d distinct, %d occurrences%n", "type relationships",
            stats.edgeCount(), stats.edgeOccurrences());

        Map<TypeRelation.Tier, Long> byTier = result.code().edges().stream()
            .collect(Collectors.groupingBy(e -> e.relation().tier(), TreeMap::new, Collectors.counting()));
        byTier.forEach((tier, count) ->
            out.printf("      %-20s %,d%n", tier, count));

        out.printf("  %-24s %d ms%n", "scanned in", stats.durationMillis());

        if (stats.inlinedConstants() > 0) {
            // The blind spot, stated every run rather than buried in documentation.
            out.printf("  %-24s %d static final fields javac inlines - their uses leave%n",
                "not visible", stats.inlinedConstants());
            out.printf("  %-24s no trace, so \"unused dependency\" is a question, not a verdict%n", "");
        }
        if (stats.modulesMissingClasses() > 0) {
            out.printf("  %-24s %d module(s) have no target/classes and contributed nothing%n",
                "not built", stats.modulesMissingClasses());
            result.code().modulesWithoutClasses().stream().limit(10)
                .forEach(m -> out.printf("      %s%n", m));
        }
    }

    private static void problems(ScanResult result, PrintStream out) {
        if (result.problems().isEmpty()) {
            return;
        }
        out.printf("%nCOULD NOT READ  %d pom(s)%n", result.problems().size());
        for (ScanResult.ModuleProblem problem : result.problems()) {
            out.printf("  %s%n    %s%n", problem.relativePath(), problem.messages().get(0));
        }
    }

    // ------------------------------------------------------------------

    private static String abbreviate(List<String> values, int limit) {
        if (values.size() <= limit) {
            return String.join(", ", values);
        }
        return String.join(", ", values.subList(0, limit))
            + " and " + (values.size() - limit) + " more";
    }

    /** Unused today; kept beside the report it belongs to. */
    static String describe(TypeEdge edge) {
        return edge.fromFqn() + " " + edge.relation().displayName() + " " + edge.toFqn();
    }
}
