package com.boeing.depgraph.fixtures;

/**
 * The blind spot, in one class.
 *
 * <p>{@link #MAX_BAYS} is {@code static final} with a compile-time constant value, so
 * javac copies the literal 4 into every caller and writes no reference to this class at
 * all. {@code Garage} uses it and has no bytecode dependency on {@code Limits}.
 *
 * <p>{@link #DEFAULT_BAY} is also {@code static final} but is not a compile-time
 * constant - a method call cannot be folded - so it is read at runtime and the reference
 * survives.
 */
public final class Limits {

    public static final int MAX_BAYS = 4;

    public static final String DEFAULT_BAY = String.valueOf(1);

    private Limits() {
    }
}
