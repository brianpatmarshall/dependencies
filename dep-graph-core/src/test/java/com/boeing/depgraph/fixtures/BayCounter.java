package com.boeing.depgraph.fixtures;

/**
 * Uses nothing from {@link Limits} except the inlined constant, and therefore has no
 * bytecode dependency on it whatsoever.
 *
 * <p>Deliberately separate from {@code Garage}, which also reads {@code DEFAULT_BAY} and
 * so does carry a real reference. Isolating the two is the only way to assert that the
 * absence is constant folding rather than a gap in the scanner.
 */
public class BayCounter {

    public int capacity() {
        return Limits.MAX_BAYS * 2;
    }
}
