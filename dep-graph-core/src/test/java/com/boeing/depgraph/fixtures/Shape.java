package com.boeing.depgraph.fixtures;

/** Sealed, so {@code PERMITS} has something to find. */
public sealed interface Shape permits Circle, Square {
}
