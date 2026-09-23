package com.boeing.depgraph.model;

/**
 * One pom in the scanned project.
 *
 * <p>Every module, aggregator and BOM in the tree gets one of these, including the ones
 * that build nothing: a {@code packaging=pom} aggregator declares no dependencies but is
 * very often where the versions are decided, and leaving it out would hide the answer to
 * "where did this number come from".
 *
 * @param pomPath    absolute path to the pom, and the MERGE key for the {@code :Module} node
 * @param relativePath path from the scan root, which is what a human recognises
 * @param packaging  {@code jar}, {@code pom}, {@code war} …
 * @param parent     the parent's GAV, or null for a module with no parent
 * @param aggregator whether this pom lists {@code <modules>}
 */
public record ModuleRef(
    String groupId,
    String artifactId,
    String version,
    String packaging,
    String name,
    String pomPath,
    String relativePath,
    Gav parent,
    boolean aggregator
) {

    public ArtifactKey key() {
        return new ArtifactKey(groupId, artifactId);
    }

    public Gav gav() {
        return new Gav(groupId, artifactId, version);
    }

    /**
     * How to name this module to a human.
     *
     * <p>{@link #relativePath()} is the right answer almost always - it is unique and it
     * is where the file is. The exception is the module at the scan root, whose relative
     * path is the bare string {@code pom.xml}, which tells a reader nothing. Pointing the
     * tool at a single-module repository is the common case, so the degenerate one is
     * worth handling.
     */
    public String label() {
        return "pom.xml".equals(relativePath) ? artifactId : relativePath;
    }

    /** A pom whose only job is to decide versions for other poms. */
    public boolean isBom() {
        return "pom".equals(packaging) && !aggregator;
    }
}
