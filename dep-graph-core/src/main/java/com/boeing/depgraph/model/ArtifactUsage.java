package com.boeing.depgraph.model;

import java.util.List;

/**
 * Every version of one artifact that the scanned project uses, classified.
 *
 * <p>This is the row in the conflict table, and the reason the build plane is worth
 * having on its own before any bytecode is read: a multi-module project usually cannot
 * answer "what version of this are we on" without one of these, and the answer is
 * frequently more than one.
 *
 * @param versions   in descending version order, so {@code versions().get(0)} is the
 *                   newest in use and the default proposal
 * @param severity   how much attention unifying them needs
 * @param hold       the declared hold covering this artifact, or null
 * @param managedBy  the imported BOM that already pins it, or null. Set only when
 *                   {@code severity} is {@link ConflictSeverity#ALREADY_MANAGED}
 */
public record ArtifactUsage(
    ArtifactKey key,
    List<VersionUse> versions,
    ConflictSeverity severity,
    VersionHold hold,
    String managedBy
) {

    /** More than one version in play. */
    public boolean isConflict() {
        return versions.size() > 1;
    }

    /** The newest version any module uses. */
    public String highestVersion() {
        return versions.get(0).version();
    }

    /** Distinct modules touching this artifact at any version. */
    public long moduleCount() {
        return versions.stream().flatMap(v -> v.modules().stream()).distinct().count();
    }

    public boolean held() {
        return hold != null;
    }
}
