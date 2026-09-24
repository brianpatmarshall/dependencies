package com.boeing.depgraph.maven;

import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.Gav;
import com.boeing.depgraph.model.ResolvedArtifact;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out each module's real classpath, transitives included.
 *
 * <h2>Collect, do not resolve</h2>
 * {@code collectDependencies} builds the graph and applies version mediation without
 * fetching anything; {@code resolveDependencies} would download. Collecting and then
 * computing local paths ourselves means a scan can run fully offline against a read-only
 * {@code /m2} mount and still get the right answer, and a jar that is genuinely absent is
 * reported rather than fatal.
 *
 * <h2>Why its own session</h2>
 * {@link EffectiveModelReader} builds a bare session, which is all a model build needs.
 * Collection needs much more: without a conflict resolver the graph comes back unmediated,
 * with every version of every artifact and cycles left in. {@code MavenRepositorySystemUtils}
 * installs what Maven itself installs - nearest-wins version selection, scope selection,
 * dependency management and the optional/exclusion selectors - so the closure here is the
 * classpath Maven would build.
 */
public final class DependencyClosure {

    private static final Logger log = LoggerFactory.getLogger(DependencyClosure.class);

    private static final String CENTRAL_URL = "https://repo.maven.apache.org/maven2/";

    /** Collection can loop on a broken graph; real trees are nowhere near this deep. */
    private static final int MAX_DEPTH = 32;

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;
    private final Path localRepository;

    private DependencyClosure(RepositorySystem system, RepositorySystemSession session,
                              List<RemoteRepository> repositories, Path localRepository) {
        this.system = system;
        this.session = session;
        this.repositories = repositories;
        this.localRepository = localRepository;
    }

    public static DependencyClosure forLocalRepository(Path localRepository, boolean offline) {
        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(
            session, new LocalRepository(localRepository.toFile())));
        session.setOffline(offline);
        // Collection asks for a pom it has not got; without this it logs a warning per
        // miss and the output drowns out the scan's own progress.
        session.setSystemProperties(System.getProperties());

        // Central is declared even when offline, and that is not a contradiction.
        // session.setOffline() is what prevents the network; this list is what the local
        // repository manager matches a cached artifact against. Aether's enhanced manager
        // records which remote each artifact came from, so with an empty list every
        // already-downloaded pom looks unavailable and collection fails. On this estate
        // that silently reduced 9 of 16 modules to their direct dependencies only.
        List<RemoteRepository> repositories =
            List.of(new RemoteRepository.Builder("central", "default", CENTRAL_URL).build());

