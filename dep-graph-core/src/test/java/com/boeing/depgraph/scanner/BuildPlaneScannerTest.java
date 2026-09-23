package com.boeing.depgraph.scanner;

import com.boeing.depgraph.model.ArtifactKey;
import com.boeing.depgraph.model.ArtifactUsage;
import com.boeing.depgraph.model.BomProposal;
import com.boeing.depgraph.model.ConflictSeverity;
import com.boeing.depgraph.model.DeclaredDependency;
import com.boeing.depgraph.model.ManagedDependency;
import com.boeing.depgraph.model.ModuleLink;
import com.boeing.depgraph.model.ModuleRelation;
import com.boeing.depgraph.model.VersionOrigin;
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
 * The build plane, against a fixture estate that carries one of each problem on purpose.
 *
 * <p>Runs offline against {@code src/test/resources/fixture-repo} rather than the
 * developer's {@code ~/.m2}. That is what makes the assertions below claims about the
 * scanner instead of claims about the machine it ran on.
 */
class BuildPlaneScannerTest {

    private static ScanResult result;

    @BeforeAll
    static void scanTheFixtureEstate() throws URISyntaxException {
        Path estate = resource("/sample-estate");
        Path repository = resource("/fixture-repo");
        result = BuildPlaneScanner.withLocalRepository(repository, true).scan(estate);
    }

