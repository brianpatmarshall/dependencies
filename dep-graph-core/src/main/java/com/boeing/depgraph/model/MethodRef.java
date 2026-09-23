package com.boeing.depgraph.model;

/**
 * One method or constructor — the {@code :Method} node.
 *
 * <p>A constructor is a method named {@code <init>}; bytecode draws no other distinction
 * and neither does this. {@link #isConstructor()} is there for display, not for
 * modelling: the coupling a parameter creates is identical either way.
 *
 * @param descriptor the JVM descriptor, e.g. {@code (Ljava/lang/String;)I}. Part of the
 *                   key because overloads are different methods and a call site names
 *                   exactly one of them
 */
public record MethodRef(
    String ownerFqn,
    String name,
    String descriptor,
    boolean isPublic,
    boolean isStatic,
    boolean isAbstract
) {

    public boolean isConstructor() {
        return "<init>".equals(name);
    }

    /** {@code com.acme.Order#place(Ljava/lang/String;)V} — the MERGE key. */
    public String signature() {
        return ownerFqn + "#" + name + descriptor;
    }
}
