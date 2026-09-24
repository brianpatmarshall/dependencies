package com.boeing.depgraph.fixtures;

/**
 * Arrays of a project type, in the three shapes where ASM reports a descriptor rather than
 * an internal name: a multi-dimensional creation, a method call on an array, and a cast to
 * an array type. Each one used to produce an edge to a class called {@code [Lcom...Part;}.
 */
public class PartsBin {

    private final Part[][] grid = new Part[2][2];

    public Part[] copy(Part[] parts) {
        return (Part[]) parts.clone();
    }

    public Part[] unpack(Object boxed) {
        return (Part[]) boxed;
    }

    public Part first() {
        return grid[0][0];
    }
}
