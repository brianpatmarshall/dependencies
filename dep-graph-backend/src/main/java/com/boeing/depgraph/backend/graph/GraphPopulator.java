package com.boeing.depgraph.backend.graph;

import com.boeing.depgraph.backend.config.DepGraphProperties;
import com.boeing.depgraph.code.CodeScanResult;
import com.boeing.depgraph.model.ArtifactUsage;
import com.boeing.depgraph.model.BomProposal;
import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.Gav;
import com.boeing.depgraph.model.ManagedDependency;
import com.boeing.depgraph.model.ModuleLink;
import com.boeing.depgraph.model.ModuleRef;
import com.boeing.depgraph.model.TypeEdge;
import com.boeing.depgraph.model.TypeRef;
import com.boeing.depgraph.model.VersionUse;
import com.boeing.depgraph.scanner.ScanResult;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Writes a {@link ScanResult} into Neo4J.
 *
 * <p>Hand-written Cypher over the raw driver, the same call the estate's other two
 * scanners made. Everything here is a bulk {@code UNWIND} of a few thousand rows against
 * an indexed {@code MERGE} key; an OGM would turn each of those into a round trip.
 *
 * <p>The order is fixed and every step depends on the one before it. Artifacts exist
 * before anything points at them, types exist before their edges, and the two rollups run
 * last because they are computed by the database from what the earlier steps wrote rather
 * than sent from here.
 */
@Component
public class GraphPopulator {

    private static final Logger log = LoggerFactory.getLogger(GraphPopulator.class);

    private final Driver driver;
    private final DepGraphProperties properties;
    private final GraphSchema schema;

    public GraphPopulator(Driver driver, DepGraphProperties properties) {
        this.driver = driver;
        this.properties = properties;
        this.schema = new GraphSchema(driver);
    }

    /**
     * @param progress called with a one-line description of each step, for the CLI and
     *                 the UI to show. A full estate scan writes for long enough that
     *                 silence is indistinguishable from a hang
     */
    public void populate(ScanResult result, OriginIndex origins, Consumer<String> progress) {
        long started = System.currentTimeMillis();

        progress.accept("Clearing the graph");
        schema.wipe();
        schema.apply();

        progress.accept("Writing project and modules");
        writeProject(result);
        writeModules(result);

        progress.accept("Writing artifacts and versions");
        writeArtifactVersions(result);
        writeArtifacts(result);

        progress.accept("Writing declared and managed dependencies");
        writeDeclaredDependencies(result);
        writeManagedDependencies(result);

        progress.accept("Writing pom topology");
        writeModuleLinks(result);

        progress.accept("Writing the proposed BOM");
        writeBomProposal(result);
        writeProblems(result);

        if (result.hasCodePlane()) {
            CodeScanResult code = result.code();
            progress.accept("Writing " + code.types().size() + " types");
            writeTypes(code);

            progress.accept("Writing the FROM bridge");
            writeBridge(code, origins);

            progress.accept("Writing " + code.edges().size() + " type relationships");
            int dropped = writeTypeEdges(code);
            recordDroppedEdges(result, dropped);

            progress.accept("Rolling up type dependencies");
            rollUpTypeDependencies();

            progress.accept("Rolling up module code dependencies");
            rollUpModuleUsage();
        }

        long elapsed = System.currentTimeMillis() - started;
        progress.accept("Graph written in " + elapsed + " ms");
        log.info("Graph written in {} ms", elapsed);
    }

    // ------------------------------------------------------------------ build plane

    private void writeProject(ScanResult result) {
        ScanResult.ScanStats stats = result.stats();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", result.projectPath());
        row.put("name", result.projectName());
        row.put("modulesRead", stats.modulesRead());
        row.put("modulesFailed", stats.modulesFailed());
        row.put("dependencyCount", stats.dependencyCount());
        row.put("artifactCount", stats.artifactCount());
        row.put("conflictCount", stats.conflictCount());
        row.put("blockedCount", stats.blockedCount());
        row.put("reviewCount", stats.reviewCount());
        row.put("safeCount", stats.safeCount());
        row.put("alreadyManagedCount", stats.alreadyManagedCount());
        row.put("heldCount", stats.heldCount());
        row.put("proposedEntryCount", stats.proposedEntryCount());
        row.put("scanMillis", stats.durationMillis());
        row.put("hasCodePlane", result.hasCodePlane());
        row.put("inlinedConstants", result.hasCodePlane() ? result.code().stats().inlinedConstants() : 0);
        row.put("typeCount", result.hasCodePlane() ? result.code().stats().typesFound() : 0);
        row.put("edgeCount", result.hasCodePlane() ? result.code().stats().edgeCount() : 0);
        row.put("thirdPartyTypes", result.hasCodePlane() ? result.code().stats().thirdPartyTypes() : 0);

        run("""
            MERGE (p:Project {path: $path})
            SET p += $row, p.scannedAt = datetime()
            """, Map.of("path", result.projectPath(), "row", row));
    }

