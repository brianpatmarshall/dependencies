package com.boeing.depgraph.backend.query;

import java.util.List;

/**
 * One question worth asking, with the Cypher that answers it.
 *
 * <p>The catalogue is the product, not a convenience wrapper. Someone who knows both this
 * schema and Cypher can write any of these by hand; nobody else can, and "here is a graph,
 * good luck" is how a graph database becomes a write-only store. Every entry therefore
 * carries the question in plain words, and {@code caveat} carries the reason an answer
 * might be wrong - because a confident wrong answer is worse than no answer.
 *
 * @param caveat what this cannot see. Shown with the results, not hidden in documentation
 */
public record CannedQuery(
    String id,
    Category category,
    String title,
    String question,
    String cypher,
    List<Param> params,
    String caveat,
    boolean needsCodePlane
) {

    public record Param(String name, String label, String defaultValue) {}

    public enum Category {
        VERSIONS("Versions and the BOM"),
        TOPOLOGY("Module topology"),
        BRIDGE("Where the poms and the bytecode disagree"),
        CODE("Code structure"),
        HEALTH("Scan health");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }
}
