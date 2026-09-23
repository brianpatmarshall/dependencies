package com.boeing.depgraph.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The front door.
 *
 * <p>Most of these are about what happens when someone types the path wrong, because
 * that is the failure this tool will hit most often and the one where a bad message
 * costs the most.
 */
class DepGraphCliTest {

    /** A single pom, no parent, no modules - the shape most repositories are. */
    private static Path simpleProject() throws URISyntaxException {
        return Paths.get(DepGraphCliTest.class.getResource("/simple-project").toURI());
    }

    private record Run(int exitCode, String out, String err) {}

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = DepGraphCli.run(args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ usage

    @Test
    @DisplayName("--help explains itself and exits cleanly")
    void printsHelp() {
        Run run = run("--help");

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("depgraph [options] <path>")
            .contains("single pom or a")
            .contains("multi-module reactor");
    }

    @Test
    @DisplayName("no path is a usage error, and says so before printing the usage")
    void requiresAPath() {
        Run run = run();

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.err()).contains("No path given");
        assertThat(run.err()).contains("USAGE");
    }

    @Test
    @DisplayName("an unknown option names the option")
    void rejectsUnknownOptions() {
        Run run = run("--deep", "/tmp");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.err()).contains("Unknown option: --deep");
    }

    @Test
    @DisplayName("two paths is almost always an unquoted glob, so both are named")
    void rejectsExtraArguments() {
        Run run = run("/tmp/one", "/tmp/two");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.err()).contains("/tmp/one").contains("/tmp/two");
    }

    // ------------------------------------------------------------------ bad targets

    @Test
    @DisplayName("a path that does not exist exits 2, not 1 or 3")
    void rejectsMissingPath(@TempDir Path temp) {
        Run run = run(temp.resolve("nope").toString());

        // A distinct code so a wrapping script can tell "you typed it wrong" from
        // "the tool broke".
        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.err()).contains("No such file or directory");
    }

    @Test
    @DisplayName("a directory with no poms is an error, never an empty success")
    void rejectsDirectoryWithNoPoms(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("README.md"), "not a maven project");

        Run run = run(temp.toString());

        // Reporting success with zero of everything is indistinguishable from a project
        // with no dependencies, and the user would believe the second one.
        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.err()).contains("No pom.xml found");
        assertThat(run.out()).isEmpty();
    }

    @Test
    @DisplayName("a file that is not a pom says what to point at instead")
    void rejectsNonPomFile(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("build.gradle");
        Files.writeString(file, "plugins { id 'java' }");

        Run run = run(file.toString());

        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.err()).contains("is a file, not a project directory");
    }

    // ------------------------------------------------------------------ the simple case

    @Test
    @DisplayName("a single-module project scans, and is named from its pom")
    void scansASingleModuleProject() throws Exception {
        Run run = run(simpleProject().toString(), "--build-plane-only", "--quiet");

        assertThat(run.exitCode()).isZero();
        // The pom's <name>, not the directory it happens to sit in.
        assertThat(run.out()).startsWith("Standalone Tool");
        assertThat(run.out()).contains("modules                  1");
        assertThat(run.out()).contains("CONFLICTS  0")
            .contains("Every artifact is on one version.");
        // One module cannot disagree with itself, so what it gets is the hoist.
        assertThat(run.out()).contains("PROPOSED BOM  3 entries");
    }

    @Test
    @DisplayName("pointing at the pom itself works as well as pointing at its directory")
    void acceptsAPomFile() throws Exception {
        Run viaDirectory = run(simpleProject().toString(), "--build-plane-only", "--quiet");
        Run viaPom = run(simpleProject().resolve("pom.xml").toString(), "--build-plane-only", "--quiet");

        assertThat(viaPom.exitCode()).isZero();
        assertThat(withoutTimings(viaPom.out())).isEqualTo(withoutTimings(viaDirectory.out()));
    }

    /**
     * The report prints how long the scan took, which differs between two runs of the
     * same scan. Comparing raw output made this test pass or fail on whether both runs
     * happened to land in the same millisecond.
     */
    private static String withoutTimings(String report) {
        return report.replaceAll("(?m)^(\\s*scanned in\\s+)\\d+ ms$", "$1<elided> ms");
    }

    // ------------------------------------------------------------------ output shapes

    @Test
    @DisplayName("--json is valid JSON with the counts and the proposal in it")
    void writesJson() throws Exception {
        Run run = run(simpleProject().toString(), "--build-plane-only", "--json", "--quiet");

        assertThat(run.exitCode()).isZero();
        JsonNode json = new ObjectMapper().readTree(run.out());

        assertThat(json.get("project").asText()).isEqualTo("Standalone Tool");
        assertThat(json.get("summary").get("modules").asInt()).isEqualTo(1);
        assertThat(json.get("summary").get("dependencies").asInt()).isEqualTo(3);
        assertThat(json.get("proposal").get("entries")).hasSize(3);
        // Not read, so null rather than an empty object - "we did not look" is not the
        // same claim as "we looked and found nothing".
        assertThat(json.get("code").isNull()).isTrue();

        // Dependencies are flattened to the module path, not carrying a nested module.
        JsonNode dependency = json.get("dependencies").get(0);
        assertThat(dependency.get("module").asText()).isEqualTo("pom.xml");
        assertThat(dependency.has("origin")).isTrue();
    }

    @Test
    @DisplayName("--bom prints a block that can be pasted into a pom")
    void writesBomXml() throws Exception {
        Run run = run(simpleProject().toString(), "--build-plane-only", "--bom", "--quiet");

        assertThat(run.exitCode()).isZero();
        assertThat(run.out())
            .contains("<dependencyManagement>")
            .contains("<artifactId>guava</artifactId>")
            .contains("<version>32.1.3-jre</version>")
            .contains("</dependencyManagement>");
        // Every managed version carries its reason. An unexplained one is
        // indistinguishable from one nobody has revisited.
        assertThat(run.out()).contains("Only pom.xml declares it");
    }

    @Test
    @DisplayName("a BOM comment stays readable; only the illegal sequence is touched")
    void doesNotOverEscapeComments() throws Exception {
        Run run = run(simpleProject().toString(), "--build-plane-only", "--bom", "--quiet");

        // Angle brackets are legal inside an XML comment, and escaping them turns a
        // rationale that reads as a sentence into one that reads as markup. Only `--`
        // actually cannot appear there.
        assertThat(run.out()).doesNotContain("-&gt;");
        assertThat(run.out()).doesNotContain("&amp;");

        // The block still has to parse. Strip the trailing exclusion note, which is a
        // sibling comment rather than part of the element.
        String xml = run.out().substring(run.out().indexOf("<dependencyManagement>"));
        int end = xml.indexOf("</dependencyManagement>") + "</dependencyManagement>".length();
        javax.xml.parsers.DocumentBuilderFactory factory =
            javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
            xml.substring(0, end).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("narration goes to stderr so stdout can be redirected")
    void keepsNarrationOffStdout() throws Exception {
        Run run = run(simpleProject().toString(), "--build-plane-only");

        assertThat(run.err()).contains("Scanning").contains("Local repository:");
        assertThat(run.out()).doesNotContain("Scanning");
    }
}
