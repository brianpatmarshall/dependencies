package com.boeing.depgraph.maven;

import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.ManagedDependency;
import com.boeing.depgraph.model.ModuleRef;

import java.util.List;

/**
 * One pom, read.
 *
 * @param problems anything the model builder complained about. A module that failed to
 *                 build a model still appears in the scan, with an empty dependency list
 *                 and the reason attached: dropping it silently would understate a
 *                 conflict, and a project where the tool could not read four poms is a
 *                 fact the report has to carry rather than round away
 */
public record ModuleModel(
    ModuleRef module,
    List<DeclaredDependency> dependencies,

    /**
     * This pom's own {@code <dependencyManagement>}, including its BOM imports.
     *
     * <p>Separate from {@link #dependencies()} because managing an artifact is not
     * depending on it: a BOM has hundreds of these and needs none of them.
     */
    List<ManagedDependency> managed,

    /** Relative directory names from {@code <modules>}, exactly as the pom writes them. */
    List<String> aggregatedModules,

    List<String> problems
) {

    public boolean failed() {
        return module == null;
    }
}
