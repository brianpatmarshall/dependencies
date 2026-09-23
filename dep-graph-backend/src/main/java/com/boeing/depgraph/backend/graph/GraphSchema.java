package com.boeing.depgraph.backend.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Constraints and indexes, applied before the first write of every scan.
 *
 * <p>Not optional tuning. Every write in {@link GraphPopulator} is a {@code MERGE} on one
 * of these keys, and a {@code MERGE} without a supporting index is a label scan. At
 * 35,000 types and 200,000 edges the difference is not "slower" - it is a scan that
 * finishes in a minute against one that has not finished by the time anyone gives up.
 */
public final class GraphSchema {

    private static final Logger log = LoggerFactory.getLogger(GraphSchema.class);

    private static final List<String> STATEMENTS = List.of(
        "CREATE CONSTRAINT project_path IF NOT EXISTS FOR (p:Project) REQUIRE p.path IS UNIQUE",
        "CREATE CONSTRAINT module_pom IF NOT EXISTS FOR (m:Module) REQUIRE m.pomPath IS UNIQUE",
        "CREATE CONSTRAINT artifact_ga IF NOT EXISTS FOR (a:Artifact) REQUIRE a.ga IS UNIQUE",
        "CREATE CONSTRAINT version_gav IF NOT EXISTS FOR (v:ArtifactVersion) REQUIRE v.gav IS UNIQUE",
        "CREATE CONSTRAINT type_fqn IF NOT EXISTS FOR (t:Type) REQUIRE t.fqn IS UNIQUE",
        "CREATE CONSTRAINT bom_entry_ga IF NOT EXISTS FOR (e:BomEntry) REQUIRE e.ga IS UNIQUE",

        // Supports the package-level questions, which group by this rather than match on it.
        "CREATE INDEX type_package IF NOT EXISTS FOR (t:Type) ON (t.packageName)",
        // Almost every bridge query starts by separating our code from everybody else's.
        "CREATE INDEX type_third_party IF NOT EXISTS FOR (t:Type) ON (t.thirdParty)",
        "CREATE INDEX module_ga IF NOT EXISTS FOR (m:Module) ON (m.ga)",
        "CREATE INDEX version_ga IF NOT EXISTS FOR (v:ArtifactVersion) ON (v.ga)",
        "CREATE INDEX artifact_severity IF NOT EXISTS FOR (a:Artifact) ON (a.severity)"
    );

    private final Driver driver;

    public GraphSchema(Driver driver) {
        this.driver = driver;
    }

    public void apply() {
        try (Session session = driver.session()) {
            for (String statement : STATEMENTS) {
                session.run(statement).consume();
            }
        }
        log.info("Applied {} schema statements", STATEMENTS.size());
    }

    /**
     * Wipes the graph.
     *
     * <p>The tool keeps one current snapshot rather than a history, so a scan replaces
     * everything rather than merging into what was there. Merging would be worse than it
     * sounds: a type deleted between two scans would linger for ever, and "what does
     * nothing depend on" - the whole point of the unused-dependency question - would
     * quietly start returning the wrong answer.
     *
     * <p>Batched through {@code CALL {} IN TRANSACTIONS} because a single transaction
     * deleting a few hundred thousand relationships is how you meet the default heap.
     */
    public void wipe() {
        try (Session session = driver.session()) {
            session.run("MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS")
                .consume();
        }
        log.info("Graph cleared");
    }
}
