package com.boeing.depgraph.model;

/**
 * Every way one type can depend on another, as bytecode records it.
 *
 * <h2>Three tiers, and they are not equally serious</h2>
 * The relations are grouped by {@link Tier} because "B depends on A" covers situations a
 * mile apart. If B extends A, B cannot exist without A and no refactor will separate them
 * quietly. If B's method happens to call one static helper on A, deleting that call is an
 * afternoon. A graph that scores those the same cannot rank anything, so each relation
 * carries a {@link #weight()} and the materialised {@code DEPENDS_ON} rollup sums them.
 *
 * <h2>What bytecode cannot see</h2>
 * Stated here rather than discovered later, because each of these is a silent hole in the
 * code plane and one of them matters a great deal in this estate:
 *
 * <ul>
 *   <li><strong>Inlined compile-time constants.</strong> {@code static final int MAX = 10}
 *       is copied into the caller by javac and leaves no reference to the declaring class
 *       at all. A module whose only use of another is a constant looks unused — which is
 *       precisely the inverse of what the {@code constants} repository exists to catalogue,
 *       and the one question that genuinely needs a source parse to answer.</li>
 *   <li><strong>{@code SOURCE}-retention annotations.</strong> Lombok's {@code @Data} and
 *       {@code @Slf4j} are gone before the class file exists. {@code CLASS} and
 *       {@code RUNTIME} retention survive and become {@link #ANNOTATED_BY}.</li>
 *   <li><strong>Generics outside a {@code Signature} attribute.</strong> Descriptors are
 *       erased, so {@code List<Order>} is only {@code List} unless the compiler wrote a
 *       signature — which it does for fields, methods and class declarations, and does not
 *       for local variables.</li>
 *   <li><strong>Reflection and service loading.</strong> A class named in a string is a
 *       string.</li>
 * </ul>
 *
 * <p>None of these is a reason to parse source instead. They are the price of javac having
 * already resolved every other name in the file, and the price is worth paying — but a
 * tool that reports "unused dependency" has to know which of its silences are real.
 */
public enum TypeRelation {

    // ---------------------------------------------------------------- structure

    /** {@code class B extends A} — B is a child of A. */
    EXTENDS(Tier.STRUCTURE, 10, "extends"),

    /** {@code class B implements A}, or {@code interface B extends A}. */
    IMPLEMENTS(Tier.STRUCTURE, 9, "implements"),

    /**
     * B is declared inside A.
     *
     * <p>Worth an edge of its own rather than being inferred from the {@code Outer$Inner}
     * name: a non-static inner class holds a synthetic reference to its outer instance,
     * so the coupling is real and not merely lexical.
     */
    NESTED_IN(Tier.STRUCTURE, 8, "nested in"),

    /** {@code sealed ... permits B} — A names B as a permitted subtype. */
    PERMITS(Tier.STRUCTURE, 8, "permits"),

    /** {@code @A} on B's declaration, or on one of its members. */
    ANNOTATED_BY(Tier.STRUCTURE, 4, "annotated by"),

    /** {@code class B<T extends A>} — A bounds one of B's type parameters. */
    TYPE_BOUND(Tier.STRUCTURE, 6, "bounded by"),

    // ---------------------------------------------------------------- signature

    /** B declares a field of type A. */
    FIELD_TYPE(Tier.SIGNATURE, 7, "has field of type"),

    /**
     * A method or constructor of B takes an A.
     *
     * <p>Constructors are not distinguished from methods. In bytecode a constructor is a
     * method named {@code <init>}, and the coupling a parameter creates is the same
     * either way; the {@code :Method} node keeps the name for anyone who cares.
     */
    PARAM(Tier.SIGNATURE, 6, "takes parameter of type"),

    /** A method of B returns an A. */
    RETURNS(Tier.SIGNATURE, 6, "returns"),

    /** A method of B declares {@code throws A}. */
    THROWS(Tier.SIGNATURE, 3, "throws"),

    /**
     * A appears as a generic argument in B's signature — {@code List<A>}, {@code Map<String, A>}.
     *
     * <p>Separate from {@link #PARAM} and {@link #RETURNS} because it is the relation most
     * likely to be missing: it survives only where javac wrote a {@code Signature}
     * attribute. Recording it distinctly is what lets a query about completeness ask how
     * much of the graph rests on erasure.
     */
    TYPE_ARGUMENT(Tier.SIGNATURE, 5, "parameterised with"),

    // ---------------------------------------------------------------- body

    /** {@code new A(...)} somewhere in B's code. */
    INSTANTIATES(Tier.BODY, 5, "instantiates"),

    /** A method of B invokes a method of A. Carried at method granularity. */
    CALLS(Tier.BODY, 3, "calls"),

    /** B reads a non-constant field of A. */
    READS_FIELD(Tier.BODY, 3, "reads field of"),

    /** B writes a field of A. */
    WRITES_FIELD(Tier.BODY, 4, "writes field of"),

    /** {@code (A) x} or {@code x instanceof A}. */
    CASTS_TO(Tier.BODY, 2, "casts to"),

    /** {@code catch (A e)}. */
    CATCHES(Tier.BODY, 2, "catches"),

    /**
     * A is named in B's constant pool for a reason none of the above covers —
     * {@code A.class}, a method reference, an {@code ldc} of a class constant.
     *
     * <p>The catch-all. Anything landing here is a real reference and a weak one, and
     * keeping it separate stops it from inflating the relations that mean something.
     */
    REFERENCES(Tier.BODY, 1, "references");

    /** How hard the two types are to separate. */
    public enum Tier {

        /** Inheritance and declaration. B cannot exist without A. */
        STRUCTURE,

        /** A appears in B's public or internal API surface. Changing A changes B's shape. */
        SIGNATURE,

        /** A is used inside a method body. The weakest coupling, and the most common. */
        BODY
    }

    private final Tier tier;
    private final int weight;
    private final String displayName;

    TypeRelation(Tier tier, int weight, String displayName) {
        this.tier = tier;
        this.weight = weight;
        this.displayName = displayName;
    }

    public Tier tier() {
        return tier;
    }

    /** Contribution to the materialised {@code DEPENDS_ON} rollup. Higher is harder to undo. */
    public int weight() {
        return weight;
    }

    /** Reads as a sentence: "B " + displayName + " A". */
    public String displayName() {
        return displayName;
    }

    /**
     * Whether this relation is visible from outside the type.
     *
     * <p>The line the "third-party public surface only" decision is drawn on: for a class
     * from a jar, structure and signature relations are recorded and body relations are
     * not, because nobody is refactoring inside a dependency.
     */
    public boolean isSurface() {
        return tier != Tier.BODY;
    }
}
