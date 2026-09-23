package com.boeing.depgraph.model;

/**
 * How one pom relates to another.
 *
 * <p>A dependency is only one of the four ways poms are wired together, and it is not the
 * one that usually decides a version. Recording only {@code DECLARES_DEP} leaves the
 * question "where did this number come from" answerable in prose and not in the graph.
 */
public enum ModuleRelation {

    /**
     * {@code <parent>} — where inheritance, and therefore most managed versions, comes
     * from.
     *
     * <p>The target is frequently outside the scanned tree: every repository in this
     * estate inherits {@code aero-parent}, which is resolved from the artifact repository
     * and has no pom on disk. That is why the edge carries a GAV and only sometimes a
     * path.
     */
    PARENT("inherits from"),

    /**
     * {@code <modules>} — a reactor listing, which is not inheritance.
     *
     * <p>Kept distinct because the two are routinely confused and they answer different
     * questions. Aggregation says what gets built together; inheritance says what decides
     * settings. A pom can do either, both, or neither.
     */
    AGGREGATES("aggregates"),

    /**
     * A declared dependency that resolves to another module in the same scan.
     *
     * <p>The edge that turns a pile of poms into a topology: it is what makes "what breaks
     * if this module changes" a traversal, and what would reveal a cycle between modules
     * that Maven itself would refuse to build.
     */
    DEPENDS_ON_MODULE("depends on"),

    /**
     * {@code <dependencyManagement>} with {@code scope=import} — a BOM this pom adopts.
     *
     * <p>The single most important edge for explaining a version, and the one that makes
     * {@link ConflictSeverity#ALREADY_MANAGED} legible rather than mysterious: the graph
     * can show the path from a module to the BOM that decided its jackson version.
     */
    IMPORTS_BOM("imports");

    private final String displayName;

    ModuleRelation(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
