package com.boeing.depgraph.code;

import com.boeing.depgraph.model.FieldRef;
import com.boeing.depgraph.model.MethodRef;
import com.boeing.depgraph.model.TypeEdge;
import com.boeing.depgraph.model.TypeKind;
import com.boeing.depgraph.model.TypeRef;
import com.boeing.depgraph.model.TypeRelation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The code plane, against fixture classes this build compiled a moment ago.
 *
 * <p>Every assertion names a relation from {@link TypeRelation} and a place in
 * {@code com.boeing.depgraph.fixtures} that produces it.
 */
class ClassPlaneScannerTest {

    private static final String PKG = "com.boeing.depgraph.fixtures.";

    private static CodeScanResult result;

    @BeforeAll
    static void scanTheCompiledFixtures() throws URISyntaxException {
        Path classes = Paths.get(ClassPlaneScannerTest.class
            .getResource("/com/boeing/depgraph/fixtures").toURI());
        result = new ClassPlaneScanner().scanDirectory(classes);
    }

    // ------------------------------------------------------------------ types

    @Test
    @DisplayName("every fixture class is found, with its kind read from access flags")
    void readsTypes() {
        assertThat(result.types()).extracting(TypeRef::fqn)
            .contains(PKG + "Car", PKG + "Drivable", PKG + "Part", PKG + "Garage$Bay");

        assertThat(type("Car")).returns(TypeKind.CLASS, TypeRef::kind).returns(true, TypeRef::isPublic);
        assertThat(type("Drivable")).returns(TypeKind.INTERFACE, TypeRef::kind);
        assertThat(type("Audited")).returns(TypeKind.ANNOTATION, TypeRef::kind);
        // The only way to tell a record from a final class is that it extends Record.
        assertThat(type("Part")).returns(TypeKind.RECORD, TypeRef::kind);
        assertThat(type("Garage$Bay")).returns(true, TypeRef::isNested);
        assertThat(result.types()).allSatisfy(t -> assertThat(t.thirdParty()).isFalse());
    }

    // ------------------------------------------------------------ the three asked for

    @Test
    @DisplayName("1. B is a child of A")
    void capturesExtends() {
        assertThat(edge("Car", "Vehicle", TypeRelation.EXTENDS)).isPresent();
    }

    @Test
    @DisplayName("2. B implements A")
    void capturesImplements() {
        assertThat(edge("Car", "Drivable", TypeRelation.IMPLEMENTS)).isPresent();
        assertThat(edge("Circle", "Shape", TypeRelation.IMPLEMENTS)).isPresent();
    }

    @Test
    @DisplayName("3. B's constructor or method has a param of type A")
    void capturesParameters() {
        // On a constructor - which in bytecode is a method named <init>.
        assertThat(edge("Garage", "Toolbox", TypeRelation.PARAM))
            .isPresent().get().returns("<init>", TypeEdge::viaMember);

        // And on ordinary methods.
        assertThat(edge("Garage", "Car", TypeRelation.PARAM)).isPresent();
        assertThat(edge("Garage", "Vehicle", TypeRelation.PARAM)).isPresent();
    }

    // ------------------------------------------------------------------ the rest

    @Test
    @DisplayName("structure: nesting, sealing, annotations")
    void capturesStructure() {
        assertThat(edge("Garage$Bay", "Garage", TypeRelation.NESTED_IN)).isPresent();
        assertThat(edge("Shape", "Circle", TypeRelation.PERMITS)).isPresent();
        assertThat(edge("Garage", "Audited", TypeRelation.ANNOTATED_BY)).isPresent();
    }

    @Test
    @DisplayName("signature: fields, returns, throws")
    void capturesSignatures() {
        assertThat(edge("Garage", "Toolbox", TypeRelation.FIELD_TYPE)).isPresent();
        assertThat(edge("Garage", "Invoice", TypeRelation.RETURNS)).isPresent();
        assertThat(edge("Garage", "InspectionFailed", TypeRelation.THROWS)).isPresent();
    }

    @Test
    @DisplayName("a generic type argument survives erasure via the Signature attribute")
    void capturesTypeArguments() {
        // Garage never names Part outside List<Part>. The descriptor says only List;
        // Part exists solely in the Signature attribute, which is the whole reason
        // TYPE_ARGUMENT is a relation of its own.
        assertThat(edge("Garage", "Part", TypeRelation.TYPE_ARGUMENT)).isPresent();
    }

    @Test
    @DisplayName("body: instantiation, calls, casts, catches, field access")
    void capturesBodies() {
        assertThat(edge("Garage", "Invoice", TypeRelation.INSTANTIATES)).isPresent();
        assertThat(edge("Garage", "Car", TypeRelation.CALLS)).isPresent();
        assertThat(edge("Garage", "Car", TypeRelation.CASTS_TO)).isPresent();
        assertThat(edge("Garage", "InspectionFailed", TypeRelation.CATCHES)).isPresent();
        assertThat(edge("Garage", "Toolbox", TypeRelation.READS_FIELD)).isPresent();
        assertThat(edge("Garage", "Toolbox", TypeRelation.WRITES_FIELD)).isPresent();
        assertThat(edge("Garage", "Shape", TypeRelation.REFERENCES)).isPresent();
    }

