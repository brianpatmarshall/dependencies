package com.boeing.depgraph.model;

/**
 * One field — the {@code :Field} node.
 *
 * <p>Worth its own node rather than a property on the type because reads and writes are
 * different edges to the same target, and a graph that collapsed them could not answer
 * "who mutates this" separately from "who looks at it".
 *
 * @param constant true for a {@code static final} with a {@code ConstantValue} attribute.
 *                 Flagged because such a field is <em>inlined into every caller</em> and
 *                 leaves no reference behind — the blind spot that makes an "unused
 *                 dependency" finding a question rather than a verdict
 */
public record FieldRef(
    String ownerFqn,
    String name,
    String typeFqn,
    boolean isPublic,
    boolean isStatic,
    boolean constant
) {

    /** {@code com.acme.Order#status} — the MERGE key. */
    public String key() {
        return ownerFqn + "#" + name;
    }
}
