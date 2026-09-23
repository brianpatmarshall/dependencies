package com.boeing.depgraph.conflict;

import com.boeing.depgraph.model.ArtifactUsage;
import com.boeing.depgraph.model.BomProposal;
import com.boeing.depgraph.model.ConflictSeverity;
import com.boeing.depgraph.model.VersionHold;
import com.boeing.depgraph.model.VersionOrigin;
import com.boeing.depgraph.model.VersionUse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the conflict table into the {@code <dependencyManagement>} block that would fix it.
 *
 * <h2>What earns an entry</h2>
 * An artifact is worth managing when there is something to take over: either the modules
 * disagree about its version, or at least one of them hand-pins it. An artifact that
 * every module already inherits from a parent needs no entry — it is already managed in
 * one place, which is the state this whole exercise is trying to reach.
 *
 * <h2>What does not</h2>
 * Major skew stays out and says why. The temptation is to emit it anyway at the highest
 * version and let the build sort it out; that turns a report someone can apply into a
 * report someone has to audit, which is the difference between a tool that gets used and
 * one that gets run once.
 *
 * <p>An artifact an imported BOM already owns stays out too, but only says why when there
 * is something to say. Run against this estate, the first version of this class produced
 * sixty exclusions, fifty-six of which meant "Spring Boot manages this and every module
 * agrees" — which is not a problem, it is the outcome the exercise is aiming at. The four
 * that mattered were invisible among them.
 */
public final class BomProposer {

    private BomProposer() {
    }

    public static BomProposal propose(List<ArtifactUsage> usages) {
        List<BomProposal.Entry> entries = new ArrayList<>();
        List<BomProposal.Excluded> excluded = new ArrayList<>();

        for (ArtifactUsage usage : usages) {
            if (usage.held()) {
                entries.add(heldEntry(usage));
                continue;
            }
            if (usage.severity() == ConflictSeverity.ALREADY_MANAGED) {
                // An artifact every module inherits cleanly from an imported BOM is not a
                // finding, it is the goal. Listing it as "excluded" was the first thing
                // this tool got wrong against a real estate: fifty-six rows saying nothing
                // buried the four that mattered.
                if (worthManaging(usage)) {
                    excluded.add(new BomProposal.Excluded(usage.key(), versionsOf(usage),
                        usage.severity(), alreadyManagedReason(usage)));
                }
                continue;
            }
            if (usage.severity() == ConflictSeverity.BLOCKED) {
                excluded.add(new BomProposal.Excluded(usage.key(), versionsOf(usage), usage.severity(),
                    "Major version skew between " + String.join(" and ", versionsOf(usage))
                        + ". That is an API migration, so it gets scheduled rather than generated."));
                continue;
            }
            if (!worthManaging(usage)) {
                continue;
            }
            entries.add(entry(usage));
        }

        return new BomProposal(List.copyOf(entries), List.copyOf(excluded));
    }

    /**
     * Why an artifact an imported BOM owns is still worth a row.
     *
     * <p>Two shapes, and they want different sentences. Usually a module hand-pins a
     * version on top of a BOM that already decided one, which is straightforwardly a
     * thing to delete. Occasionally no module pins anything and the versions still
     * differ, which means the modules import different releases of the same BOM — a
     * bigger problem wearing smaller clothes.
     */
    private static String alreadyManagedReason(ArtifactUsage usage) {
        String managed = usage.versions().stream()
            .filter(v -> v.origins().contains(VersionOrigin.IMPORTED_BOM))
            .map(VersionUse::version)
            .findFirst()
            .orElse(usage.highestVersion());

        List<VersionUse> pinned = usage.versions().stream()
            .filter(v -> v.origins().contains(VersionOrigin.LOCAL_DECLARATION))
            .toList();

        if (pinned.isEmpty()) {
            return usage.managedBy() + " manages this, but the modules are on "
                + String.join(" and ", versionsOf(usage))
                + " - they are importing different releases of it. Align the import, "
                + "which is a bigger fix than this BOM can make.";
        }

        String pins = pinned.stream()
            .map(v -> String.join(", ", v.modules()) + " on " + v.version())
            .collect(Collectors.joining("; "));
        return usage.managedBy() + " already manages this at " + managed + ", but " + pins
            + ". Delete the hand-pinned version rather than adding another that beats it.";
    }

    /**
     * Something to take over: a disagreement, or a version written out by hand somewhere.
     */
    private static boolean worthManaging(ArtifactUsage usage) {
        if (usage.isConflict()) {
            return true;
        }
        return usage.versions().stream()
            .anyMatch(version -> version.origins().contains(VersionOrigin.LOCAL_DECLARATION));
    }

    private static BomProposal.Entry entry(ArtifactUsage usage) {
        String chosen = usage.highestVersion();
        List<String> replaces = versionsOf(usage).stream().filter(v -> !v.equals(chosen)).toList();
        return new BomProposal.Entry(
            usage.key(), chosen, replaces, usage.severity(), modulesOf(usage), rationale(usage, chosen, replaces));
    }

    /**
     * A held artifact is proposed at the version the hold names, whatever the skew.
     *
     * <p>Deliberately not excluded even when the skew is major: the hold <em>is</em> the
     * decision that a {@code BLOCKED} row is otherwise asking someone to make. Writing it
     * into the BOM is how the decision stops being re-argued every scan.
     */
    private static BomProposal.Entry heldEntry(ArtifactUsage usage) {
        VersionHold hold = usage.hold();
        String chosen = hold.pinsAnyVersion() ? usage.highestVersion() : hold.version();
        List<String> replaces = versionsOf(usage).stream().filter(v -> !v.equals(chosen)).toList();

        StringBuilder rationale = new StringBuilder("Held at ").append(chosen).append(" - ").append(hold.reason());
        if (hold.until() != null && !hold.until().isBlank()) {
            rationale.append(" (revisit: ").append(hold.until()).append(')');
        }
        return new BomProposal.Entry(
            usage.key(), chosen, replaces, usage.severity(), modulesOf(usage), rationale.toString());
    }

    private static String rationale(ArtifactUsage usage, String chosen, List<String> replaces) {
        return switch (usage.severity()) {
            // A single-module project is the common case for this tool and has no
            // "every module" to speak of. What it gets from a BOM is not agreement - there
            // is nobody to agree with - but the separation of the version from the
            // dependency, so saying "every module agrees" there would be faintly absurd.
            case UNIFORM -> usage.moduleCount() == 1
                ? "Only " + usage.versions().get(0).modules().get(0) + " declares it, at "
                    + chosen + ". Managing it here separates the version from the dependency."
                : "All " + usage.moduleCount() + " modules are already on " + chosen
                    + "; managing it here keeps it that way when the next one is added.";
            case SAFE -> "Patch-level skew only (" + String.join(", ", replaces)
                + " -> " + chosen + "). Additive by semantic versioning, so no reviewer would pick anything else.";
            case REVIEW -> "Minor skew (" + String.join(", ", replaces) + " -> " + chosen
                + "). New API is safe; check that nothing was pinned low on purpose.";
            default -> "Proposed at " + chosen + ".";
        };
    }

    private static List<String> versionsOf(ArtifactUsage usage) {
        return usage.versions().stream().map(VersionUse::version).toList();
    }

    private static List<String> modulesOf(ArtifactUsage usage) {
        Set<String> modules = new LinkedHashSet<>();
        usage.versions().forEach(version -> modules.addAll(version.modules()));
        return modules.stream().sorted().toList();
    }
}
