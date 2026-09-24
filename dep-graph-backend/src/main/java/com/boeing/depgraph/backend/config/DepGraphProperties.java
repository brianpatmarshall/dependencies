package com.boeing.depgraph.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything the backend will not decide for itself.
 *
 * @param allowedRoots  a security boundary, not a convenience setting. A scan reads every
 *                      pom and class file under the path it is given, so the path is
 *                      checked against these before anything is opened. Narrowed to the
 *                      read-only mount in compose
 * @param localRepository where dependency jars are read from; the read-only {@code /m2}
 *                      mount in compose, {@code ~/.m2/repository} on a workstation
 * @param includeThirdPartyTypes whether library types are written to the graph at all.
 *                      On this estate they are 97% of it - 34,926 types of 35,705 - and
 *                      turning them off makes the graph small and fast at the cost of
 *                      every question that spans the boundary, which is most of the
 *                      interesting ones
 * @param includeLibraryEdges whether relationships between two library types are written.
 *                      Off by default, and this is the single biggest lever on graph size:
 *                      on this estate it is the difference between 528,000 relationships
 *                      and about 20,000. Library-to-library coupling is netty's business,
 *                      not ours, and no canned query reads it - every question this tool
 *                      exists to answer has our own code at one end. Library types are
 *                      still written as nodes either way, so nothing an edge needs is
 *                      missing and "what else does this jar contain" still has an answer
 * @param includeMembers whether {@code :Method} and {@code :Field} nodes are written.
 *                      Off by default: that is 300,000 more nodes, and no canned query
 *                      needs them because every edge the tool records is already
 *                      type-to-type with the member it came from on the relationship
 * @param batchSize     rows per UNWIND. 10,000 is comfortably inside the default heap
 *                      and well past the point where per-statement overhead matters
 */
@ConfigurationProperties(prefix = "depgraph")
public record DepGraphProperties(
    List<Path> allowedRoots,
    Path localRepository,
    boolean offline,
    boolean includeThirdPartyTypes,
    boolean includeLibraryEdges,
    boolean includeMembers,
    int batchSize,
    int queryTimeoutSeconds,
    int maxQueryRows
) {

    public DepGraphProperties {
        allowedRoots = allowedRoots == null || allowedRoots.isEmpty()
            ? List.of(Path.of("/projects"), Path.of("/src"))
            : allowedRoots;
        localRepository = localRepository == null
            ? Path.of(System.getProperty("user.home"), ".m2", "repository")
            : localRepository;
        batchSize = batchSize <= 0 ? 10_000 : batchSize;
        queryTimeoutSeconds = queryTimeoutSeconds <= 0 ? 60 : queryTimeoutSeconds;
        maxQueryRows = maxQueryRows <= 0 ? 1_000 : maxQueryRows;
    }
}
