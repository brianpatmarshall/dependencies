package com.boeing.depgraph.backend.query;

import java.util.List;
import java.util.Map;

/**
 * @param truncated whether {@code maxQueryRows} cut the result short. Reported rather than
 *                  silently applied: a table that stops at a round number without saying
 *                  so reads as a complete answer
 */
public record QueryResult(
    String id,
    String title,
    String question,
    String cypher,
    List<String> columns,
    List<Map<String, Object>> rows,
    int rowCount,
    boolean truncated,
    long millis,
    String caveat
) {}
