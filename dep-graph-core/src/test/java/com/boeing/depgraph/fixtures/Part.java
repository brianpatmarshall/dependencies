package com.boeing.depgraph.fixtures;

/** A record, so {@code TypeKind.RECORD} has something to detect. */
public record Part(String name, int quantity) {
}
