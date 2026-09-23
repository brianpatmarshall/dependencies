package com.boeing.depgraph.code;

import com.boeing.depgraph.model.FieldRef;
import com.boeing.depgraph.model.MethodRef;
import com.boeing.depgraph.model.TypeEdge;
import com.boeing.depgraph.model.TypeRef;
import com.boeing.depgraph.model.TypeRelation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Collects what the class visitor finds, aggregating edges as they arrive.
 *
 * <p>Aggregation happens here rather than in a post-pass because a single large method
 * can emit thousands of identical {@code CALLS} occurrences, and holding them all to
 * group them later is the difference between a scan that fits in memory and one that
 * does not.
 */
public class TypeSink {

    private final List<TypeRef> types = new ArrayList<>();
    private final List<MethodRef> methods = new ArrayList<>();
    private final List<FieldRef> fields = new ArrayList<>();

    /** Keyed by from|to|relation, so repeated occurrences increment rather than append. */
    private final Map<String, Occurrence> edges = new LinkedHashMap<>();

    /** Decides which target types are worth an edge at all. */
    private final Predicate<String> included;

    public TypeSink(Predicate<String> included) {
        this.included = included;
    }

    private static final class Occurrence {
        private final String viaMember;
        private int count;

        private Occurrence(String viaMember) {
            this.viaMember = viaMember;
            this.count = 1;
        }
    }

    // ------------------------------------------------------------------

    public void addType(TypeRef type) {
        types.add(type);
    }

    public void addMethod(MethodRef method) {
        methods.add(method);
    }

    public void addField(FieldRef field) {
        fields.add(field);
    }

    /**
     * Record one occurrence of one relation.
     *
     * <p>Self-edges are dropped. A class refers to itself constantly — every
     * {@code this}, every internal call, every field of its own type — and none of it is
     * a dependency on anything.
     */
    public void edge(String fromFqn, String toFqn, TypeRelation relation, String viaMember) {
        if (toFqn == null || fromFqn.equals(toFqn) || !included.test(toFqn)) {
            return;
        }
        String key = fromFqn + "|" + toFqn + "|" + relation.name();
        Occurrence existing = edges.get(key);
        if (existing == null) {
            edges.put(key, new Occurrence(viaMember));
        } else {
            existing.count++;
        }
    }

    // ------------------------------------------------------------------

    public List<TypeRef> types() {
        return List.copyOf(types);
    }

    public List<MethodRef> methods() {
        return List.copyOf(methods);
    }

    public List<FieldRef> fields() {
        return List.copyOf(fields);
    }

    public List<TypeEdge> edges() {
        List<TypeEdge> result = new ArrayList<>(edges.size());
        edges.forEach((key, occurrence) -> {
            String[] parts = key.split("\\|", 3);
            result.add(new TypeEdge(parts[0], parts[1],
                TypeRelation.valueOf(parts[2]), occurrence.viaMember, occurrence.count));
        });
        return List.copyOf(result);
    }
}
