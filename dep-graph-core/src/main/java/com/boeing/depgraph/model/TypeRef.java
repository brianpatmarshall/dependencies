package com.boeing.depgraph.model;

/**
 * One type, as its class file declares it — the {@code :Type} node.
 *
 * @param fqn        fully qualified name, and the MERGE key. Nested types keep the
 *                   {@code Outer$Inner} form the class file uses, because that is what
 *                   every other reference in the bytecode will say
 * @param sourceFile from the {@code SourceFile} attribute, or null when compiled without
 *                   it. The only breadcrumb back to a line of Java
 * @param originPath the {@code target/classes} directory or jar this was read from — what
 *                   the {@code FROM} bridge edge is built out of
 * @param thirdParty true when read from a jar rather than from the project's own output.
 *                   Decides whether method bodies were scanned at all
 */
public record TypeRef(
    String fqn,
    String simpleName,
    String packageName,
    TypeKind kind,
    boolean isPublic,
    boolean isAbstract,
    String sourceFile,
    String originPath,
    boolean thirdParty
) {

    /** True for {@code Outer$Inner}. */
    public boolean isNested() {
        return fqn.indexOf('$') >= 0;
    }
}
