package com.boeing.depgraph.model;

/**
 * One dependency of one module, as the effective model resolved it.
 *
 * <p>"Effective" matters: the version here is the one the build will actually use, after
 * inheritance, property interpolation and every imported BOM. {@link #rawVersion()} keeps
 * what the pom literally says, which is usually either nothing at all or a
 * {@code ${property}}, because a BOM proposal has to edit the text and a version number
 * that appears nowhere in the file cannot be edited.
 *
 * @param module        the module that depends
 * @param gav           the dependency, at its effective version
 * @param rawVersion    what the module's own pom says in the version element — null when it
 *                      says nothing, {@code ${jackson.version}} when it defers to a property
 * @param origin        who decided the effective version
 * @param originModelId the model that decided it, as Maven names it. For an imported BOM
 *                      this is the BOM's own GAV, which is how {@code ALREADY_MANAGED} is
 *                      attributed to {@code org.springframework.boot:spring-boot-dependencies}
 *                      rather than to a shrug
 */
public record DeclaredDependency(
    ModuleRef module,
    Gav gav,
    String type,
    String classifier,
    String scope,
    boolean optional,
    String rawVersion,
    VersionOrigin origin,
    String originModelId
) {

    public ArtifactKey key() {
        return gav.key();
    }

    /** True when the pom defers to a {@code ${property}} rather than naming a version. */
    public boolean viaProperty() {
        return rawVersion != null && rawVersion.contains("${");
    }

    /**
     * Test-scoped dependencies still count.
     *
     * <p>They are the ones most likely to have drifted — nobody reviews a test dependency
     * bump — and a BOM that manages junit for four modules and leaves the fifth behind is
     * the exact failure this tool exists to find.
     */
    public boolean isTestOnly() {
        return "test".equals(scope);
    }
}
