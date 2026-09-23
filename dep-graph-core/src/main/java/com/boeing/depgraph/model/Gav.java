package com.boeing.depgraph.model;

/**
 * A versioned artifact — the {@code :ArtifactVersion} node.
 *
 * <p>Split from {@link ArtifactKey} rather than carrying the version as a property on
 * one node, because the two answer different questions and both are asked constantly.
 * {@code (:Module)-[:DECLARES_DEP]->(:ArtifactVersion)-[:VERSION_OF]->(:Artifact)} makes
 * "every version of jackson-databind in this estate, and who wants it" a single hop out
 * and back, which is precisely the query a BOM extraction is built on.
 */
public record Gav(String groupId, String artifactId, String version) implements Comparable<Gav> {

    public ArtifactKey key() {
        return new ArtifactKey(groupId, artifactId);
    }

    /** {@code groupId:artifactId:version} — the MERGE key for an {@code :ArtifactVersion}. */
    public String gav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    @Override
    public int compareTo(Gav other) {
        int byKey = key().compareTo(other.key());
        return byKey != 0 ? byKey : version.compareTo(other.version);
    }

    @Override
    public String toString() {
        return gav();
    }
}
