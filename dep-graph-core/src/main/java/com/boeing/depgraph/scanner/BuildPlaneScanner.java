package com.boeing.depgraph.scanner;

import com.boeing.depgraph.conflict.BomProposer;
import com.boeing.depgraph.conflict.ConflictAnalyzer;
import com.boeing.depgraph.conflict.HoldsFile;
import com.boeing.depgraph.maven.EffectiveModelReader;
import com.boeing.depgraph.maven.ModuleModel;
import com.boeing.depgraph.maven.PomLocator;
import com.boeing.depgraph.model.ArtifactUsage;
import com.boeing.depgraph.model.BomProposal;
import com.boeing.depgraph.model.ConflictSeverity;
import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.ManagedDependency;
import com.boeing.depgraph.model.ModuleLink;
import com.boeing.depgraph.model.ModuleRelation;
import com.boeing.depgraph.model.ModuleRef;
import com.boeing.depgraph.model.VersionHold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads every pom under a root and works out what the project depends on.
 *
 * <p>The build plane, end to end: find the poms, build each one's effective model, group
 * the result by artifact, classify the skew, propose the BOM. No bytecode is read and no
 * compiled output is needed — this half of the tool works on a project that has never
 * been built.
 */
public class BuildPlaneScanner {

    private static final Logger log = LoggerFactory.getLogger(BuildPlaneScanner.class);

    private final EffectiveModelReader reader;

    public BuildPlaneScanner(EffectiveModelReader reader) {
        this.reader = reader;
    }

    /** Local repository and offline flag as the container sets them. */
    public static BuildPlaneScanner withLocalRepository(Path localRepository, boolean offline) {
        return new BuildPlaneScanner(new EffectiveModelReader(localRepository, offline));
    }

    // ------------------------------------------------------------------

    public ScanResult scan(Path scanRoot) {
        return scan(scanRoot, progress -> { });
    }

    /**
     * @param progress called once per pom, for the progress stream the UI subscribes to.
     *                 A scan of a large estate takes long enough that a spinner with no
     *                 numbers behind it reads as a hang
     */
    public ScanResult scan(Path scanRoot, Consumer<Progress> progress) {
        long started = System.currentTimeMillis();
        Path root = normalise(scanRoot);

        List<Path> poms;
        try {
            poms = PomLocator.findPoms(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root, e);
        }
        if (poms.isEmpty()) {
            throw new NotAMavenProjectException(root,
                "No pom.xml found anywhere under " + root
                    + ". This tool reads Maven projects; point it at a repository root "
                    + "or at a pom.xml.");
        }

        List<ModuleRef> modules = new ArrayList<>();
        List<DeclaredDependency> dependencies = new ArrayList<>();
        List<ManagedDependency> managed = new ArrayList<>();
        List<ModuleModel> models = new ArrayList<>();
        List<ScanResult.ModuleProblem> problems = new ArrayList<>();

        int index = 0;
        for (Path pom : poms) {
            index++;
            ModuleModel model = reader.read(pom, root);
            models.add(model);
            if (model.module() != null) {
                modules.add(model.module());
                dependencies.addAll(model.dependencies());
                managed.addAll(model.managed());
            }
            if (!model.problems().isEmpty()) {
                problems.add(new ScanResult.ModuleProblem(
                    pom.toString(),
                    relativize(root, pom),
                    model.problems()));
            }
            progress.accept(new Progress(index, poms.size(), relativize(root, pom),
                model.problems().isEmpty()));
        }

        // Linking is a second pass on purpose: a module cannot be told from a third-party
        // artifact until every pom in the scan has been read.
        List<ModuleLink> links = ProjectTopology.link(models);

        List<VersionHold> holds = HoldsFile.readFrom(root);
        List<ArtifactUsage> artifacts = ConflictAnalyzer.analyze(dependencies, holds);
        BomProposal proposal = BomProposer.propose(artifacts);

        long duration = System.currentTimeMillis() - started;
        ScanResult.ScanStats stats = stats(poms.size(), modules, dependencies, managed, links,
            artifacts, problems, proposal, duration);

        log.info("Scanned {}: {} modules ({} linked), {} dependencies, {} artifacts, "
                + "{} conflicts ({} blocked) in {} ms",
            root, stats.modulesRead(), links.size(), stats.dependencyCount(),
            stats.artifactCount(), stats.conflictCount(), stats.blockedCount(), duration);

        return new ScanResult(
            root.toString(),
            projectName(root, modules),
            List.copyOf(modules),
            List.copyOf(dependencies),
            List.copyOf(managed),
            links,
            artifacts,
            proposal,
            holds,
            List.copyOf(problems),
            null,
            stats);
    }

