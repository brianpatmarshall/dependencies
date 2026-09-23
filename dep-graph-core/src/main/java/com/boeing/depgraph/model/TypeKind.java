package com.boeing.depgraph.model;

/**
 * What sort of type a class file declares.
 *
 * <p>Read from access flags and superclass rather than from source, which is why
 * {@link #RECORD} is detectable at all: a record compiles to a final class extending
 * {@code java.lang.Record}, and nothing else does.
 */
public enum TypeKind {

    CLASS("class"),
    INTERFACE("interface"),
    ENUM("enum"),
    RECORD("record"),

    /** An annotation type — an interface with {@code ACC_ANNOTATION} set. */
    ANNOTATION("@interface");

    private final String displayName;

    TypeKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
