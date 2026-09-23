package com.boeing.depgraph.scanner;

import com.boeing.depgraph.maven.ModuleModel;
import com.boeing.depgraph.model.ArtifactKey;
import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.Gav;
import com.boeing.depgraph.model.ManagedDependency;
import com.boeing.depgraph.model.ModuleLink;
import com.boeing.depgraph.model.ModuleRef;
import com.boeing.depgraph.model.ModuleRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out how the poms in one scan point at each other.
 *
 * <p>Four kinds of edge, and only one of them is a dependency. Parent and aggregation are
 * how a project is structured; BOM imports are how its versions are decided; module
 * dependencies are how it actually hangs together. A graph carrying only
 * {@code DECLARES_DEP} can list what a project uses but cannot draw its shape.
 *
 * <p>Every edge records whether the far end is in the scan. Most parents are not: every
 * repository in this estate inherits {@code aero-parent}, which is resolved from the
 * artifact repository and has no pom on disk. That is a fact about the project, not a
 * failure to resolve, and the graph says so by carrying the GAV with a null path.
 */
final class ProjectTopology {

    private static final Logger log = LoggerFactory.getLogger(ProjectTopology.class);

    private ProjectTopology() {
    }

    static List<ModuleLink> link(List<ModuleModel> models) {
        Map<ArtifactKey, ModuleRef> byArtifact = new LinkedHashMap<>();
        Map<String, ModuleRef> byPomPath = new LinkedHashMap<>();

        for (ModuleModel model : models) {
            ModuleRef module = model.module();
            if (module == null) {
                continue;
            }
            byPomPath.put(module.pomPath(), module);
            // First wins. Two modules sharing groupId:artifactId is a broken project; the
            // conflict analyser will already be shouting about it, and picking the first
            // keeps this pass deterministic rather than adding a second complaint.
            byArtifact.putIfAbsent(module.key(), module);
        }

        List<ModuleLink> links = new ArrayList<>();
        for (ModuleModel model : models) {
            if (model.module() == null) {
                continue;
            }
            parent(model.module(), byArtifact, links);
            aggregates(model, byPomPath, links);
            bomImports(model, byArtifact, links);
            moduleDependencies(model, byArtifact, links);
        }

        log.debug("Linked {} modules with {} edges", byPomPath.size(), links.size());
        return List.copyOf(links);
    }

    // ------------------------------------------------------------------

    private static void parent(ModuleRef module,
                               Map<ArtifactKey, ModuleRef> byArtifact,
                               List<ModuleLink> links) {
        Gav parent = module.parent();
        if (parent == null) {
            return;
        }
        links.add(new ModuleLink(module, ModuleRelation.PARENT, parent,
            pathOf(byArtifact.get(parent.key()))));
    }

    /**
     * {@code <modules>} entries are directory names relative to the aggregator.
     *
     * <p>Usually. Maven also accepts a path to a pom file directly, and accepts nested
     * paths like {@code ../sibling}, so the entry is resolved and normalised rather than
     * concatenated.
     */
    private static void aggregates(ModuleModel model,
                                   Map<String, ModuleRef> byPomPath,
                                   List<ModuleLink> links) {
        ModuleRef aggregator = model.module();
        Path base = Paths.get(aggregator.pomPath()).getParent();
        if (base == null) {
            return;
        }

        for (String entry : model.aggregatedModules()) {
            Path candidate = base.resolve(entry).normalize();
            if (!entry.endsWith(".xml")) {
                candidate = candidate.resolve("pom.xml");
            }
            ModuleRef child = byPomPath.get(candidate.toString());
            if (child == null) {
                // A listed module with no pom where it says. Maven would fail the build;
                // this tool records the claim and moves on, because reporting the project
                // as unscannable would be a worse answer than reporting it as broken.
                log.debug("{} lists module '{}' but no pom was found at {}",
                    aggregator.relativePath(), entry, candidate);
                continue;
            }
            links.add(new ModuleLink(aggregator, ModuleRelation.AGGREGATES, child.gav(),
                child.pomPath()));
        }
    }

    private static void bomImports(ModuleModel model,
                                   Map<ArtifactKey, ModuleRef> byArtifact,
                                   List<ModuleLink> links) {
        for (ManagedDependency managed : model.managed()) {
            if (managed.bomImport()) {
                links.add(new ModuleLink(model.module(), ModuleRelation.IMPORTS_BOM,
                    managed.gav(), pathOf(byArtifact.get(managed.key()))));
            }
        }
    }

    /**
     * A declared dependency that happens to be another module in the scan.
     *
     * <p>The edge that turns a pile of poms into a topology. Note it is derived rather
     * than declared: nothing in a pom says "this dependency is one of us", and the answer
     * changes with the scan root. Scan one repository and its siblings are third-party
     * artifacts; scan the estate and they are modules.
     */
    private static void moduleDependencies(ModuleModel model,
                                           Map<ArtifactKey, ModuleRef> byArtifact,
                                           List<ModuleLink> links) {
        for (DeclaredDependency dependency : model.dependencies()) {
            ModuleRef target = byArtifact.get(dependency.key());
            if (target != null && !target.pomPath().equals(model.module().pomPath())) {
                links.add(new ModuleLink(model.module(), ModuleRelation.DEPENDS_ON_MODULE,
                    dependency.gav(), target.pomPath()));
            }
        }
    }

    private static String pathOf(ModuleRef module) {
        return module == null ? null : module.pomPath();
    }
}
