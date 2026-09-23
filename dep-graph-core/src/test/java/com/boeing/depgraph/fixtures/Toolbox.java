package com.boeing.depgraph.fixtures;

/** Has a public field, so field reads and writes are visible from another class. */
public class Toolbox {

    public int wrenchCount;

    public void restock() {
        wrenchCount = 10;
    }
}
