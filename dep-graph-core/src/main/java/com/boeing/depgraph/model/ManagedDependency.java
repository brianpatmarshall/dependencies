package com.boeing.depgraph.model;

/**
 * An entry in a pom's own {@code <dependencyManagement>}.
 *
 * <p>Deliberately the module's <em>own</em> block, not the effective one. The effective
 * dependencyManagement of anything importing {@code spring-boot-dependencies} is about
 * 2,500 entries, none of which anybody wrote; recording those would bury the handful this
 * pom actually decided under Spring Boot's entire catalogue.
 *
 * <p>Managing an artifact is not depending on it. A BOM declares hundreds of these and
 * needs none of them at build time, which is exactly why the distinction earns its own
 * record rather than a flag on {@link DeclaredDependency}.
 *
 * @param bomImport true for {@code <type>pom</type><scope>import</scope>} — this row is a
 *                  whole BOM being adopted rather than one version being set
 */
public record ManagedDependency(
    ModuleRef module,
    Gav gav,
    String type,
    String classifier,
    String scope,
    String rawVersion,
    boolean bomImport
) {

    public ArtifactKey key() {
        return gav.key();
    }
}
