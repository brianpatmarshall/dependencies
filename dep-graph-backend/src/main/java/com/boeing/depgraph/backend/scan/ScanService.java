package com.boeing.depgraph.backend.scan;

import com.boeing.depgraph.backend.config.DepGraphProperties;
import com.boeing.depgraph.backend.graph.GraphPopulator;
import com.boeing.depgraph.backend.graph.OriginIndex;
import com.boeing.depgraph.scanner.NotAMavenProjectException;
import com.boeing.depgraph.scanner.ProjectScanner;
import com.boeing.depgraph.scanner.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs a scan and writes the result into the graph.
 *
 * <p>Synchronous on purpose. A full estate scan is about ten seconds of analysis plus the
 * graph write, which is inside what an HTTP client will wait for, and a job queue would be
 * a second source of truth about what the graph currently holds.
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final GraphPopulator populator;
    private final DepGraphProperties properties;
    private final AtomicReference<ScanSummary> last = new AtomicReference<>();

    public ScanService(GraphPopulator populator, DepGraphProperties properties) {
        this.populator = populator;
        this.properties = properties;
    }

    public ScanSummary scan(String path, boolean buildPlaneOnly, Consumer<String> progress) {
        Path root = resolve(path);
        long started = System.currentTimeMillis();

        progress.accept("Scanning " + root);
        ProjectScanner scanner = ProjectScanner.withLocalRepository(
            properties.localRepository(), properties.offline());

        ScanResult result = buildPlaneOnly
            ? scanner.scanBuildPlane(root)
            : scanner.scan(root);

        progress.accept("Read " + result.stats().modulesRead() + " modules, "
            + result.stats().dependencyCount() + " dependencies");

        OriginIndex origins = OriginIndex.build(result.modules(), result.classpath());

        populator.populate(result, origins, progress);

        ScanSummary summary = ScanSummary.of(result, System.currentTimeMillis() - started);
        last.set(summary);
        log.info("Scan of {} complete in {} ms", root, summary.totalMillis());
        return summary;
    }

    public ScanSummary lastScan() {
        return last.get();
    }

    /**
     * Checks the path before anything is opened.
     *
     * <p>A security boundary, not a convenience setting. The scanner reads every pom and
     * class file under whatever it is given, so an unchecked path is an arbitrary file
     * read. In compose this is narrowed to the read-only mounts.
     */
    private Path resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("No path given");
        }
        Path candidate = Path.of(path).toAbsolutePath().normalize();
        if (!Files.exists(candidate)) {
            throw new NotAMavenProjectException(candidate,
                "nothing exists there. Note that paths are container paths: the scan "
                    + "root is mounted at /projects.");
        }

        List<Path> allowed = properties.allowedRoots();
        boolean permitted = allowed.stream()
            .map(root -> root.toAbsolutePath().normalize())
            .anyMatch(candidate::startsWith);
        if (!permitted) {
            throw new IllegalArgumentException(
                candidate + " is outside the allowed roots " + allowed);
        }
        return candidate;
    }
}
