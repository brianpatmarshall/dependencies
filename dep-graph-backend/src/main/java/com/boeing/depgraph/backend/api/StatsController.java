package com.boeing.depgraph.backend.api;

import com.boeing.depgraph.backend.scan.ScanService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** What the dashboard needs, read from the graph rather than from a scan held in memory. */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final Driver driver;
    private final ScanService scans;

    public StatsController(Driver driver, ScanService scans) {
        this.driver = driver;
        this.scans = scans;
    }

    @GetMapping
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try (Session session = driver.session()) {
            // The Project node carries the scan's own counters, so the dashboard survives
            // a backend restart: the numbers live in the graph, not in this process.
            session.executeRead(tx -> {
                var result = tx.run("MATCH (p:Project) RETURN p ORDER BY p.scannedAt DESC LIMIT 1");
                if (result.hasNext()) {
                    stats.putAll(result.next().get("p").asMap());
                }
                return null;
            });
            stats.put("nodes", count(session, "MATCH (n) RETURN count(n) AS c"));
            stats.put("relationships", count(session, "MATCH ()-[r]->() RETURN count(r) AS c"));
            stats.put("bridgeEdges", count(session, "MATCH (:Type)-[r:FROM]->() RETURN count(r) AS c"));
            stats.put("usesCodeEdges", count(session, "MATCH (:Module)-[r:USES_CODE]->() RETURN count(r) AS c"));
        }
        stats.put("lastScan", scans.lastScan());
        return stats;
    }

    /** Whether the graph is reachable and whether anything has been scanned into it. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        try (Session session = driver.session()) {
            long projects = count(session, "MATCH (p:Project) RETURN count(p) AS c");
            health.put("neo4j", "up");
            health.put("scanned", projects > 0);
        } catch (Exception e) {
            health.put("neo4j", "down");
            health.put("error", e.getMessage());
            health.put("scanned", false);
        }
        return health;
    }

    private static long count(Session session, String cypher) {
        return session.executeRead(tx -> tx.run(cypher).single().get("c").asLong());
    }
}
