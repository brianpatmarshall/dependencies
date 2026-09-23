package com.boeing.depgraph.backend.query;

import com.boeing.depgraph.backend.config.DepGraphProperties;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Runs a catalogue entry, or a read-only query somebody typed. */
@Service
public class QueryService {

    /**
     * A courtesy, not the security boundary.
     *
     * <p>The real enforcement is {@code executeRead}: Neo4J refuses a write inside a read
     * transaction whatever this pattern thinks. This exists so that someone who pastes an
     * UPDATE gets a sentence explaining the endpoint is read-only, rather than a driver
     * stack trace.
     */
    private static final Pattern WRITES = Pattern.compile(
        "(?i)\\b(CREATE|MERGE|DELETE|SET|REMOVE|DROP|DETACH|LOAD\\s+CSV|CALL\\s*\\{[^}]*}\\s*IN\\s+TRANSACTIONS)\\b");

    private final Driver driver;
    private final DepGraphProperties properties;

    public QueryService(Driver driver, DepGraphProperties properties) {
        this.driver = driver;
        this.properties = properties;
    }

    public QueryResult runCanned(String id, Map<String, Object> params) {
        CannedQuery query = CannedQueries.byId(id)
            .orElseThrow(() -> new IllegalArgumentException("No such query: " + id));

        Map<String, Object> effective = new LinkedHashMap<>();
        for (CannedQuery.Param param : query.params()) {
            Object supplied = params == null ? null : params.get(param.name());
            String value = supplied == null || supplied.toString().isBlank()
                ? param.defaultValue()
                : supplied.toString();
            effective.put(param.name(), value);
        }

        Execution execution = execute(query.cypher(), effective);
        return new QueryResult(query.id(), query.title(), query.question(), query.cypher(),
            execution.columns(), execution.rows(), execution.rows().size(),
            execution.truncated(), execution.millis(), query.caveat());
    }

    public QueryResult runAdHoc(String cypher, Map<String, Object> params) {
        if (cypher == null || cypher.isBlank()) {
            throw new IllegalArgumentException("No query given");
        }
        if (WRITES.matcher(cypher).find()) {
            throw new IllegalArgumentException(
                "This endpoint is read-only. Remove the write clause, or use the Neo4J "
                    + "browser if you mean it.");
        }
        Execution execution = execute(cypher, params == null ? Map.of() : params);
        return new QueryResult(null, "Ad-hoc query", null, cypher,
            execution.columns(), execution.rows(), execution.rows().size(),
            execution.truncated(), execution.millis(), null);
    }

    // ------------------------------------------------------------------

    private record Execution(List<String> columns, List<Map<String, Object>> rows,
                             boolean truncated, long millis) {}

    private Execution execute(String cypher, Map<String, Object> params) {
        long started = System.currentTimeMillis();
        int cap = properties.maxQueryRows();

        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result result = tx.run(cypher, params);
                List<String> columns = result.keys();
                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;

                while (result.hasNext()) {
                    if (rows.size() >= cap) {
                        truncated = true;
                        break;
                    }
                    Record record = result.next();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String column : columns) {
                        row.put(column, convert(record.get(column)));
                    }
                    rows.add(row);
                }
                return new Execution(columns, rows, truncated,
                    System.currentTimeMillis() - started);
            }, org.neo4j.driver.TransactionConfig.builder()
                .withTimeout(Duration.ofSeconds(properties.queryTimeoutSeconds()))
                .build());
        } catch (ClientException e) {
            // Cypher that does not compile is a user mistake, not a server fault. The
            // frontend depends on getting a 400 with a readable message here.
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Driver values to something Jackson can serialise.
     *
     * <p>Nodes and relationships are flattened to their properties plus the labels, which
     * is what a table wants. Anyone who needs the real graph object has the Neo4J browser.
     */
    private static Object convert(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Object object = value.asObject();
        if (object instanceof Node node) {
            Map<String, Object> map = new LinkedHashMap<>(node.asMap());
            map.put("_labels", node.labels());
            return map;
        }
        if (object instanceof Relationship relationship) {
            Map<String, Object> map = new LinkedHashMap<>(relationship.asMap());
            map.put("_type", relationship.type());
            return map;
        }
        if (object instanceof List<?> list) {
            return list.stream().map(item -> item instanceof Value v ? convert(v) : item).toList();
        }
        return object;
    }
}