    private void writeModules(ScanResult result) {
        Set<String> unbuilt = result.hasCodePlane()
            ? new HashSet<>(result.code().modulesWithoutClasses())
            : Set.of();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ModuleRef m : result.modules()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pomPath", m.pomPath());
            row.put("ga", m.key().ga());
            row.put("groupId", m.groupId());
            row.put("artifactId", m.artifactId());
            row.put("version", s(m.version()));
            row.put("packaging", s(m.packaging()));
            row.put("name", s(m.name()));
            row.put("relativePath", s(m.relativePath()));
            row.put("label", m.label());
            row.put("aggregator", m.aggregator());
            row.put("isBom", m.isBom());
            // Tri-state on purpose: false means "we looked and there was no target/classes",
            // null means the code plane never ran. Collapsing them would make an unscanned
            // project look like an unbuilt one.
            row.put("built", result.hasCodePlane() ? !unbuilt.contains(m.relativePath()) : null);
            rows.add(row);
        }

        batch("""
            UNWIND $rows AS r
            MERGE (m:Module {pomPath: r.pomPath})
            SET m += r
            WITH m
            MATCH (p:Project {path: $path})
            MERGE (p)-[:CONTAINS]->(m)
            """, rows, Map.of("path", result.projectPath()));
    }

    /**
     * Every GAV mentioned anywhere, as an {@code :ArtifactVersion} joined to its
     * version-free {@code :Artifact}.
     *
     * <p>Collected from four places rather than just the dependency list, because a parent
     * that is never depended on and a BOM that is only imported are both real nodes that
     * later steps will try to point at. A missing one would make its edge vanish silently.
     */
    private void writeArtifactVersions(ScanResult result) {
        Map<String, Gav> gavs = new LinkedHashMap<>();
        for (DeclaredDependency d : result.dependencies()) {
            gavs.putIfAbsent(d.gav().gav(), d.gav());
        }
        for (ManagedDependency m : result.managed()) {
            gavs.putIfAbsent(m.gav().gav(), m.gav());
        }
        for (ModuleLink l : result.links()) {
            if (l.target() != null && l.target().version() != null) {
                gavs.putIfAbsent(l.target().gav(), l.target());
            }
        }
        for (ArtifactUsage usage : result.artifacts()) {
            for (VersionUse use : usage.versions()) {
                Gav gav = new Gav(usage.key().groupId(), usage.key().artifactId(), use.version());
                gavs.putIfAbsent(gav.gav(), gav);
            }
        }

        List<Map<String, Object>> rows = gavs.values().stream()
            .map(g -> Map.<String, Object>of(
                "gav", g.gav(), "ga", g.key().ga(),
                "groupId", g.groupId(), "artifactId", g.artifactId(),
                "version", s(g.version())))
            .toList();

        batch("""
            UNWIND $rows AS r
            MERGE (v:ArtifactVersion {gav: r.gav})
            SET v.ga = r.ga, v.groupId = r.groupId, v.artifactId = r.artifactId, v.version = r.version
            MERGE (a:Artifact {ga: r.ga})
            ON CREATE SET a.groupId = r.groupId, a.artifactId = r.artifactId
            MERGE (v)-[:VERSION_OF]->(a)
            """, rows, Map.of());
    }

    /** The classification - severity, hold, who manages it - onto the artifact nodes. */
    private void writeArtifacts(ScanResult result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ArtifactUsage usage : result.artifacts()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ga", usage.key().ga());
            row.put("groupId", usage.key().groupId());
            row.put("artifactId", usage.key().artifactId());
            row.put("severity", usage.severity().name());
            row.put("severityLabel", usage.severity().displayName());
            row.put("conflict", usage.isConflict());
            row.put("versionCount", usage.versions().size());
            row.put("moduleCount", usage.moduleCount());
            row.put("highestVersion", s(usage.highestVersion()));
            row.put("managedBy", s(usage.managedBy()));
            row.put("held", usage.held());
            row.put("holdVersion", usage.held() ? s(usage.hold().version()) : "");
            row.put("holdReason", usage.held() ? s(usage.hold().reason()) : "");
            row.put("holdUntil", usage.held() ? s(usage.hold().until()) : "");
            row.put("versions", usage.versions().stream().map(VersionUse::version).toList());
            rows.add(row);
        }

        batch("""
            UNWIND $rows AS r
            MERGE (a:Artifact {ga: r.ga})
            SET a += r
            """, rows, Map.of());
    }

    private void writeDeclaredDependencies(ScanResult result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DeclaredDependency d : result.dependencies()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pomPath", d.module().pomPath());
            row.put("gav", d.gav().gav());
            row.put("scope", s(d.scope()).isEmpty() ? "compile" : d.scope());
            row.put("type", s(d.type()).isEmpty() ? "jar" : d.type());
            row.put("classifier", s(d.classifier()));
            row.put("optional", d.optional());
            row.put("rawVersion", s(d.rawVersion()));
            row.put("origin", d.origin().name());
            row.put("originLabel", d.origin().displayName());
            row.put("originModelId", s(d.originModelId()));
            row.put("viaProperty", d.viaProperty());
            row.put("managed", d.origin().isManaged());
            row.put("testOnly", d.isTestOnly());
            rows.add(row);
        }

        // scope/type/classifier are in the MERGE key: one module legitimately declares the
        // same artifact twice, most often a jar plus its test-jar.
        batch("""
            UNWIND $rows AS r
            MATCH (m:Module {pomPath: r.pomPath})
            MATCH (v:ArtifactVersion {gav: r.gav})
            MERGE (m)-[d:DECLARES_DEP {scope: r.scope, type: r.type, classifier: r.classifier}]->(v)
            SET d.optional = r.optional, d.rawVersion = r.rawVersion, d.origin = r.origin,
                d.originLabel = r.originLabel, d.originModelId = r.originModelId,
                d.viaProperty = r.viaProperty, d.managed = r.managed, d.testOnly = r.testOnly
            """, rows, Map.of());
    }

    private void writeManagedDependencies(ScanResult result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ManagedDependency m : result.managed()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pomPath", m.module().pomPath());
            row.put("gav", m.gav().gav());
            row.put("type", s(m.type()).isEmpty() ? "jar" : m.type());
            row.put("classifier", s(m.classifier()));
            row.put("scope", s(m.scope()));
            row.put("rawVersion", s(m.rawVersion()));
            row.put("bomImport", m.bomImport());
            rows.add(row);
        }

        batch("""
            UNWIND $rows AS r
            MATCH (m:Module {pomPath: r.pomPath})
            MATCH (v:ArtifactVersion {gav: r.gav})
            MERGE (m)-[x:MANAGES {type: r.type, classifier: r.classifier}]->(v)
            SET x.rawVersion = r.rawVersion, x.bomImport = r.bomImport, x.scope = r.scope
            """, rows, Map.of());
    }

    /**
     * The four pom relationships.
     *
     * <p>One query per relation because Cypher will not take a relationship type as a
     * parameter, and split again by whether the far end is inside the scan. That second
     * split is the interesting one: most parents point outside - every repository in this
     * estate inherits {@code aero-parent}, which has no pom on disk - so those edges land
     * on an {@code :ArtifactVersion} instead of a {@code :Module}.
     */
    private void writeModuleLinks(ScanResult result) {
        for (com.boeing.depgraph.model.ModuleRelation relation
                : com.boeing.depgraph.model.ModuleRelation.values()) {

            List<Map<String, Object>> internal = new ArrayList<>();
            List<Map<String, Object>> external = new ArrayList<>();
            for (ModuleLink link : result.links()) {
                if (link.relation() != relation) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fromPom", link.from().pomPath());
                row.put("gav", link.target() == null ? "" : link.target().gav());
                row.put("ga", link.target() == null ? "" : link.target().key().ga());
                if (link.inProject()) {
                    row.put("targetPom", link.targetPomPath());
                    internal.add(row);
                } else {
                    external.add(row);
                }
            }

            batch("""
                UNWIND $rows AS r
                MATCH (m:Module {pomPath: r.fromPom})
                MATCH (t:Module {pomPath: r.targetPom})
                MERGE (m)-[l:%s]->(t)
                SET l.inProject = true, l.gav = r.gav
                """.formatted(relation.name()), internal, Map.of());

            batch("""
                UNWIND $rows AS r
                MATCH (m:Module {pomPath: r.fromPom})
                MATCH (v:ArtifactVersion {gav: r.gav})
                MERGE (m)-[l:%s]->(v)
                SET l.inProject = false, l.gav = r.gav
                """.formatted(relation.name()), external, Map.of());
        }
    }

    private void writeBomProposal(ScanResult result) {
        BomProposal proposal = result.proposal();
        if (proposal == null) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BomProposal.Entry entry : proposal.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ga", entry.key().ga());
            row.put("version", s(entry.version()));
            row.put("severity", entry.severity().name());
            row.put("needsReview", entry.needsReview());
            row.put("rationale", s(entry.rationale()));
            row.put("replaces", entry.replaces());
            row.put("modules", entry.modules());
            rows.add(row);
        }

        batch("""
            UNWIND $rows AS r
            MERGE (e:BomEntry {ga: r.ga})
            SET e += r
            WITH e, r
            MATCH (a:Artifact {ga: r.ga})
            MERGE (e)-[:PROPOSED_FOR]->(a)
            """, rows, Map.of());
    }

    /**
     * Poms that would not build a model.
     *
     * <p>In the graph rather than a log line because a conflict table built from a scan
     * that silently dropped nine poms is confidently wrong, and the only defence is for
     * the omission to be as queryable as the findings.
     */
    private void writeProblems(ScanResult result) {
        List<Map<String, Object>> rows = result.problems().stream()
            .map(p -> Map.<String, Object>of(
                "pomPath", s(p.pomPath()),
                "relativePath", s(p.relativePath()),
                "messages", p.messages()))
            .toList();

        batch("""
            UNWIND $rows AS r
            MERGE (x:ScanProblem {pomPath: r.pomPath})
            SET x.relativePath = r.relativePath, x.messages = r.messages
            WITH x
            MATCH (p:Project {path: $path})
            MERGE (p)-[:HAD_PROBLEM]->(x)
            """, rows, Map.of("path", result.projectPath()));
    }

    // ------------------------------------------------------------------ code plane

    private void writeTypes(CodeScanResult code) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TypeRef t : code.types()) {
            if (t.thirdParty() && !properties.includeThirdPartyTypes()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fqn", t.fqn());
            row.put("simpleName", s(t.simpleName()));
            row.put("packageName", s(t.packageName()));
            row.put("kind", t.kind().name());
            row.put("isPublic", t.isPublic());
            row.put("isAbstract", t.isAbstract());
            row.put("thirdParty", t.thirdParty());
            row.put("nested", t.isNested());
            row.put("sourceFile", s(t.sourceFile()));
            row.put("originPath", s(t.originPath()));
            rows.add(row);
        }

        batch("""
            UNWIND $rows AS r
            MERGE (t:Type {fqn: r.fqn})
            SET t += r
            """, rows, Map.of());

        if (properties.includeMembers()) {
            writeMembers(code);
        }
    }

    /**
     * Off by default.
     *
     * <p>300,000 methods and 100,000 fields is an order of magnitude more nodes than the
     * rest of the graph put together, and no canned query needs them: every edge the
     * scanner records is already type-to-type, carrying the member it came through as a
     * property. Worth having when a question is genuinely about a method, and worth not
     * paying for until then.
     */
    private void writeMembers(CodeScanResult code) {
        List<Map<String, Object>> methods = code.methods().stream()
            .map(m -> Map.<String, Object>of(
                "sig", m.signature(), "ownerFqn", m.ownerFqn(), "name", m.name(),
                "descriptor", s(m.descriptor()), "isPublic", m.isPublic(),
                "isStatic", m.isStatic(), "isAbstract", m.isAbstract(),
                "constructor", m.isConstructor()))
            .toList();
        batch("""
            UNWIND $rows AS r
            MERGE (x:Method {sig: r.sig})
            SET x += r
            WITH x, r
            MATCH (t:Type {fqn: r.ownerFqn})
            MERGE (t)-[:DECLARES]->(x)
            """, methods, Map.of());

        List<Map<String, Object>> fields = code.fields().stream()
            .map(f -> Map.<String, Object>of(
                "key", f.key(), "ownerFqn", f.ownerFqn(), "name", f.name(),
                "typeFqn", s(f.typeFqn()), "isPublic", f.isPublic(),
                "isStatic", f.isStatic(), "constant", f.constant()))
            .toList();
        batch("""
            UNWIND $rows AS r
            MERGE (x:Field {key: r.key})
            SET x += r
            WITH x, r
            MATCH (t:Type {fqn: r.ownerFqn})
            MERGE (t)-[:DECLARES]->(x)
            """, fields, Map.of());
    }

    /** The bridge: every type to the module or artifact version that supplied it. */
    private void writeBridge(CodeScanResult code, OriginIndex origins) {
        List<Map<String, Object>> toModule = new ArrayList<>();
        List<Map<String, Object>> toArtifact = new ArrayList<>();
        int unattributed = 0;

        for (TypeRef t : code.types()) {
            if (t.thirdParty() && !properties.includeThirdPartyTypes()) {
                continue;
            }
            String pom = origins.moduleFor(t.originPath());
            if (pom != null) {
                toModule.add(Map.of("fqn", t.fqn(), "pomPath", pom));
                continue;
            }
            Gav gav = origins.artifactFor(t.originPath());
            if (gav != null) {
                toArtifact.add(Map.of("fqn", t.fqn(), "gav", gav.gav()));
            } else {
                unattributed++;
            }
        }

        batch("""
            UNWIND $rows AS r
            MATCH (t:Type {fqn: r.fqn})
            MATCH (m:Module {pomPath: r.pomPath})
            MERGE (t)-[:FROM]->(m)
            """, toModule, Map.of());

        batch("""
            UNWIND $rows AS r
            MATCH (t:Type {fqn: r.fqn})
            MATCH (v:ArtifactVersion {gav: r.gav})
            MERGE (t)-[:FROM]->(v)
            """, toArtifact, Map.of());

        log.info("Bridge: {} types to modules, {} to artifact versions, {} unattributed",
            toModule.size(), toArtifact.size(), unattributed);
        if (unattributed > 0) {
            // Not an error. A jar pulled in transitively is on the scan's classpath without
            // being any module's declared dependency, so nothing names it.
            log.debug("{} types came from a path matching no module and no declared jar",
                unattributed);
        }
    }

    /**
     * @return edges whose endpoints are not both in the graph, and were therefore not written
     *
     * <p>Counted rather than ignored, and this number matters more than it looks. Only
     * directly declared jars are read, so a class reached through a transitive artifact is
     * never a node - {@code org.apache.pulsar.client.api.Producer} lives in
     * {@code pulsar-client-api}, which nothing declares directly, so every edge pointing at
     * it lands nowhere. Written as a {@code MATCH} that simply fails, that is silent data
     * loss, and the query it corrupts is "declared but never used": the dependency looks
     * unused precisely because the evidence of its use was dropped.
     */
    private int writeTypeEdges(CodeScanResult code) {
        Set<String> present = new HashSet<>();
        for (TypeRef t : code.types()) {
            if (t.thirdParty() && !properties.includeThirdPartyTypes()) {
                continue;
            }
            present.add(t.fqn());
        }

        int dropped = 0;
        Map<String, List<Map<String, Object>>> byRelation = new LinkedHashMap<>();
        for (TypeEdge e : code.edges()) {
            if (!present.contains(e.fromFqn()) || !present.contains(e.toFqn())) {
                dropped++;
                continue;
            }
            byRelation.computeIfAbsent(e.relation().name(), k -> new ArrayList<>())
                .add(Map.of(
                    "from", e.fromFqn(), "to", e.toFqn(),
                    "count", e.count(), "weight", e.weight(),
                    "viaMember", s(e.viaMember())));
        }

        byRelation.forEach((relation, rows) -> batch("""
            UNWIND $rows AS r
            MATCH (a:Type {fqn: r.from})
            MATCH (b:Type {fqn: r.to})
            MERGE (a)-[e:%s]->(b)
            SET e.count = r.count, e.weight = r.weight, e.viaMember = r.viaMember
            """.formatted(relation), rows, Map.of()));

        if (dropped > 0) {
            log.warn("{} type relationships point at a class that was never read - almost "
                + "always a transitive artifact nobody declares directly", dropped);
        }
        return dropped;
    }

    /**
     * Collapses seventeen relationship types into one weighted {@code DEPENDS_ON}.
     *
     * <p>Computed by the database from what was just written, not sent from here. Every
     * question about shape - fan-in, chokepoints, cycles, blast radius - wants "does A
     * depend on B, and how hard would it be to separate them", and answering that over
     * seventeen relationship types means naming all seventeen in every query and summing
     * the weights each time.
     */
    private void rollUpTypeDependencies() {
        try (Session session = driver.session()) {
            session.run("""
                MATCH (a:Type)-[r]->(b:Type)
                WHERE type(r) <> 'DEPENDS_ON' AND a <> b
                WITH a, b,
                     sum(coalesce(r.weight, 1)) AS weight,
                     sum(coalesce(r.count, 1)) AS occurrences,
                     collect(DISTINCT type(r)) AS kinds
                CALL {
                    WITH a, b, weight, occurrences, kinds
                    MERGE (a)-[d:DEPENDS_ON]->(b)
                    SET d.weight = weight, d.occurrences = occurrences, d.kinds = kinds
                } IN TRANSACTIONS OF 10000 ROWS
                """).consume();
        }
    }

    /**
     * Module-level code dependency: what a module's classes actually reach for.
     *
     * <p>Deliberately a different relationship from {@code DEPENDS_ON_MODULE}, which is
     * what the poms declare. Keeping them apart is the whole point - the two disagreeing
     * is the finding, and merging them into one edge would erase it.
     */
    private void rollUpModuleUsage() {
        try (Session session = driver.session()) {
            session.run("""
                MATCH (t1:Type)-[:FROM]->(m1:Module)
                MATCH (t1)-[d:DEPENDS_ON]->(t2:Type)-[:FROM]->(x)
                WHERE m1 <> x
                WITH m1, x,
                     sum(d.weight) AS weight,
                     count(DISTINCT t1) AS fromTypes,
                     count(DISTINCT t2) AS toTypes
                CALL {
                    WITH m1, x, weight, fromTypes, toTypes
                    MERGE (m1)-[u:USES_CODE]->(x)
                    SET u.weight = weight, u.fromTypes = fromTypes, u.toTypes = toTypes
                } IN TRANSACTIONS OF 5000 ROWS
                """).consume();
        }
    }

    /**
     * On the Project node so the dashboard can show it.
     *
     * <p>A graph that quietly lost 40,000 edges looks exactly like a codebase that is less
     * coupled than it is, and nothing else in the system would ever say otherwise.
     */
    private void recordDroppedEdges(ScanResult result, int dropped) {
        run("""
            MATCH (p:Project {path: $path})
            SET p.droppedEdges = $dropped
            """, Map.of("path", result.projectPath(), "dropped", dropped));
    }

    // ------------------------------------------------------------------ plumbing

    private void run(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, Values.value(params)).consume());
        }
    }

    /** Splits rows into transactions of {@code batchSize} and runs the same statement over each. */
    private void batch(String cypher, List<Map<String, Object>> rows, Map<String, Object> extra) {
        if (rows.isEmpty()) {
            return;
        }
        int size = properties.batchSize();
        try (Session session = driver.session()) {
            for (int start = 0; start < rows.size(); start += size) {
                List<Map<String, Object>> slice = rows.subList(start, Math.min(start + size, rows.size()));
                Map<String, Object> params = new LinkedHashMap<>(extra);
                params.put("rows", slice);
                session.executeWrite(tx -> tx.run(cypher, Values.value(params)).consume());
            }
        }
    }

    /** Neo4J will not accept null inside a MERGE key, and "absent" is not a distinct answer here. */
    private static String s(String value) {
        return value == null ? "" : value;
    }
}
