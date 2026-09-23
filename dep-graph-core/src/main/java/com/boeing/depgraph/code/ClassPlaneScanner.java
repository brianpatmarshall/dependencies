package com.boeing.depgraph.code;

import com.boeing.depgraph.model.FieldRef;
import com.boeing.depgraph.model.ModuleRef;
import com.boeing.depgraph.model.TypeRef;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Reads compiled bytecode and builds the code plane.
 *
 * <p>Two sources, one visitor. A module's own {@code target/classes} is read in full;
 * a third-party jar is read surface-only, which skips every method body. The decision is
 * made once here and carried into {@link AsmClassVisitor} as a flag.
 *
 * <p>Requires the scan target to be built. That is the single price of reading bytecode
 * rather than source, it is paid knowingly, and modules that have not been built are
 * named in the result rather than quietly contributing nothing.
 */
public class ClassPlaneScanner {

    private static final Logger log = LoggerFactory.getLogger(ClassPlaneScanner.class);

    /**
     * Packages whose types are never worth an edge.
     *
     * <p>Without this every class in the estate depends on {@code java.lang.Object},
     * {@code java.lang.String} and {@code java.util.List}, which is true, useless, and
     * roughly doubles the graph. The JDK is not a dependency anybody manages.
     */
    private static final Set<String> IGNORED_PREFIXES = Set.of(
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.dom.", "org.xml.sax.");

    private final Predicate<String> included;

    public ClassPlaneScanner() {
        this(fqn -> IGNORED_PREFIXES.stream().noneMatch(fqn::startsWith));
    }

    /** @param included which target types earn an edge. */
    public ClassPlaneScanner(Predicate<String> included) {
        this.included = included;
    }

    // ------------------------------------------------------------------

    /**
     * Scan each module's own compiled output, plus any jars supplied.
     *
     * @param jars third-party artifacts, read surface-only. Resolving which jars belong to
     *             which module is the build plane's job; this method only reads what it
     *             is handed
     */
    public CodeScanResult scan(List<ModuleRef> modules, List<Path> jars) {
        long started = System.currentTimeMillis();
        TypeSink sink = new TypeSink(included);

        List<String> missing = new ArrayList<>();
        int classFiles = 0;
        int scanned = 0;

        for (ModuleRef module : modules) {
            Path classes = classesDirOf(module);
            if (classes == null) {
                // An aggregator or a BOM compiles nothing and is not missing anything.
                if (!"pom".equals(module.packaging())) {
                    missing.add(module.label());
                }
                continue;
            }
            classFiles += scanDirectory(classes, sink);
            scanned++;
        }

        int jarsRead = 0;
        for (Path jar : jars) {
            int read = scanJar(jar, sink);
            if (read > 0) {
                classFiles += read;
                jarsRead++;
            }
        }

        CodeScanResult result = assemble(sink, missing, classFiles, scanned, jarsRead,
            System.currentTimeMillis() - started);

        log.info("Code plane: {} types, {} edges from {} class files ({} modules, {} jars) in {} ms",
            result.stats().typesFound(), result.stats().edgeCount(), classFiles,
            scanned, jarsRead, result.stats().durationMillis());
        if (!missing.isEmpty()) {
            log.warn("{} module(s) have no target/classes and contributed nothing: {}",
                missing.size(), String.join(", ", missing));
        }
        return result;
    }

    /** Convenience for scanning one directory of class files. */
    public CodeScanResult scanDirectory(Path classesDir) {
        long started = System.currentTimeMillis();
        TypeSink sink = new TypeSink(included);
        int classFiles = scanDirectory(classesDir, sink);
        return assemble(sink, List.of(), classFiles, 1, 0, System.currentTimeMillis() - started);
    }

    // ------------------------------------------------------------------

    /** {@code <module dir>/target/classes}, or null when it does not exist. */
    static Path classesDirOf(ModuleRef module) {
        Path pom = Path.of(module.pomPath());
        Path parent = pom.getParent();
        if (parent == null) {
            return null;
        }
        Path classes = parent.resolve("target").resolve("classes");
        return Files.isDirectory(classes) ? classes : null;
    }

    private int scanDirectory(Path root, TypeSink sink) {
        int count = 0;
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> classFiles = files
                .filter(p -> p.getFileName().toString().endsWith(".class"))
                .sorted()
                .toList();
            for (Path file : classFiles) {
                try (InputStream in = Files.newInputStream(file)) {
                    if (read(in, root.toString(), false, sink)) {
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read classes under " + root, e);
        }
        return count;
    }

    private int scanJar(Path jar, TypeSink sink) {
        if (!Files.isRegularFile(jar)) {
            log.debug("No jar at {}", jar);
            return 0;
        }
        int count = 0;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                // Multi-release jars carry the same class several times over; the base
                // version is the one every JDK agrees on.
                if (entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = jarFile.getInputStream(entry)) {
                    if (read(in, jar.toString(), true, sink)) {
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            // A broken jar is skipped, not fatal. One unreadable artifact in a local
            // repository must not cost the whole scan.
            log.warn("Skipping unreadable jar {}: {}", jar, e.getMessage());
            return 0;
        }
        return count;
    }

    /**
     * @return false when the class file could not be parsed, which is not fatal
     */
    private boolean read(InputStream in, String originPath, boolean thirdParty, TypeSink sink) {
        try {
            // SKIP_FRAMES and SKIP_DEBUG: stack map frames and line numbers cost time to
            // parse and carry no type references. Everything this tool reports survives.
            new ClassReader(in).accept(
                new AsmClassVisitor(sink, originPath, thirdParty),
                ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            return true;
        } catch (IOException | RuntimeException e) {
            // Chiefly IllegalArgumentException from a class file newer than this ASM.
            log.warn("Could not read a class file from {}: {}", originPath, e.toString());
            return false;
        }
    }

    private static CodeScanResult assemble(TypeSink sink, List<String> missing, int classFiles,
                                           int modulesScanned, int jarsRead, long duration) {
        List<TypeRef> types = sink.types();
        var edges = sink.edges();
        return new CodeScanResult(
            types, sink.methods(), sink.fields(), edges, List.copyOf(missing),
            new CodeScanResult.CodeStats(
                classFiles,
                types.size(),
                sink.methods().size(),
                sink.fields().size(),
                edges.size(),
                edges.stream().mapToInt(e -> e.count()).sum(),
                (int) types.stream().filter(TypeRef::thirdParty).count(),
                jarsRead,
                modulesScanned,
                missing.size(),
                (int) sink.fields().stream().filter(FieldRef::constant).count(),
                duration));
    }
}