    @Test
    @DisplayName("a method reference is a call, not an invisible lambda")
    void capturesMethodReferences() {
        // Part::name compiles to invokedynamic; without reading the bootstrap arguments
        // this edge would not exist at all.
        assertThat(edge("Garage", "Part", TypeRelation.CALLS)).isPresent();
    }

    @Test
    @DisplayName("occurrences are aggregated onto one edge with a count")
    void aggregatesOccurrences() {
        // park() casts to Car twice - instanceof and the cast itself.
        assertThat(edge("Garage", "Car", TypeRelation.CASTS_TO))
            .isPresent().get()
            .returns(true, e -> e.count() >= 2);

        assertThat(result.edges())
            .extracting(TypeEdge::groupKey)
            .doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ blind spot

    @Test
    @DisplayName("an inlined compile-time constant leaves no edge - the documented blind spot")
    void cannotSeeInlinedConstants() {
        // BayCounter.capacity() is nothing but `Limits.MAX_BAYS * 2`. javac copies the
        // literal 4 in and writes no reference at all, so BayCounter has no bytecode
        // dependency on Limits of ANY kind. No amount of bytecode reading will find it.
        assertThat(allFrom("BayCounter"))
            .extracting(TypeEdge::toFqn)
            .doesNotContain(PKG + "Limits");

        // The control. DEFAULT_BAY is initialised by a method call, so it is not a
        // compile-time constant, is read at runtime, and Garage's reference to it does
        // survive. That is what makes the absence above constant folding rather than a
        // bug in the scanner.
        assertThat(edge("Garage", "Limits", TypeRelation.READS_FIELD)).isPresent();

        // Both fields are classified, so the size of the blind spot is reportable.
        assertThat(field("Limits", "MAX_BAYS")).returns(true, FieldRef::constant);
        assertThat(field("Limits", "DEFAULT_BAY")).returns(false, FieldRef::constant);
        assertThat(result.stats().inlinedConstants()).isEqualTo(1);
    }

    @Test
    @DisplayName("the JDK is not a dependency anybody manages")
    void ignoresJdkTypes() {
        assertThat(result.edges()).allSatisfy(e -> {
            assertThat(e.toFqn()).doesNotStartWith("java.");
            assertThat(e.toFqn()).doesNotStartWith("javax.");
        });
        // Vehicle extends Object and that is not worth an edge.
        assertThat(result.edges()).filteredOn(e -> e.fromFqn().equals(PKG + "Vehicle"))
            .isEmpty();
    }

    @Test
    @DisplayName("a class never depends on itself")
    void dropsSelfEdges() {
        assertThat(result.edges()).allSatisfy(e -> assertThat(e.fromFqn()).isNotEqualTo(e.toFqn()));
    }

    @Test
    @DisplayName("constructors are methods named <init>, and synthetics are skipped")
    void capturesMembers() {
        assertThat(result.methods()).filteredOn(m -> m.ownerFqn().equals(PKG + "Garage"))
            .filteredOn(MethodRef::isConstructor)
            .singleElement()
            .returns("<init>", MethodRef::name);

        // A record's accessors are real; its javac-generated equals/hashCode bridge
        // machinery is synthetic and must not appear.
        assertThat(result.methods()).filteredOn(m -> m.ownerFqn().equals(PKG + "Part"))
            .extracting(MethodRef::name)
            .contains("name", "quantity")
            .doesNotContain("lambda$new$0");
    }

    @Test
    @DisplayName("the counts add up")
    void reportsStats() {
        CodeScanResult.CodeStats stats = result.stats();

        assertThat(stats.classFilesRead()).isEqualTo(result.types().size());
        assertThat(stats.typesFound()).isGreaterThanOrEqualTo(13);
        assertThat(stats.edgeCount()).isEqualTo(result.edges().size());
        assertThat(stats.edgeOccurrences()).isGreaterThanOrEqualTo(stats.edgeCount());
        assertThat(stats.thirdPartyTypes()).isZero();
        assertThat(stats.modulesMissingClasses()).isZero();
    }

    // ------------------------------------------------------------------

    private static TypeRef type(String simpleName) {
        return result.types().stream().filter(t -> t.fqn().equals(PKG + simpleName))
            .findFirst().orElseThrow(() -> new AssertionError("No type " + simpleName));
    }

    private static FieldRef field(String owner, String name) {
        return result.fields().stream()
            .filter(f -> f.ownerFqn().equals(PKG + owner) && f.name().equals(name))
            .findFirst().orElseThrow(() -> new AssertionError("No field " + owner + "#" + name));
    }

    private static Optional<TypeEdge> edge(String from, String to, TypeRelation relation) {
        return result.edges().stream()
            .filter(e -> e.fromFqn().equals(PKG + from))
            .filter(e -> e.toFqn().equals(PKG + to))
            .filter(e -> e.relation() == relation)
            .findFirst();
    }

    private static List<TypeEdge> allFrom(String from) {
        return result.edges().stream().filter(e -> e.fromFqn().equals(PKG + from)).toList();
    }
}
