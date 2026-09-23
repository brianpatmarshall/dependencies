package com.boeing.depgraph.backend.scan;

import com.boeing.depgraph.scanner.ScanResult;

import java.time.Instant;
import java.util.List;

/** What a scan did, small enough to hand straight back over HTTP. */
public record ScanSummary(
    String projectPath,
    String projectName,
    Instant scannedAt,
    int modulesRead,
    int modulesFailed,
    int dependencyCount,
    int artifactCount,
    int conflictCount,
    int blockedCount,
    int reviewCount,
    int alreadyManagedCount,
    int heldCount,
    int proposedEntryCount,
    boolean hasCodePlane,
    int typeCount,
    int thirdPartyTypes,
    int edgeCount,
    int inlinedConstants,
    List<String> unbuiltModules,
    List<String> unreadablePoms,
    long scanMillis,
    long totalMillis
) {

    public static ScanSummary of(ScanResult result, long totalMillis) {
        ScanResult.ScanStats stats = result.stats();
        boolean code = result.hasCodePlane();
        return new ScanSummary(
            result.projectPath(), result.projectName(), Instant.now(),
            stats.modulesRead(), stats.modulesFailed(), stats.dependencyCount(),
            stats.artifactCount(), stats.conflictCount(), stats.blockedCount(),
            stats.reviewCount(), stats.alreadyManagedCount(), stats.heldCount(),
            stats.proposedEntryCount(), code,
            code ? result.code().stats().typesFound() : 0,
            code ? result.code().stats().thirdPartyTypes() : 0,
            code ? result.code().stats().edgeCount() : 0,
            code ? result.code().stats().inlinedConstants() : 0,
            code ? result.code().modulesWithoutClasses() : List.of(),
            result.problems().stream().map(ScanResult.ModuleProblem::relativePath).toList(),
            stats.durationMillis(), totalMillis);
    }
}
