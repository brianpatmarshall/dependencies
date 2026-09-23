package com.boeing.depgraph.scanner;

import com.boeing.depgraph.code.CodeScanResult;
import com.boeing.depgraph.model.ArtifactUsage;
import com.boeing.depgraph.model.BomProposal;
import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.ManagedDependency;
import com.boeing.depgraph.model.ModuleLink;
import com.boeing.depgraph.model.ModuleRef;
import com.boeing.depgraph.model.VersionHold;

import java.util.List;

/**
 * Everything one scan discovered, ready to be written into the graph.
 *
 * <p>The same seam the estate's other two scanners use: core produces one of these and
 * knows nothing about what happens next, and the backend's graph populator consumes one
 * and knows nothing about how it was made. Keeping that line clean is what would let the
 * three tools share a {@code discovery-common} later without any of them being rewritten.
 */
public record ScanResult(
    String projectPath,
    String projectName,

    /** Every pom found, including aggregators and BOMs that declare no dependencies. */
    List<ModuleRef> modules,

    /** Effective dependencies, one row per module per dependency. */
    List<DeclaredDependency> dependencies,

    /**
     * Each pom's own {@code <dependencyManagement>}, including its BOM imports.
     *
     * <p>What a pom manages and what it depends on are different lists, and a BOM has
     * only the first. Collapsing them would make every BOM in the project look like the
     * hungriest module in it.
     */
    List<ManagedDependency> managed,

    /**
     * How the poms point at each other: parent, aggregation, BOM import, and dependencies
     * that resolve to another module in the same scan.
     *
     * <p>Dependencies are one edge of four here. The other three are how a project is
     * shaped and how its versions are decided, and a graph without them can say what a
     * project uses but not what it is.
     */
    List<ModuleLink> links,

    /** The same dependencies collapsed onto the version-free artifact, and classified. */
    List<ArtifactUsage> artifacts,

    BomProposal proposal,

    /** Deliberate holds read from {@code .depgraph-holds.yml}, whether or not they matched. */
    List<VersionHold> holds,

    /**
     * Poms that would not build a model, usually an unresolvable parent.
     *
     * <p>Reported rather than dropped: a project scanned with four modules missing is a
     * smaller project than the one on disk, and a conflict table built from it can be
     * confidently wrong.
     */
    List<ModuleProblem> problems,

    /**
     * The code plane, or null when only the build plane was run.
     *
     * <p>Nullable rather than empty because the two mean different things. An empty code
     * plane says "we read the bytecode and found nothing", which for any real project is
     * a finding; null says "we did not look", which is the normal state for a project
     * that has not been built.
     */
    CodeScanResult code,

    ScanStats stats
) {

    /** Whether the bytecode was read at all. */
    public boolean hasCodePlane() {
        return code != null;
    }

    public record ModuleProblem(String pomPath, String relativePath, List<String> messages) {}

    public record ScanStats(
        int pomsFound,
        int modulesRead,
        int modulesFailed,
        int dependencyCount,

        /** Entries in the modules' own dependencyManagement blocks. */
        int managedCount,

        /** {@code scope=import} rows - the BOMs this project adopts. */
        int bomImportCount,

        /** Declared dependencies that resolve to another module in the same scan. */
        int intraProjectDependencyCount,

        int artifactCount,

        /** Artifacts with more than one version in play. The headline number. */
        int conflictCount,

        int safeCount,
        int reviewCount,

        /** Major skew. The ones that will not be fixed by a generated BOM. */
        int blockedCount,

        int alreadyManagedCount,
        int heldCount,
        int proposedEntryCount,
        long durationMillis
    ) {}
}
