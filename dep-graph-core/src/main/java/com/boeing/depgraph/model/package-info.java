/**
 * The vocabulary the analyser reports in.
 *
 * <h2>Two planes, one graph</h2>
 * A dependency question is nearly always one of two quite different questions wearing the
 * same word:
 *
 * <ul>
 *   <li>The <strong>build plane</strong> — what the poms declare. Modules, artifacts,
 *       versions, and the skew between them. Everything in this package except
 *       {@code Type} and its relatives. Read from the effective Maven model, so the
 *       version on a {@link com.boeing.depgraph.model.DeclaredDependency} is the one the
 *       build will really use, not the one the file happens to show.</li>
 *   <li>The <strong>code plane</strong> — what the bytecode actually touches. Types,
 *       methods, fields, and the edges between them.</li>
 * </ul>
 *
 * <p>They are joined by one edge, from a type to the module or artifact version it came
 * from, and that edge is the reason to build this at all. On its own the build plane can
 * say a module depends on Guava; on its own the code plane can say a class calls
 * {@code ImmutableList.of}. Only together can either say that nothing in the module ever
 * touches Guava, or that a class is using a type the module never declared and is
 * getting by on somebody else's transitive.
 *
 * <h2>Why bytecode, not source</h2>
 * The rest of this estate parses {@code .java} with JavaParser, and both discovery
 * scanners hold a settled rule: a detection must be decidable from a single parse, with
 * no type resolution and no second pass. That rule is what keeps a monorepo scan
 * finishable, and it was written about advisory rules over source text.
 *
 * <p>It does not transfer here, because the code plane <em>is</em> type resolution.
 * JavaParser without its symbol solver reads a return type of {@code List<Order>} as
 * exactly that text and cannot say whether {@code Order} is {@code com.acme.billing.Order}
 * or {@code com.acme.catalog.Order} — and an edge to the wrong class is worse than no
 * edge. Turning the symbol solver on buys the answer at the price of the classpath
 * configuration and the quadratic passes the scanners banned.
 *
 * <p>Reading bytecode with ASM sidesteps the argument rather than winning it. javac
 * already did the resolution; the fully qualified name is sitting in the constant pool,
 * generic signatures are preserved in the {@code Signature} attribute, and the
 * "third-party public surface only" rule is an
 * {@code ACC_PUBLIC | ACC_PROTECTED} mask rather than a traversal. It costs one thing,
 * stated plainly: the scan target must already be compiled, and a module without a
 * {@code target/classes} is reported by name rather than silently contributing nothing.
 *
 * <h2>Versions are outcomes, not properties</h2>
 * The single idea worth carrying out of this package is that a dependency's version is
 * not written on the dependency. It is the result of an argument between the module, its
 * parents, and every BOM they import, and
 * {@link com.boeing.depgraph.model.VersionOrigin} records who won. A module that names
 * {@code 2.15.2} and a module that inherits it are not in the same position: unify them
 * and only the first will notice.
 *
 * <p>That distinction is what makes
 * {@link com.boeing.depgraph.model.ConflictSeverity#ALREADY_MANAGED} an exclusion rather
 * than a finding. When an imported BOM already decides an artifact, adding a hand-pinned
 * version that beats it is not a fix — it is the mechanism by which an estate drifts
 * apart one upgrade at a time.
 */
package com.boeing.depgraph.model;
