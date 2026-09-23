package com.boeing.depgraph.code;

import com.boeing.depgraph.model.FieldRef;
import com.boeing.depgraph.model.MethodRef;
import com.boeing.depgraph.model.TypeEdge;
import com.boeing.depgraph.model.TypeRef;

import java.util.List;

/**
 * Everything one bytecode scan found.
 *
 * @param modulesWithoutClasses modules with no {@code target/classes}, by relative path.
 *                              The one cost of reading bytecode instead of source, and it
 *                              is reported by name rather than absorbed: a module that was
 *                              never built contributes nothing, and a report that does not
 *                              say so is describing a smaller codebase than the real one
 */
public record CodeScanResult(
    List<TypeRef> types,
    List<MethodRef> methods,
    List<FieldRef> fields,
    List<TypeEdge> edges,
    List<String> modulesWithoutClasses,
    CodeStats stats
) {

    public record CodeStats(
        int classFilesRead,
        int typesFound,
        int methodsFound,
        int fieldsFound,

        /** Distinct from-to-relation triples, after aggregation. */
        int edgeCount,

        /** Total occurrences behind those edges. */
        int edgeOccurrences,

        int thirdPartyTypes,
        int jarsRead,
        int modulesScanned,
        int modulesMissingClasses,

        /**
         * Fields javac will inline into their callers, leaving no reference behind.
         *
         * <p>Worth a number on the dashboard: it is the size of the code plane's largest
         * blind spot, and the reason an unused-dependency finding is a question.
         */
        int inlinedConstants,

        long durationMillis
    ) {}
}