    private static Path resource(String name) throws URISyntaxException {
        return Paths.get(BuildPlaneScannerTest.class.getResource(name).toURI());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("every pom is read, aggregator included")
    void readsEveryPom() {
        assertThat(result.problems()).isEmpty();
        assertThat(result.modules()).extracting("artifactId")
            .containsExactlyInAnyOrder(
                "acme-parent", "platform-common", "order-service", "billing-service", "legacy-gateway");

        assertThat(result.modules()).filteredOn("artifactId", "acme-parent")
            .singleElement()
            .returns(true, m -> ((com.boeing.depgraph.model.ModuleRef) m).aggregator());
    }

    @Test
    @DisplayName("a version reached through a property is the number, not the property")
    void interpolatesProperties() {
        DeclaredDependency guava = dependency("order-service", "com.google.guava", "guava");

        assertThat(guava.gav().version()).isEqualTo("32.1.3-jre");
        assertThat(guava.rawVersion()).isEqualTo("${guava.version}");
        assertThat(guava.viaProperty()).isTrue();
        assertThat(guava.origin()).isEqualTo(VersionOrigin.LOCAL_DECLARATION);
    }

    @Test
    @DisplayName("the same property name in two poms is two different versions")
    void honoursPropertyOverrides() {
        // The trap a text-reading tool falls into: both poms say ${guava.version}, so a
        // scanner that reports the property reports no conflict at all.
        assertThat(dependency("order-service", "com.google.guava", "guava").gav().version())
            .isEqualTo("32.1.3-jre");
        assertThat(dependency("legacy-gateway", "com.google.guava", "guava").gav().version())
            .isEqualTo("32.1.2-jre");
    }

    @Test
    @DisplayName("a version the module never mentions is attributed to whoever decided it")
    void attributesManagedVersions() {
        DeclaredDependency slf4j = dependency("platform-common", "org.slf4j", "slf4j-api");
        assertThat(slf4j.gav().version()).isEqualTo("2.0.13");
        assertThat(slf4j.rawVersion()).isNull();
        assertThat(slf4j.origin()).isEqualTo(VersionOrigin.INHERITED_MANAGEMENT);

        DeclaredDependency jackson =
            dependency("order-service", "com.fasterxml.jackson.core", "jackson-databind");
        assertThat(jackson.gav().version()).isEqualTo("2.17.1");
        assertThat(jackson.origin()).isEqualTo(VersionOrigin.IMPORTED_BOM);
        assertThat(jackson.originModelId()).contains("acme-platform-bom");
    }

    // ------------------------------------------------------------------
    // Topology: how the poms point at each other.

    @Test
    @DisplayName("every child records the parent it inherits from")
    void linksParents() {
        assertThat(links(ModuleRelation.PARENT))
            .hasSize(4)
            .allSatisfy(link -> {
                assertThat(link.target().artifactId()).isEqualTo("acme-parent");
                assertThat(link.inProject()).isTrue();
            });
    }

    @Test
    @DisplayName("an aggregator links to the poms it lists, resolved on disk")
    void linksAggregatedModules() {
        assertThat(links(ModuleRelation.AGGREGATES))
            .hasSize(4)
            .extracting(link -> link.target().artifactId())
            .containsExactlyInAnyOrder(
                "platform-common", "order-service", "billing-service", "legacy-gateway");
    }

    @Test
    @DisplayName("a BOM import is an edge, even when the BOM is not in the tree")
    void linksBomImports() {
        assertThat(links(ModuleRelation.IMPORTS_BOM)).singleElement()
            .returns("acme-platform-bom", link -> link.target().artifactId())
            .returns("1.0.0", link -> link.target().version())
            // Resolved from the fixture repository, so there is no pom on disk to point
            // at. That is information about the project, not a failure.
            .returns(false, ModuleLink::inProject);
    }

    @Test
    @DisplayName("a dependency that resolves to another module in the scan is a module edge")
    void linksIntraProjectDependencies() {
        // Nothing in order-service's pom says platform-common is one of us. It is derived
        // from the scan, and the answer would change with the scan root.
        assertThat(links(ModuleRelation.DEPENDS_ON_MODULE)).singleElement()
            .returns("order-service", link -> link.from().artifactId())
            .returns("platform-common", link -> link.target().artifactId())
            .returns(true, ModuleLink::inProject);
    }

    @Test
    @DisplayName("dependencyManagement is the pom's own block, not the flattened union")
    void capturesOwnDependencyManagementOnly() {
        List<ManagedDependency> parent = result.managed().stream()
            .filter(m -> m.module().artifactId().equals("acme-parent"))
            .toList();

        // Two entries, not the whole of acme-platform-bom flattened in behind them.
        assertThat(parent).hasSize(2);
        assertThat(parent).filteredOn(ManagedDependency::bomImport).singleElement()
            .returns("acme-platform-bom", m -> m.gav().artifactId());
        assertThat(parent).filteredOn(m -> !m.bomImport()).singleElement()
            .returns("slf4j-api", m -> m.gav().artifactId())
            .returns("2.0.13", m -> m.gav().version());

        // A module that manages nothing manages nothing.
        assertThat(result.managed()).noneMatch(m -> m.module().artifactId().equals("order-service"));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("patch-level skew is SAFE and bumps to the highest in use")
    void classifiesPatchSkewAsSafe() {
        ArtifactUsage guava = usage("com.google.guava", "guava");

        assertThat(guava.severity()).isEqualTo(ConflictSeverity.SAFE);
        assertThat(guava.versions()).extracting("version")
            .containsExactly("32.1.3-jre", "32.1.2-jre");
        assertThat(entry(guava.key())).isPresent().get()
            .returns("32.1.3-jre", BomProposal.Entry::version)
            .returns(List.of("32.1.2-jre"), BomProposal.Entry::replaces);
    }

    @Test
    @DisplayName("minor skew is proposed but flagged for review")
    void classifiesMinorSkewAsReview() {
        ArtifactUsage lang3 = usage("org.apache.commons", "commons-lang3");

        assertThat(lang3.severity()).isEqualTo(ConflictSeverity.REVIEW);
        assertThat(entry(lang3.key())).isPresent().get()
            .returns("3.14.0", BomProposal.Entry::version)
            .returns(true, BomProposal.Entry::needsReview);
    }

    @Test
    @DisplayName("major skew stays out of the proposal and says why")
    void leavesMajorSkewOut() {
        ArtifactUsage mongo = usage("org.mongodb", "mongodb-driver-sync");

        assertThat(mongo.severity()).isEqualTo(ConflictSeverity.BLOCKED);
        assertThat(entry(mongo.key())).isEmpty();
        assertThat(excluded(mongo.key())).isPresent().get()
            .extracting(BomProposal.Excluded::reason, org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .contains("Major version skew");
    }

    @Test
    @DisplayName("an artifact an imported BOM already owns is excluded, not overridden")
    void leavesAlreadyManagedOut() {
        ArtifactUsage jackson = usage("com.fasterxml.jackson.core", "jackson-databind");

        // Two versions really are in play - 2.17.1 from the BOM, 2.15.2 hand-pinned - so
        // this is a genuine conflict. The fix is to stop pinning, not to pin harder.
        assertThat(jackson.isConflict()).isTrue();
        assertThat(jackson.severity()).isEqualTo(ConflictSeverity.ALREADY_MANAGED);
        assertThat(jackson.managedBy()).contains("acme-platform-bom");
        assertThat(entry(jackson.key())).isEmpty();
        assertThat(excluded(jackson.key())).isPresent().get()
            .extracting(BomProposal.Excluded::reason, org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .contains("already manages this at");
    }

    @Test
    @DisplayName("an artifact the BOM owns cleanly is not reported at all")
    void staysQuietAboutArtifactsTheBomOwnsCleanly() {
        ArtifactUsage assertj = usage("org.assertj", "assertj-core");

        assertThat(assertj.severity()).isEqualTo(ConflictSeverity.ALREADY_MANAGED);
        assertThat(assertj.isConflict()).isFalse();

        // Neither an entry nor an exclusion. Every module inherits 3.25.3 from
        // acme-platform-bom and agrees about it, which is the goal rather than a finding.
        assertThat(entry(assertj.key())).isEmpty();
        assertThat(excluded(assertj.key())).isEmpty();
    }

    @Test
    @DisplayName("an artifact hand-pinned over its BOM names the module doing it")
    void namesTheModuleThatPinsOverABom() {
        assertThat(excluded(new ArtifactKey("com.fasterxml.jackson.core", "jackson-databind")))
            .isPresent().get()
            .extracting(BomProposal.Excluded::reason, org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .contains("already manages this at 2.17.1")
            .contains("billing-service/pom.xml on 2.15.2")
            .contains("Delete the hand-pinned version");
    }

    @Test
    @DisplayName("an artifact every module already inherits earns no entry")
    void leavesWhollyManagedArtifactsAlone() {
        ArtifactUsage slf4j = usage("org.slf4j", "slf4j-api");

        assertThat(slf4j.severity()).isEqualTo(ConflictSeverity.UNIFORM);
        assertThat(slf4j.versions()).singleElement()
            .returns(true, v -> ((com.boeing.depgraph.model.VersionUse) v).whollyManaged());
        // Nothing to hoist: it is already decided in exactly one place.
        assertThat(entry(slf4j.key())).isEmpty();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a deliberate hold is carried into the proposal with its reason")
    void honoursDeclaredHolds() {
        ArtifactUsage pulsar = usage("org.apache.pulsar", "pulsar-client");

        assertThat(result.holds()).hasSize(1);
        assertThat(pulsar.held()).isTrue();
        assertThat(entry(pulsar.key())).isPresent().get()
            .returns("3.3.1", BomProposal.Entry::version)
            .extracting(BomProposal.Entry::rationale, org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .contains("Held at 3.3.1")
            .contains("API migration")
            .contains("revisit:");
    }

    @Test
    @DisplayName("the headline counts add up")
    void reportsStats() {
        ScanResult.ScanStats stats = result.stats();

        assertThat(stats.pomsFound()).isEqualTo(5);
        assertThat(stats.modulesRead()).isEqualTo(5);
        assertThat(stats.modulesFailed()).isZero();
        assertThat(stats.conflictCount()).isEqualTo(4);   // guava, lang3, mongo, jackson
        assertThat(stats.safeCount()).isEqualTo(1);
        assertThat(stats.reviewCount()).isEqualTo(1);
        assertThat(stats.blockedCount()).isEqualTo(1);
        assertThat(stats.alreadyManagedCount()).isEqualTo(2);  // jackson, and assertj quietly
        assertThat(stats.heldCount()).isEqualTo(1);
        assertThat(stats.proposedEntryCount()).isEqualTo(result.proposal().entries().size());
        assertThat(stats.managedCount()).isEqualTo(2);
        assertThat(stats.bomImportCount()).isEqualTo(1);
        assertThat(stats.intraProjectDependencyCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private static DeclaredDependency dependency(String module, String groupId, String artifactId) {
        return result.dependencies().stream()
            .filter(d -> d.module().artifactId().equals(module))
            .filter(d -> d.key().equals(new ArtifactKey(groupId, artifactId)))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                module + " does not depend on " + groupId + ":" + artifactId));
    }

    private static ArtifactUsage usage(String groupId, String artifactId) {
        ArtifactKey key = new ArtifactKey(groupId, artifactId);
        return result.artifacts().stream()
            .filter(a -> a.key().equals(key))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No usage recorded for " + key));
    }

    private static List<ModuleLink> links(ModuleRelation relation) {
        return result.links().stream().filter(l -> l.relation() == relation).toList();
    }

    private static Optional<BomProposal.Entry> entry(ArtifactKey key) {
        return result.proposal().entries().stream().filter(e -> e.key().equals(key)).findFirst();
    }

    private static Optional<BomProposal.Excluded> excluded(ArtifactKey key) {
        return result.proposal().excluded().stream().filter(e -> e.key().equals(key)).findFirst();
    }
}
