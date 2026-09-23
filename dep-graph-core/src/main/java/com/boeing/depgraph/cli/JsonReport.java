package com.boeing.depgraph.cli;

import com.boeing.depgraph.model.ArtifactUsage;
import com.boeing.depgraph.model.BomProposal;
import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.ModuleLink;
import com.boeing.depgraph.model.ModuleRef;
import com.boeing.depgraph.model.TypeEdge;
import com.boeing.depgraph.scanner.ScanResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;

/**
 * The machine-readable shape.
 *
 * <p>Deliberately not {@code ScanResult} serialised directly. Every
 * {@link DeclaredDependency} embeds the whole {@link ModuleRef} that owns it, which is
 * right for a Java object graph and wrong for a document: the estate's 146 dependencies
 * would carry 146 copies of nine module fields. Flattening to the module's relative path
 * costs one join for the reader and removes most of the file.
 *
 * <p>This is also the shape the REST API will return, so that the CLI and the UI are
 * looking at the same thing rather than at two renderings that drift.
 */
record JsonReport(
    String project,
    String path,
    Summary summary,
    List<Module> modules,
    List<Link> links,
    List<Dependency> dependencies,
    List<ArtifactUsage> artifacts,
    BomProposal proposal,
    List<Problem> problems,
    Code code
) {

    record Summary(
        int modules, int dependencies, int managed, int bomImports, int intraProjectDependencies,
        int artifacts, int conflicts, int safe, int review, int blocked, int alreadyManaged,
        int held, int proposedEntries, int unreadablePoms, long durationMillis) {}

    record Module(String artifactId, String groupId, String version, String packaging,
                  String path, boolean aggregator) {}

    record Link(String from, String relation, String target, boolean inProject) {}

    record Dependency(String module, String groupId, String artifactId, String version,
                      String scope, String origin, String decidedBy, String rawVersion) {}

    record Problem(String path, List<String> messages) {}

    record Code(int types, int thirdPartyTypes, int methods, int fields,
                int edges, int occurrences, int inlinedConstants,
                List<String> modulesNotBuilt, List<Edge> topEdges, long durationMillis) {}

    record Edge(String from, String to, String relation, int count) {}

    // ------------------------------------------------------------------

    /**
     * @param edgeLimit how many code-plane edges to include. The estate produces 192,441
     *                  of them; a default report that ships all of them is a file nobody
     *                  opens twice. The counts above it stay exact either way
     */
    static String write(ScanResult result, int edgeLimit) throws Exception {
        ScanResult.ScanStats s = result.stats();

        JsonReport report = new JsonReport(
            result.projectName(),
            result.projectPath(),
            new Summary(s.modulesRead(), s.dependencyCount(), s.managedCount(),
                s.bomImportCount(), s.intraProjectDependencyCount(), s.artifactCount(),
                s.conflictCount(), s.safeCount(), s.reviewCount(), s.blockedCount(),
                s.alreadyManagedCount(), s.heldCount(), s.proposedEntryCount(),
                s.modulesFailed(), s.durationMillis()),
            result.modules().stream().map(JsonReport::module).toList(),
            result.links().stream().map(JsonReport::link).toList(),
            result.dependencies().stream().map(JsonReport::dependency).toList(),
            result.artifacts(),
            result.proposal(),
            result.problems().stream()
                .map(p -> new Problem(p.relativePath(), p.messages())).toList(),
            code(result, edgeLimit));

        return new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .writeValueAsString(report);
    }

    private static Module module(ModuleRef m) {
        return new Module(m.artifactId(), m.groupId(), m.version(), m.packaging(),
            m.relativePath(), m.aggregator());
    }

    private static Link link(ModuleLink l) {
        return new Link(l.from().relativePath(), l.relation().name(), l.target().gav(),
            l.inProject());
    }

    private static Dependency dependency(DeclaredDependency d) {
        return new Dependency(d.module().relativePath(), d.gav().groupId(),
            d.gav().artifactId(), d.gav().version(), d.scope(), d.origin().name(),
            d.originModelId(), d.rawVersion());
    }

    private static Code code(ScanResult result, int edgeLimit) {
        if (!result.hasCodePlane()) {
            return null;
        }
        var stats = result.code().stats();
        List<Edge> edges = result.code().edges().stream()
            .sorted((a, b) -> Integer.compare(b.weight(), a.weight()))
            .limit(edgeLimit)
            .map(e -> new Edge(e.fromFqn(), e.toFqn(), e.relation().name(), e.count()))
            .toList();

        return new Code(stats.typesFound(), stats.thirdPartyTypes(), stats.methodsFound(),
            stats.fieldsFound(), stats.edgeCount(), stats.edgeOccurrences(),
            stats.inlinedConstants(), result.code().modulesWithoutClasses(), edges,
            stats.durationMillis());
    }

    @SuppressWarnings("unused")
    private static String describe(TypeEdge edge) {
        return edge.groupKey();
    }
}
