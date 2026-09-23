package com.boeing.depgraph.model;

/**
 * One kind of dependency from one type to another, with how many times it occurs.
 *
 * <p>Aggregated, not per-occurrence. A class that calls the same helper four hundred
 * times has one dependency on it; four hundred parallel relationships would make every
 * traversal four hundred times more expensive and say nothing extra. The true count
 * survives on {@link #count()}.
 *
 * @param viaMember the method or field that created the edge, or null for a relation the
 *                  class declaration itself carries ({@code EXTENDS}, {@code IMPLEMENTS}).
 *                  Only the first is kept when an edge is aggregated — it is there to
 *                  point a human at somewhere to look, not to enumerate
 */
public record TypeEdge(
    String fromFqn,
    String toFqn,
    TypeRelation relation,
    String viaMember,
    int count
) {

    /** Contribution to the materialised {@code DEPENDS_ON} rollup. */
    public int weight() {
        return relation.weight() * count;
    }

    /** Key for aggregating occurrences. */
    public String groupKey() {
        return fromFqn + "|" + toFqn + "|" + relation.name();
    }
}
