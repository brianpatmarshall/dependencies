package com.boeing.depgraph.model;

/**
 * How dangerous it is to unify the versions of one artifact on a single number.
 *
 * <p>The severities are ordered by how much human attention they need, and the whole
 * point of the classification is that most rows need none. An estate with two hundred
 * artifacts typically has a handful of real arguments in it; everything else is patch
 * noise that nobody chose and nobody will miss.
 */
public enum ConflictSeverity {

    /**
     * One version in use. Nothing to reconcile — but still worth hoisting into a BOM so
     * it stays that way.
     */
    UNIFORM("Uniform", 0),

    /**
     * Versions differ only below the minor: {@code 2.15.2} against {@code 2.15.4}.
     *
     * <p>Auto-bumped to the highest in use. Semantic versioning promises this is
     * additive, and unlike the cases below there is no version a reviewer would pick
     * other than the newest.
     */
    SAFE("Safe", 1),

    /**
     * Minor skew: {@code 2.15.4} against {@code 2.17.0}.
     *
     * <p>Proposed at the highest in use, but flagged. New API is fine; the risk is a
     * behaviour change under a module that was pinned for a reason nobody wrote down.
     */
    REVIEW("Review", 2),

    /**
     * Major skew: {@code 3.3.1} against {@code 4.0.11}.
     *
     * <p>Left out of the proposal entirely. A major bump is an API migration and belongs
     * in a scheduled piece of work, not in a generated BOM entry — see the pulsar-client
     * hold in this estate's own {@code aero-bom} for what that looks like when it is
     * done deliberately.
     */
    BLOCKED("Blocked", 3),

    /**
     * An imported BOM already pins this artifact somewhere in the project.
     *
     * <p>Excluded rather than resolved. The versions may well differ between modules,
     * but the fix is to align the BOM imports, not to add a hand-pinned override that
     * silently beats them.
     */
    ALREADY_MANAGED("Already managed", 4);

    private final String displayName;
    private final int weight;

    ConflictSeverity(String displayName, int weight) {
        this.displayName = displayName;
        this.weight = weight;
    }

    public String displayName() {
        return displayName;
    }

    /** Sort order for tables and dashboards. Higher wants more attention. */
    public int weight() {
        return weight;
    }

    /** Whether this artifact earns a {@code <dependency>} in the proposed BOM. */
    public boolean proposable() {
        return this == UNIFORM || this == SAFE || this == REVIEW;
    }
}