    /** One pom read, for the progress stream. */
    public record Progress(int done, int total, String pom, boolean ok) {}

    // ------------------------------------------------------------------

    private static ScanResult.ScanStats stats(int pomsFound,
                                              List<ModuleRef> modules,
                                              List<DeclaredDependency> dependencies,
                                              List<ManagedDependency> managed,
                                              List<ModuleLink> links,
                                              List<ArtifactUsage> artifacts,
                                              List<ScanResult.ModuleProblem> problems,
                                              BomProposal proposal,
                                              long duration) {
        return new ScanResult.ScanStats(
            pomsFound,
            modules.size(),
            problems.size(),
            dependencies.size(),
            managed.size(),
            (int) managed.stream().filter(ManagedDependency::bomImport).count(),
            countByRelation(links, ModuleRelation.DEPENDS_ON_MODULE),
            artifacts.size(),
            (int) artifacts.stream().filter(ArtifactUsage::isConflict).count(),
            countBySeverity(artifacts, ConflictSeverity.SAFE),
            countBySeverity(artifacts, ConflictSeverity.REVIEW),
            countBySeverity(artifacts, ConflictSeverity.BLOCKED),
            countBySeverity(artifacts, ConflictSeverity.ALREADY_MANAGED),
            (int) artifacts.stream().filter(ArtifactUsage::held).count(),
            proposal.entries().size(),
            duration);
    }

    private static int countByRelation(List<ModuleLink> links, ModuleRelation relation) {
        return (int) links.stream().filter(l -> l.relation() == relation).count();
    }

    private static int countBySeverity(List<ArtifactUsage> artifacts, ConflictSeverity severity) {
        return (int) artifacts.stream().filter(a -> a.severity() == severity).count();
    }

    private static String relativize(Path root, Path pom) {
        try {
            return root.relativize(pom).toString();
        } catch (IllegalArgumentException e) {
            return pom.toString();
        }
    }

    /**
     * Accept a repository root or a pom file, and reject anything else clearly.
     *
     * <p>Someone pointing this at a project is as likely to type the path of the pom as
     * the path of the directory holding it, and refusing one of the two would be
     * pedantry. Everything else - a missing path, a jar, a java file - is a mistake worth
     * naming rather than absorbing.
     */
    static Path normalise(Path scanRoot) {
        Path path = scanRoot.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            return path;
        }
        if (!Files.exists(path)) {
            throw new NotAMavenProjectException(path, "No such file or directory: " + path);
        }
        if ("pom.xml".equals(path.getFileName().toString())) {
            Path parent = path.getParent();
            if (parent != null) {
                return parent;
            }
        }
        throw new NotAMavenProjectException(path,
            path + " is a file, not a project directory. Point at the directory that "
                + "contains the pom, or at the pom itself.");
    }

    /**
     * What to call this project.
     *
     * <p>The pom at the scan root knows its own name; the directory only knows what
     * someone called the checkout. Prefer the pom, and fall back to the directory for a
     * scan root that merely contains projects rather than being one - which is the normal
     * case for an estate.
     */
    private static String projectName(Path root, List<ModuleRef> modules) {
        String rootPom = root.resolve("pom.xml").toString();
        for (ModuleRef module : modules) {
            if (module.pomPath().equals(rootPom)) {
                return module.name() == null || module.name().isBlank()
                    ? module.artifactId()
                    : module.name();
            }
        }
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }

    /** {@code ~/.m2/repository} unless {@code M2_REPO} says otherwise. */
    public static Path defaultLocalRepository() {
        String configured = System.getenv("M2_REPO");
        return configured != null && !configured.isBlank()
            ? Paths.get(configured)
            : Paths.get(System.getProperty("user.home"), ".m2", "repository");
    }
}
