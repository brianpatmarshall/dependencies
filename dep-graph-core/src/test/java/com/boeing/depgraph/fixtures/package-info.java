/**
 * Classes that exist to be compiled and then read back as bytecode.
 *
 * <p>Written as real Java rather than checked in as {@code .class} files, so the fixture
 * is compiled by the same build and the same JDK as the code that reads it. A committed
 * binary would freeze one compiler's output and quietly stop testing the thing that
 * actually varies.
 *
 * <p>Between them these classes carry at least one instance of every
 * {@link com.boeing.depgraph.model.TypeRelation}, and one instance of the blind spot:
 * {@link com.boeing.depgraph.fixtures.Limits#MAX_BAYS} is used by
 * {@link com.boeing.depgraph.fixtures.Garage} and leaves no trace in its bytecode.
 */
package com.boeing.depgraph.fixtures;