        log.info("Dependency closure ready: localRepository={} offline={}",
            localRepository, offline);
        return new DependencyClosure(system, session, repositories, localRepository);
    }

    // ------------------------------------------------------------------

    /**
     * @param declared every module's direct effective dependencies
     * @return one row per module per jar on its classpath, deduplicated within a module
     */
    public List<ResolvedArtifact> resolve(List<DeclaredDependency> declared) {
        Map<String, List<DeclaredDependency>> byModule = new LinkedHashMap<>();
        for (DeclaredDependency dependency : declared) {
            byModule.computeIfAbsent(dependency.module().pomPath(), key -> new ArrayList<>())
                .add(dependency);
        }

        List<ResolvedArtifact> resolved = new ArrayList<>();
        int failures = 0;
        for (Map.Entry<String, List<DeclaredDependency>> entry : byModule.entrySet()) {
            try {
                resolved.addAll(forModule(entry.getKey(), entry.getValue()));
            } catch (DependencyCollectionException e) {
                // One module's broken graph is not the scan's problem. Its direct jars are
                // still recorded below, so the module degrades to the old behaviour rather
                // than vanishing from the code plane.
                failures++;
                log.debug("Could not collect dependencies for {}: {}",
                    entry.getKey(), e.getMessage());
                resolved.addAll(directOnly(entry.getKey(), entry.getValue()));
            }
        }

        long transitive = resolved.stream().filter(artifact -> !artifact.direct()).count();
        log.info("Classpath closure: {} rows across {} modules ({} transitive){}",
            resolved.size(), byModule.size(), transitive,
            failures == 0 ? "" : ", " + failures + " module(s) fell back to direct only");
        return List.copyOf(resolved);
    }

    // ------------------------------------------------------------------

    private List<ResolvedArtifact> forModule(String pomPath, List<DeclaredDependency> declared)
            throws DependencyCollectionException {

        CollectRequest request = new CollectRequest();
        request.setRepositories(repositories);
        for (DeclaredDependency dependency : declared) {
            Artifact artifact = toArtifact(dependency);
            if (artifact != null) {
                request.addDependency(new Dependency(artifact,
                    dependency.scope() == null ? "compile" : dependency.scope(),
                    dependency.optional()));
            }
        }
        if (request.getDependencies().isEmpty()) {
            return List.of();
        }

        CollectResult result = system.collectDependencies(session, request);
        return walk(pomPath, result.getRoot());
    }

    /**
     * Depth-first over the collected graph, remembering which direct dependency each node
     * hangs off.
     *
     * <p>Iterative rather than recursive, and visited-tracked: the graph is a DAG after
     * mediation but a diamond is normal, and revisiting a shared subtree once per path into
     * it turns a large estate's closure into minutes of pointless work.
     */
    private List<ResolvedArtifact> walk(String pomPath, DependencyNode root) {
        List<ResolvedArtifact> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<Frame> stack = new ArrayDeque<>();

        // The root is synthetic and carries no artifact; its children are the declared ones.
        for (DependencyNode child : root.getChildren()) {
            stack.push(new Frame(child, 0, ""));
        }

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            DependencyNode node = frame.node();
            Artifact artifact = node.getArtifact();
            if (artifact == null || frame.depth() > MAX_DEPTH) {
                continue;
            }

            String ga = artifact.getGroupId() + ":" + artifact.getArtifactId();
            if (!seen.add(ga + "@" + artifact.getVersion())) {
                continue;
            }

            String via = frame.depth() == 0 ? "" : frame.viaGa();
            Path jar = jarPath(artifact);
            if (jar != null && Files.isRegularFile(jar)) {
                rows.add(new ResolvedArtifact(
                    pomPath,
                    new Gav(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()),
                    jar.toAbsolutePath().toString(),
                    frame.depth(),
                    node.getDependency() == null ? "compile" : node.getDependency().getScope(),
                    via));
            }

            String viaForChildren = frame.depth() == 0 ? ga : frame.viaGa();
            for (DependencyNode child : node.getChildren()) {
                stack.push(new Frame(child, frame.depth() + 1, viaForChildren));
            }
        }
        return rows;
    }

    private record Frame(DependencyNode node, int depth, String viaGa) {}

    /** What the scanner did before this class existed, kept as the fallback path. */
    private List<ResolvedArtifact> directOnly(String pomPath, List<DeclaredDependency> declared) {
        List<ResolvedArtifact> rows = new ArrayList<>();
        for (DeclaredDependency dependency : declared) {
            Artifact artifact = toArtifact(dependency);
            if (artifact == null) {
                continue;
            }
            Path jar = jarPath(artifact);
            if (jar != null && Files.isRegularFile(jar)) {
                rows.add(new ResolvedArtifact(pomPath, dependency.gav(),
                    jar.toAbsolutePath().toString(), 0,
                    dependency.scope() == null ? "compile" : dependency.scope(), ""));
            }
        }
        return rows;
    }

    /**
     * Null for anything that is not a readable jar.
     *
     * <p>SNAPSHOTs of this project's own modules are excluded deliberately: their classes
     * are read from {@code target/classes} instead, and a stale installed snapshot would
     * describe code nobody is looking at.
     */
    private static Artifact toArtifact(DeclaredDependency dependency) {
        Gav gav = dependency.gav();
        if (gav.version() == null || gav.version().isBlank() || gav.version().endsWith("-SNAPSHOT")) {
            return null;
        }
        String type = dependency.type() == null || dependency.type().isBlank()
            ? "jar" : dependency.type();
        if (!"jar".equals(type)) {
            // pom, test-jar and friends. A pom dependency has no bytecode to read, and a
            // BOM import would drag its entire managed list into the closure.
            return null;
        }
        String classifier = dependency.classifier() == null ? "" : dependency.classifier();
        return new DefaultArtifact(gav.groupId(), gav.artifactId(), classifier, "jar", gav.version());
    }

    /** The layout is fixed, so the path is computable without asking the repository. */
    private Path jarPath(Artifact artifact) {
        String extension = artifact.getExtension();
        if (extension != null && !extension.isBlank() && !"jar".equals(extension)) {
            return null;
        }
        String classifier = artifact.getClassifier();
        String name = artifact.getArtifactId() + "-" + artifact.getVersion()
            + (classifier == null || classifier.isBlank() ? "" : "-" + classifier) + ".jar";
        return localRepository
            .resolve(artifact.getGroupId().replace('.', '/'))
            .resolve(artifact.getArtifactId())
            .resolve(artifact.getVersion())
            .resolve(name);
    }
}
