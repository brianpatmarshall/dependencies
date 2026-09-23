package com.boeing.depgraph.model;

import java.util.List;

/**
 * The {@code <dependencyManagement>} block that would put this project on one version of
 * everything, plus an honest account of what it could not do.
 *
 * <p>The excluded list is not a footnote. A proposal that quietly drops the four hard
 * artifacts and presents the forty easy ones as "done" is worse than no proposal, because
 * the four are the ones that will break a build.
 */
public record BomProposal(
    List<Entry> entries,
    List<Excluded> excluded
) {

    /**
     * One managed dependency.
     *
     * @param version    the version proposed — the highest in use, except where a hold says
     *                   otherwise
     * @param replaces   versions this entry would displace, for the diff a reviewer reads
     * @param rationale  a sentence the reviewer can agree or disagree with
     */
    public record Entry(
        ArtifactKey key,
        String version,
        List<String> replaces,
        ConflictSeverity severity,
        List<String> modules,
        String rationale
    ) {
        /** Entries a reviewer must look at before this BOM is safe to apply. */
        public boolean needsReview() {
            return severity == ConflictSeverity.REVIEW;
        }
    }

    /**
     * An artifact left out, and the reason — a major skew, or a BOM that already owns it.
     */
    public record Excluded(
        ArtifactKey key,
        List<String> versions,
        ConflictSeverity severity,
        String reason
    ) {}

    public long reviewCount() {
        return entries.stream().filter(Entry::needsReview).count();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
