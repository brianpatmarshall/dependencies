package com.boeing.depgraph.model;

/**
 * An artifact without a version — {@code com.fasterxml.jackson.core:jackson-databind}.
 *
 * <p>This is the thing a BOM has an opinion about. Keeping it separate from the versions
 * in use is the whole trick behind the build plane: "which versions of jackson-databind
 * are in play" is one hop from here, and a graph keyed on GAV instead would make that a
 * string-prefix search.
 */
public record ArtifactKey(String groupId, String artifactId) implements Comparable<ArtifactKey> {

    public static ArtifactKey of(String groupId, String artifactId) {
        return new ArtifactKey(groupId, artifactId);
    }

    /** {@code groupId:artifactId} — the MERGE key for an {@code :Artifact} node. */
    public String ga() {
        return groupId + ":" + artifactId;
    }

    @Override
    public int compareTo(ArtifactKey other) {
        int byGroup = groupId.compareTo(other.groupId);
        return byGroup != 0 ? byGroup : artifactId.compareTo(other.artifactId);
    }

    @Override
    public String toString() {
        return ga();
    }
}
