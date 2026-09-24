package com.boeing.depgraph.scanner;

import com.boeing.depgraph.code.ClassPlaneScanner;
import com.boeing.depgraph.code.CodeScanResult;
import com.boeing.depgraph.maven.DependencyClosure;
import com.boeing.depgraph.model.ResolvedArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs both planes and hands back one result.
 *
 * <p>The order is not optional. The code plane needs the build plane's answer twice
 * over: which directories are modules, and which jars the declared dependencies resolve
 * to. Reading bytecode first would mean scanning whatever happened to be on disk with no
 * way to attribute any of it — and attribution is the entire point of the
 * {@code FROM} bridge.
 */
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    private final BuildPlaneScanner buildPlane;
    private final ClassPlaneScanner classPlane;
    private final DependencyClosure closure;

    public ProjectScanner(BuildPlaneScanner buildPlane, ClassPlaneScanner classPlane,
                          DependencyClosure closure) {
        this.buildPlane = buildPlane;
        this.classPlane = classPlane;
        this.closure = closure;
    }

    public static ProjectScanner withLocalRepository(Path localRepository, boolean offline) {
        return new ProjectScanner(
            BuildPlaneScanner.withLocalRepository(localRepository, offline),
            new ClassPlaneScanner(),
            DependencyClosure.forLocalRepository(localRepository, offline));
    }

    // ------------------------------------------------------------------

    /** Build plane only — for a project that has never been compiled. */
    public ScanResult scanBuildPlane(Path scanRoot) {
        return buildPlane.scan(scanRoot);
    }

    /**
     * Both planes.
     *
     * <p>Third-party jars are read surface-only, so the cost of including them is far
     * below what the class count suggests: on this estate 32,000 of 33,000 types come
     * from jars and contribute signatures alone.
     */
    public ScanResult scan(Path scanRoot) {
        ScanResult build = buildPlane.scan(scanRoot);

        // The full classpath, not just what the poms name. Reading only declared jars left
        // every transitively supplied class out of the graph, and an edge whose target is
        // missing is not a smaller graph - it is a silently wrong one.
        List<ResolvedArtifact> classpath = closure.resolve(build.dependencies());
        List<Path> jars = classpath.stream()
            .map(ResolvedArtifact::jarPath)
            .distinct()
            .map(Path::of)
            .filter(Files::isRegularFile)
            .toList();
        CodeScanResult code = classPlane.scan(build.modules(), jars);

        if (!code.modulesWithoutClasses().isEmpty()) {
            // Named, not absorbed. This is the one price of reading bytecode.
            log.warn("{} module(s) contributed no code because they have not been built",
                code.modulesWithoutClasses().size());
        }

        return new ScanResult(
            build.projectPath(), build.projectName(), build.modules(), build.dependencies(),
            build.managed(), build.links(), build.artifacts(), build.proposal(),
            build.holds(), build.problems(), classpath, code, build.stats());
    }
}
