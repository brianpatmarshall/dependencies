package com.boeing.depgraph.model;

/**
 * One jar on one module's classpath, direct or transitive.
 *
 * <p>The build plane reads what a pom <em>declares</em>. This is what Maven would actually
 * put on the classpath after resolution, which is a longer list and the one the code plane
 * needs: a class can only become a node if the jar holding it was read, and a great many
 * classes that real code compiles against arrive through an artifact nobody names.
 *
 * <p>The canonical example is {@code org.apache.pulsar:pulsar-client}. Its API types live
 * in the companion artifact {@code pulsar-client-api}, which nothing declares directly, so
 * reading only declared jars left {@code org.apache.pulsar.client.api.Producer} out of the
 * graph - and every edge pointing at it was discarded, which made a heavily used dependency
 * look unused.
 *
 * @param depth 0 when the module declares this itself, 1 or more when something else
 *              brought it in
 * @param viaGa the module's own direct dependency that pulled this in, empty at depth 0.
 *              The answer to "why is this on my classpath at all"
 */
public record ResolvedArtifact(
    String modulePomPath,
    Gav gav,
    String jarPath,
    int depth,
    String scope,
    String viaGa
) {

    public boolean direct() {
        return depth == 0;
    }

    public ArtifactKey key() {
        return gav.key();
    }
}
