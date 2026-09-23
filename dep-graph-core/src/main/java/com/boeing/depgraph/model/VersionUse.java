package com.boeing.depgraph.model;

import java.util.List;
import java.util.Set;

/**
 * One version of one artifact, and everyone who ends up on it.
 *
 * @param modules relative paths of the modules using this version, sorted
 * @param origins how those modules came to be on it. A set rather than a single value
 *                because the interesting case is the mixed one: two modules on 2.15.2
 *                where one asked for it and the other inherited it are not in the same
 *                position, and only the first will notice a change
 */
public record VersionUse(
    String version,
    List<String> modules,
    Set<VersionOrigin> origins
) {

    public int moduleCount() {
        return modules.size();
    }

    /** True when no module names this version itself. Nobody would miss it. */
    public boolean whollyManaged() {
        return !origins.contains(VersionOrigin.LOCAL_DECLARATION);
    }
}
