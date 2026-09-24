package com.boeing.depgraph.backend.graph;

import com.boeing.depgraph.model.Gav;
import com.boeing.depgraph.model.ModuleRef;
import com.boeing.depgraph.model.ResolvedArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a type's origin path back into the thing that supplied it.
 *
 * <p>This is the {@code FROM} bridge, and it is the only reason the two planes are worth
 * putting in one graph. The scanner records where every type was read from - a module's
 * {@code target/classes} or a jar in the local repository - and that string is all the
 * evidence there is. Resolving it here, once, is what lets a query ask "which module owns
 * this class" and "which artifact version supplied this class" in the same breath.
 *
 * <p>Module matching is longest-prefix rather than first-match. Modules nest: with
 * {@code dep-graph-core} inside {@code dependencies}, a shortest-match rule would
 * attribute every class in the child to the parent, and the parent is an aggregator that
 * has no classes of its own.
 */
public final class OriginIndex {

    private static final Logger log = LoggerFactory.getLogger(OriginIndex.class);

    /** Module directory to pom path, longest first. */
    private final List<Map.Entry<String, String>> moduleDirs;
    private final Map<String, Gav> jars;

    private OriginIndex(List<Map.Entry<String, String>> moduleDirs, Map<String, Gav> jars) {
        this.moduleDirs = moduleDirs;
        this.jars = jars;
    }

    public static OriginIndex build(List<ModuleRef> modules, List<ResolvedArtifact> classpath) {
        Map<String, String> dirs = new LinkedHashMap<>();
        for (ModuleRef module : modules) {
            Path pom = Path.of(module.pomPath()).toAbsolutePath();
            Path dir = pom.getParent();
            if (dir != null) {
                dirs.put(dir.toString(), module.pomPath());
            }
        }
        List<Map.Entry<String, String>> ordered = dirs.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
            .toList();

        // From the scan's own resolved classpath, so transitively supplied jars are
        // attributable too. Built from declared dependencies alone, every type arriving
        // through a transitive artifact was unattributable and its edges were discarded.
        Map<String, Gav> jars = new LinkedHashMap<>();
        for (ResolvedArtifact artifact : classpath) {
            jars.putIfAbsent(artifact.jarPath(), artifact.gav());
        }
        log.info("Origin index: {} module directories, {} classpath jars",
            ordered.size(), jars.size());
        return new OriginIndex(ordered, jars);
    }

    /** The pom path of the module a type came from, or null if it did not come from one. */
    public String moduleFor(String originPath) {
        if (originPath == null) {
            return null;
        }
        String path = Path.of(originPath).toAbsolutePath().toString();
        for (Map.Entry<String, String> entry : moduleDirs) {
            if (path.equals(entry.getKey()) || path.startsWith(entry.getKey() + java.io.File.separator)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** The artifact version whose jar a type was read from, or null. */
    public Gav artifactFor(String originPath) {
        if (originPath == null) {
            return null;
        }
        return jars.get(Path.of(originPath).toAbsolutePath().toString());
    }
}
