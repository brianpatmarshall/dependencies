package com.boeing.depgraph.maven;

import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.Gav;
import com.boeing.depgraph.model.ManagedDependency;
import com.boeing.depgraph.model.ModuleRef;
import com.boeing.depgraph.model.VersionOrigin;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProblem;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Turns a pom file into the dependencies the build will really use.
 *
 * <h2>Why not read the XML</h2>
 * Because the XML does not say. In any multi-module project worth analysing, most
 * {@code <dependency>} elements have no {@code <version>} at all, and most of the ones
 * that do say {@code ${something.version}} — a property that may be defined three poms
 * up, or overridden by a profile, or come from an imported BOM. A tool that reads the
 * text reports the property name and calls it a version, which makes every conflict
 * table it produces fiction.
 *
 * <p>Maven's own {@code ModelBuilder} does the whole job: inheritance, interpolation,
 * profile activation, dependency management and BOM imports, ending in an effective model
 * whose every dependency carries the number javac will see.
 *
 * <h2>Keeping the receipts</h2>
 * The effective model alone would lose something important — that a version was decided
 * by Spring Boot's BOM rather than by the module. Location tracking is switched on so
 * every resolved version also carries the model that decided it, and
 * {@link #classifyOrigin} turns that into a {@link VersionOrigin}. It is the difference
 * between a BOM proposal that fixes drift and one that causes it.
 */
public class EffectiveModelReader {

    private static final Logger log = LoggerFactory.getLogger(EffectiveModelReader.class);

    private static final String CENTRAL_URL = "https://repo.maven.apache.org/maven2/";

    private final RepositorySystem system;
    private final DefaultRepositorySystemSession session;
    private final List<RemoteRepository> remoteRepositories;
    private final ModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
    private final Properties systemProperties;

    /**
     * @param localRepository the {@code ~/.m2/repository} to read parents and BOMs from.
     *                        In the container this is the read-only {@code /m2} mount
     * @param offline         true to fail rather than reach the network. The default for a
     *                        scan: a run whose duration depends on what someone else's
     *                        Nexus is doing today is not a run anyone will trust
     */
    public EffectiveModelReader(Path localRepository, boolean offline) {
        this.system = new RepositorySystemSupplier().get();
        this.session = new DefaultRepositorySystemSession();
        this.session.setLocalRepositoryManager(system.newLocalRepositoryManager(
            session, new LocalRepository(localRepository.toFile())));
        this.session.setOffline(offline);
        this.remoteRepositories = offline
            ? List.of()
            : List.of(new RemoteRepository.Builder("central", "default", CENTRAL_URL).build());

        // Profile activation reads jdk and os properties off here, so a scan on this JDK
        // resolves the profiles this JDK would activate.
        this.systemProperties = new Properties();
        this.systemProperties.putAll(System.getProperties());

        log.info("Model reader ready: localRepository={} offline={}", localRepository, offline);
    }

    // ------------------------------------------------------------------

    public ModuleModel read(Path pom, Path scanRoot) {
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile(pom.toFile());
        request.setModelResolver(new RepositoryModelResolver(system, session, remoteRepositories));
        request.setSystemProperties(systemProperties);

        // MINIMAL, not STRICT: the job is to read what is there, not to referee it. A pom
        // that trips a strict-mode warning still builds on someone's machine, and refusing
        // to report its dependencies would hide a real conflict behind a style complaint.
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

        // No plugin resolution. Plugins are not dependencies, and resolving them would
        // multiply the network work by an order of magnitude for nothing this tool reports.
        request.setProcessPlugins(false);
        request.setTwoPhaseBuilding(false);

        // The reason any of this is attributable at all.
        request.setLocationTracking(true);

        try {
            ModelBuildingResult result = modelBuilder.build(request);
            return toModuleModel(result, pom, scanRoot);
        } catch (ModelBuildingException e) {
            return failed(e, pom, scanRoot);
        }
    }

    // ------------------------------------------------------------------

    private ModuleModel toModuleModel(ModelBuildingResult result, Path pom, Path scanRoot) {
        Model effective = result.getEffectiveModel();
        Model raw = result.getRawModel();
        ModuleRef module = toModuleRef(effective, pom, scanRoot);

        // The lineage: this pom and its parents. Anything that decided a version and is
        // NOT in here decided it from an imported BOM.
        Set<String> lineage = new LinkedHashSet<>(result.getModelIds());
        String ownId = result.getModelIds().isEmpty() ? null : result.getModelIds().get(0);

        Map<String, String> rawVersions = rawVersionsByKey(raw);

        List<DeclaredDependency> dependencies = new ArrayList<>();
        for (Dependency dependency : effective.getDependencies()) {
            String rawVersion = rawVersions.get(dependencyKey(dependency));
            String decidedBy = modelIdOfVersion(dependency);
            VersionOrigin origin = classifyOrigin(rawVersion, decidedBy, ownId, lineage);

            dependencies.add(new DeclaredDependency(
                module,
                new Gav(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion()),
                dependency.getType() == null ? "jar" : dependency.getType(),
                dependency.getClassifier() == null ? "" : dependency.getClassifier(),
                dependency.getScope() == null ? "compile" : dependency.getScope(),
                dependency.isOptional(),
                rawVersion,
                origin,
                decidedBy == null ? "" : decidedBy));
        }

        return new ModuleModel(
            module,
            dependencies,
            managedBy(module, raw),
            raw == null ? List.of() : List.copyOf(raw.getModules()),
            problems(result.getProblems()));
    }

    /**
     * The module's own dependencyManagement, read from the RAW model.
     *
     * <p>The effective model's block is the flattened union of every BOM in the lineage -
     * around 2,500 entries for anything importing Spring Boot. What this pom decided is a
     * much shorter and much more interesting list, and it is the only one that exists in
     * a file a human can edit.
     */
    private static List<ManagedDependency> managedBy(ModuleRef module, Model raw) {
        if (raw == null || raw.getDependencyManagement() == null) {
            return List.of();
        }
        List<ManagedDependency> managed = new ArrayList<>();
        for (Dependency dependency : raw.getDependencyManagement().getDependencies()) {
            String type = dependency.getType() == null ? "jar" : dependency.getType();
            boolean bomImport = "pom".equals(type) && "import".equals(dependency.getScope());
            managed.add(new ManagedDependency(
                module,
                new Gav(dependency.getGroupId(), dependency.getArtifactId(),
                    interpolate(dependency.getVersion(), raw)),
                type,
                dependency.getClassifier() == null ? "" : dependency.getClassifier(),
                dependency.getScope() == null ? "" : dependency.getScope(),
                dependency.getVersion(),
                bomImport));
        }
        return List.copyOf(managed);
    }

    /**
     * Resolve a raw {@code ${property}} against the raw model's own properties.
     *
     * <p>A deliberately shallow interpolation, and the only place in this class that does
     * any. The raw model is pre-interpolation by definition, but a managed version is
     * almost always written as a property defined in the same file, and reporting
     * {@code ${spring-boot.version}} as the version a BOM import pins would make the
     * IMPORTS_BOM edge useless. Where the property lives in a parent instead, the raw
     * text survives on {@code rawVersion} and the version is left as written rather than
     * guessed at.
     */
    private static String interpolate(String version, Model raw) {
        if (version == null || !version.contains("${")) {
            return version;
        }
        String resolved = version;
        for (String name : raw.getProperties().stringPropertyNames()) {
            resolved = resolved.replace("${" + name + "}", raw.getProperties().getProperty(name));
        }
        if (raw.getVersion() != null) {
            resolved = resolved.replace("${project.version}", raw.getVersion());
        }
        return resolved;
    }

    /**
     * Who decided this dependency's version, as Maven names models.
     *
     * <p>For an imported BOM the id is the BOM's own GAV, which is how a row in the
     * conflict table can say "spring-boot-dependencies:3.5.16 already manages this"
     * instead of merely "managed elsewhere".
     */
    private static String modelIdOfVersion(Dependency dependency) {
        InputLocation location = dependency.getLocation("version");
        if (location == null || location.getSource() == null) {
            return null;
        }
        return location.getSource().getModelId();
    }

    /**
     * The module's own word beats everything.
     *
     * <p>A version written in this pom's {@code <dependencies>} is a choice someone made
     * here, whether spelled out or reached through a property, and it is the only origin
     * that means "changing this will be noticed in this file". Everything else is
     * somebody upstream answering a question the module did not ask.
     */
    static VersionOrigin classifyOrigin(String rawVersion, String decidedBy,
                                        String ownId, Set<String> lineage) {
        if (rawVersion != null) {
            return VersionOrigin.LOCAL_DECLARATION;
        }
        if (decidedBy == null || decidedBy.isBlank()) {
            return VersionOrigin.UNKNOWN;
        }
        if (decidedBy.equals(ownId)) {
            return VersionOrigin.LOCAL_MANAGEMENT;
        }
        return lineage.contains(decidedBy)
            ? VersionOrigin.INHERITED_MANAGEMENT
            : VersionOrigin.IMPORTED_BOM;
    }

    /**
     * Versions exactly as the pom writes them, keyed the way Maven keys a dependency.
     *
     * <p>Type and classifier are part of the key because a module perfectly legitimately
     * depends on both {@code foo} and {@code foo:tests}, at different versions, and
     * collapsing them would invent a conflict.
     */
    private static Map<String, String> rawVersionsByKey(Model raw) {
        Map<String, String> versions = new HashMap<>();
        if (raw == null) {
            return versions;
        }
        for (Dependency dependency : raw.getDependencies()) {
            if (dependency.getVersion() != null) {
                versions.put(dependencyKey(dependency), dependency.getVersion());
            }
        }
        return versions;
    }

    private static String dependencyKey(Dependency dependency) {
        return dependency.getGroupId() + ":" + dependency.getArtifactId()
            + ":" + (dependency.getType() == null ? "jar" : dependency.getType())
            + ":" + (dependency.getClassifier() == null ? "" : dependency.getClassifier());
    }

    private static ModuleRef toModuleRef(Model model, Path pom, Path scanRoot) {
        Gav parent = model.getParent() == null ? null : new Gav(
            model.getParent().getGroupId(),
            model.getParent().getArtifactId(),
            model.getParent().getVersion());

        Path absolute = pom.toAbsolutePath().normalize();
        return new ModuleRef(
            model.getGroupId(),
            model.getArtifactId(),
            model.getVersion(),
            model.getPackaging() == null ? "jar" : model.getPackaging(),
            model.getName() == null ? model.getArtifactId() : model.getName(),
            absolute.toString(),
            relativize(scanRoot, absolute),
            parent,
            !model.getModules().isEmpty());
    }

    private static String relativize(Path scanRoot, Path pom) {
        try {
            return scanRoot.toAbsolutePath().normalize().relativize(pom).toString();
        } catch (IllegalArgumentException e) {
            // Different roots - a symlink out of the tree. The absolute path is still true.
            return pom.toString();
        }
    }

    /**
     * A pom that would not build a model.
     *
     * <p>Overwhelmingly this is an unresolvable parent: the project was mounted without
     * the local repository that holds its parent pom, or it is genuinely offline and the
     * parent was never installed. Recording it as a named problem against a placeholder
     * module is what lets the report say "four modules could not be read, here they are"
     * rather than quietly reporting a smaller project than the one on disk.
     */
    private ModuleModel failed(ModelBuildingException e, Path pom, Path scanRoot) {
        Model raw = e.getResult() == null ? null : e.getResult().getRawModel();
        ModuleRef module = raw == null ? null : toModuleRef(raw, pom, scanRoot);
        List<String> problems = problems(e.getProblems());
        if (problems.isEmpty()) {
            problems = List.of(e.getMessage());
        }
        log.warn("Could not build a model for {}: {}", pom, problems.get(0));
        return new ModuleModel(module, List.of(), List.of(), List.of(), problems);
    }

    private static List<String> problems(List<ModelProblem> problems) {
        List<String> messages = new ArrayList<>();
        for (ModelProblem problem : problems) {
            if (problem.getSeverity() == ModelProblem.Severity.ERROR
                || problem.getSeverity() == ModelProblem.Severity.FATAL) {
                messages.add(problem.getSeverity() + ": " + problem.getMessage()
                    + (problem.getSource() == null ? "" : " (" + problem.getSource() + ")"));
            }
        }
        return messages;
    }
}
