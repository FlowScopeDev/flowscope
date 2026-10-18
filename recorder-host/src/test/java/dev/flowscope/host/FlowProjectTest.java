package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class FlowProjectTest {
    @TempDir
    Path temp;

    @Test
    void openReadsTheManifestAndResolvesTheLayout() throws IOException {
        FlowProject project = scaffold();

        assertEquals(root(), project.root());
        assertEquals("Example", project.manifest().name());
        assertEquals(project.root().resolve("flows"), project.flowsDir());
        assertEquals(project.root().resolve("flows/tests"), project.testsDir());
        assertEquals(project.root().resolve("flows/suites"), project.suitesDir());
        assertEquals(project.root().resolve("reports"), project.reportsDir());
        assertEquals(project.root().resolve("flowscope.json"), project.manifestPath());
    }

    @Test
    void openRejectsADirectoryWithoutTheMarker() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> FlowProject.open(temp));
        assertTrue(failure.getMessage().contains("No flowscope.json in"));
        assertTrue(failure.getMessage().contains("flowscope project init"));
    }

    @Test
    void honoursConfiguredDirectoryNames() throws IOException {
        Files.writeString(temp.resolve("flowscope.json"), """
            {"schemaVersion": 1, "name": "Custom",
             "flowsDir": "automation/flows", "reportsDir": "out/reports"}
            """, StandardCharsets.UTF_8);
        FlowProject project = FlowProject.open(temp);

        assertEquals(project.root().resolve("automation/flows/tests"), project.testsDir());
        assertEquals(project.root().resolve("out/reports"), project.reportsDir());
    }

    @Test
    void discoverWalksUpFromANestedDirectory() throws IOException {
        FlowProject expected = scaffold();
        Path nested = Files.createDirectories(temp.resolve("flows/tests/deep/deeper"));

        Optional<FlowProject> found = FlowProject.discover(nested);
        assertTrue(found.isPresent());
        assertEquals(expected.root(), found.get().root());
    }

    @Test
    void discoverFromAFilePathStartsAtItsParent() throws IOException {
        FlowProject expected = scaffold();
        Path test = writeTest("login.flow");

        Optional<FlowProject> found = FlowProject.discover(test);
        assertTrue(found.isPresent());
        assertEquals(expected.root(), found.get().root());
    }

    @Test
    void discoverReturnsEmptyWhenNoMarkerExistsAnywhereAbove() throws IOException {
        Path isolated = Files.createDirectories(temp.resolve("no-project"));
        assertTrue(FlowProject.discover(isolated).isEmpty());
    }

    @Test
    void requireExplainsHowToCreateAProject() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> FlowProject.require(temp));
        assertTrue(failure.getMessage().contains("or any parent directory"));
        assertTrue(failure.getMessage().contains("flowscope project init"));
    }

    @Test
    void resolveTestArgumentWalksTheLadderAndAppendsTheExtension() throws IOException {
        FlowProject project = scaffold();
        Path inTests = writeTest("login.flow");
        Path inFlows = Files.writeString(project.flowsDir().resolve("shared.flow"), "x");
        Path atRoot = Files.writeString(project.root().resolve("legacy.flow"), "x");

        assertEquals(inTests, project.resolveTestArgument("login"));
        assertEquals(inTests, project.resolveTestArgument("login.flow"));
        assertEquals(inFlows, project.resolveTestArgument("shared"));
        assertEquals(atRoot, project.resolveTestArgument("legacy.flow"));
        assertEquals(inTests, project.resolveTestArgument("flows/tests/login.flow"));
    }

    @Test
    void resolveTestArgumentAcceptsAnAbsolutePathAndNamesWhatItSearched() throws IOException {
        FlowProject project = scaffold();
        Path outside = Files.writeString(temp.resolve("outside.flow"), "x");
        assertEquals(outside.toAbsolutePath().normalize(),
            project.resolveTestArgument(outside.toString()));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> project.resolveTestArgument("absent"));
        assertTrue(failure.getMessage().startsWith("Test not found: absent"));
        assertTrue(failure.getMessage().contains("searched flows/tests in"));
    }

    @Test
    void resolveSuiteEntryIsStrict() throws IOException {
        FlowProject project = scaffold();
        Path login = writeTest("login.flow");

        assertEquals(login, project.resolveSuiteEntry("tests/login.flow"));

        assertTrue(suiteEntryError(project, "tests/login").contains("must end in .flow"));
        assertTrue(suiteEntryError(project, "tests/absent.flow").contains("test not found"));
        assertTrue(suiteEntryError(project, "/abs/login.flow").contains("must be relative"));
        assertTrue(suiteEntryError(project, "tests\\login.flow").contains("forward slashes"));
        assertTrue(suiteEntryError(project, "").contains("empty test reference"));
    }

    @Test
    void bothResolversRejectEscapingTheProject() throws IOException {
        FlowProject project = scaffold();
        Files.writeString(temp.getParent().resolve("escape.flow"), "x");

        assertTrue(suiteEntryError(project, "../../escape.flow").contains("escapes the project"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> project.resolveTestArgument("../../escape.flow"))
            .getMessage().startsWith("Test not found"));
    }

    @Test
    void resolveSuiteArgumentWalksItsOwnLadder() throws IOException {
        FlowProject project = scaffold();
        Path nightly = writeSuite("nightly.suite");

        assertEquals(nightly, project.resolveSuiteArgument("nightly"));
        assertEquals(nightly, project.resolveSuiteArgument("nightly.suite"));
        assertEquals(nightly, project.resolveSuiteArgument("flows/suites/nightly.suite"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> project.resolveSuiteArgument("absent")).getMessage()
            .startsWith("Suite not found: absent"));
    }

    @Test
    void listTestsIsRecursiveSortedAndSkipsHiddenDirectories() throws IOException {
        FlowProject project = scaffold();
        writeTest("zebra.flow");
        writeTest("alpha.flow");
        Files.createDirectories(project.testsDir().resolve("checkout"));
        Files.writeString(project.testsDir().resolve("checkout/pay.flow"), "x");
        Files.createDirectories(project.testsDir().resolve(".drafts"));
        Files.writeString(project.testsDir().resolve(".drafts/wip.flow"), "x");
        Files.writeString(project.testsDir().resolve("notes.txt"), "x");

        assertEquals(
            List.of("flows/tests/alpha.flow", "flows/tests/checkout/pay.flow",
                "flows/tests/zebra.flow"),
            project.listTests().stream().map(project::relativize).toList());
    }

    @Test
    void listSuitesFiltersByExtensionAndEmptyProjectsListNothing() throws IOException {
        FlowProject project = scaffold();
        writeSuite("regression.suite");
        Files.writeString(project.suitesDir().resolve("README.md"), "x");

        assertEquals(List.of("flows/suites/regression.suite"),
            project.listSuites().stream().map(project::relativize).toList());

        FlowProject bare = ProjectScaffold.initialize(
            temp.resolve("bare"), ProjectManifest.defaults("Bare"));
        Files.delete(bare.testsDir().resolve(".gitkeep"));
        Files.delete(bare.testsDir());
        assertEquals(List.of(), bare.listTests());
    }

    @Test
    void relativizeUsesForwardSlashes() throws IOException {
        FlowProject project = scaffold();
        assertEquals("flows/tests/login.flow", project.relativize(writeTest("login.flow")));
    }

    /**
     * `FlowProject` normalizes but deliberately does not resolve symlinks, so every expectation
     * here must use the same normalized form — on macOS @TempDir hands back /var, a symlink to
     * /private/var, and toRealPath() would disagree with the production path.
     */
    private Path root() {
        return temp.toAbsolutePath().normalize();
    }

    private FlowProject scaffold() throws IOException {
        return ProjectScaffold.initialize(temp, ProjectManifest.defaults("Example"));
    }

    private Path writeTest(String name) throws IOException {
        FlowProject project = FlowProject.open(temp);
        Files.createDirectories(project.testsDir());
        return Files.writeString(project.testsDir().resolve(name), "x", StandardCharsets.UTF_8);
    }

    private Path writeSuite(String name) throws IOException {
        FlowProject project = FlowProject.open(temp);
        Files.createDirectories(project.suitesDir());
        return Files.writeString(project.suitesDir().resolve(name), "x", StandardCharsets.UTF_8);
    }

    private static String suiteEntryError(FlowProject project, String reference) {
        return assertThrows(IllegalArgumentException.class,
            () -> project.resolveSuiteEntry(reference)).getMessage();
    }
}
