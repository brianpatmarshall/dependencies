package com.boeing.depgraph.model;

/**
 * Where the number actually came from.
 *
 * <p>A dependency's version is not a property of the dependency; it is the outcome of an
 * argument between the module, its parents and every BOM they import. Recording who won
 * is what separates "this module chose 2.15.2" from "this module said nothing and Spring
 * Boot chose 2.15.2 for it", and those two want opposite treatment in a BOM proposal.
 */
public enum VersionOrigin {

    /** Written in this module's own {@code <dependencies>}. The module chose it. */
    LOCAL_DECLARATION("Declared here"),

    /** This module's own {@code <dependencyManagement>} decided it. */
    LOCAL_MANAGEMENT("Managed here"),

    /** A parent pom in this module's lineage decided it. */
    INHERITED_MANAGEMENT("Managed by a parent"),

    /**
     * An imported BOM decided it — {@code spring-boot-dependencies} and its like.
     *
     * <p>Artifacts reaching a module this way are deliberately left out of any BOM this
     * tool proposes. Pinning a version that Spring Boot already manages is not a fix; it
     * is the mechanism by which an estate drifts apart one upgrade at a time.
     */
    IMPORTED_BOM("Managed by an imported BOM"),

    /** The model builder produced a version whose origin it could not attribute. */
    UNKNOWN("Origin unknown");

    private final String displayName;

    VersionOrigin(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** True when the module said nothing and something upstream answered. */
    public boolean isManaged() {
        return this != LOCAL_DECLARATION;
    }
}
