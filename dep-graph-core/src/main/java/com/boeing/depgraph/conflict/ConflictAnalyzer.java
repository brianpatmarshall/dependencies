package com.boeing.depgraph.conflict;

import com.boeing.depgraph.model.ArtifactKey;
import com.boeing.depgraph.model.ArtifactUsage;
import com.boeing.depgraph.model.ConflictSeverity;
import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.VersionHold;
import com.boeing.depgraph.model.VersionOrigin;
import com.boeing.depgraph.model.VersionUse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Groups every dependency in a project by artifact and says how bad the disagreement is.
 *
 * <p>This is the whole build plane in one class: take the effective dependencies of every
 * module, collapse them onto the version-free artifact, and classify what comes out.
 */
public final class ConflictAnalyzer {

    private ConflictAnalyzer() {
    }

    /**
     * @param holds deliberate holds from {@code .depgraph-holds.yml}, attached to the
     *              artifacts they cover so the report can say "this is on purpose"
     */
    public static List<ArtifactUsage> analyze(List<DeclaredDependency> dependencies,
                                              List<VersionHold> holds) {
        Map<ArtifactKey, VersionHold> holdsByKey = new LinkedHashMap<>();
        for (VersionHold hold : holds) {
            holdsByKey.put(hold.key(), hold);
        }

        // Sorted by groupId:artifactId so a scan of the same project twice produces the
        // same report. A conflict table that reshuffles between runs cannot be diffed.
        Map<ArtifactKey, List<DeclaredDependency>> byArtifact = new TreeMap<>();
        for (DeclaredDependency dependency : dependencies) {
            if (dependency.gav().version() == null) {
                // A dependency the model builder could not put a version on. Reporting it
                // as a version of its own would invent a conflict out of missing data.
                continue;
            }
            byArtifact.computeIfAbsent(dependency.key(), k -> new ArrayList<>()).add(dependency);
        }

        List<ArtifactUsage> usages = new ArrayList<>(byArtifact.size());
        byArtifact.forEach((key, uses) -> usages.add(toUsage(key, uses, holdsByKey.get(key))));
        return usages;
    }

    private static ArtifactUsage toUsage(ArtifactKey key,
                                         List<DeclaredDependency> uses,
                                         VersionHold hold) {
        Map<String, List<DeclaredDependency>> byVersion = new LinkedHashMap<>();
        for (DeclaredDependency use : uses) {
            byVersion.computeIfAbsent(use.gav().version(), v -> new ArrayList<>()).add(use);
        }

        List<VersionUse> versions = new ArrayList<>(byVersion.size());
        byVersion.forEach((version, group) -> {
            Set<String> modules = new LinkedHashSet<>();
            Set<VersionOrigin> origins = EnumSet.noneOf(VersionOrigin.class);
            for (DeclaredDependency use : group) {
                modules.add(use.module().relativePath());
                origins.add(use.origin());
            }
            versions.add(new VersionUse(version,
                modules.stream().sorted().toList(),
                Set.copyOf(origins)));
        });
        versions.sort(Comparator.comparing(VersionUse::version, VersionOrder.DESCENDING));

        String managedBy = importedBomManaging(uses);
        ConflictSeverity severity = managedBy != null
            ? ConflictSeverity.ALREADY_MANAGED
            : VersionOrder.classify(byVersion.keySet());

        return new ArtifactUsage(key, List.copyOf(versions), severity, hold, managedBy);
    }

    /**
     * The imported BOM that already owns this artifact, if any.
     *
     * <p>One module reaching it through {@code spring-boot-dependencies} is enough to make
     * the whole artifact somebody else's business. Adding a hand-pinned version that beats
     * an imported BOM does not unify anything — it creates a second opinion that wins
     * silently, and the next Boot upgrade moves one of them and not the other.
     */
    private static String importedBomManaging(List<DeclaredDependency> uses) {
        for (DeclaredDependency use : uses) {
            if (use.origin() == VersionOrigin.IMPORTED_BOM && !use.originModelId().isBlank()) {
                return use.originModelId();
            }
        }
        return null;
    }
}
