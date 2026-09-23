package com.boeing.depgraph.model;

/**
 * An edge between one pom and something it points at.
 *
 * @param target       always populated — the coordinates the pom names
 * @param targetPomPath the pom on disk this resolves to, or null when the target lives
 *                     outside the scanned tree. {@code aero-parent} is the normal case for
 *                     null: it is resolved from the artifact repository and there is no
 *                     file to point at, which is information rather than a failure
 */
public record ModuleLink(
    ModuleRef from,
    ModuleRelation relation,
    Gav target,
    String targetPomPath
) {

    /** Whether the other end is a module this scan also read. */
    public boolean inProject() {
        return targetPomPath != null;
    }
}
