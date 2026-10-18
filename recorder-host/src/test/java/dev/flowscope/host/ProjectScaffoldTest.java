package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class ProjectScaffoldTest {
    @TempDir
    Path temp;

    @Test
    void createsTheMarkerDirectoriesPlaceholdersAndGitignore() throws IOException {
        FlowProject project =
            ProjectScaffold.initialize(temp, ProjectManifest.defaults("Example app"));

        assertTrue(Files.isRegularFile(project.manifestPath()));
        assertTrue(Files.isDirectory(project.testsDir()));
        assertTrue(Files.isDirectory(project.suitesDir()));
        assertTrue(Files.isRegularFile(project.testsDir().resolve(".gitkeep")));
        assertTrue(Files.isRegularFile(project.suitesDir().resolve(".gitkeep")));

        assertEquals("# FlowScope\nreports/\nsessions/\n",
            Files.readString(temp.resolve(".gitignore"), StandardCharsets.UTF_8));
    }

    @Test
    void doesNotCreateReportsUntilSomethingRuns() throws IOException {
        FlowProject project = ProjectScaffold.initialize(temp, ProjectManifest.defaults("x"));
        assertFalse(Files.exists(project.reportsDir()));
    }

    @Test
    void theScaffoldedProjectOpensAndItsManifestRoundTrips() throws IOException {
        ProjectManifest manifest = new ProjectManifest(1, "Round trip", "flows", "reports",
            new ProjectManifest.Defaults("com.example.debug", CaptureMode.ZERO_CODE, 10000L, null),
            null);
        ProjectScaffold.initialize(temp, manifest);

        FlowProject reopened = FlowProject.open(temp);
        assertEquals(manifest, reopened.manifest());
        assertEquals(List.of(), reopened.listTests());
    }

    @Test
    void refusesToInitialiseTwice() throws IOException {
        ProjectScaffold.initialize(temp, ProjectManifest.defaults("x"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ProjectScaffold.initialize(temp, ProjectManifest.defaults("x")));
        assertTrue(failure.getMessage().startsWith("Project already initialised:"));
    }

    @Test
    void initialisesIntoANonEmptyDirectoryWithoutTouchingIt() throws IOException {
        Files.writeString(temp.resolve("build.gradle.kts"), "plugins {}");
        ProjectScaffold.initialize(temp, ProjectManifest.defaults("Existing repo"));
        assertEquals("plugins {}", Files.readString(temp.resolve("build.gradle.kts")));
    }

    @Test
    void appendsToAnExistingGitignoreWithoutReorderingIt() throws IOException {
        Files.writeString(temp.resolve(".gitignore"), "build/\n.idea/\n", StandardCharsets.UTF_8);
        ProjectScaffold.initialize(temp, ProjectManifest.defaults("x"));

        assertEquals("build/\n.idea/\n\n# FlowScope\nreports/\nsessions/\n",
            Files.readString(temp.resolve(".gitignore"), StandardCharsets.UTF_8));
    }

    @Test
    void mergeGitignoreAddsOnlyWhatIsMissing() {
        assertEquals("sessions/\n\n# FlowScope\nreports/\n",
            ProjectScaffold.mergeGitignore("sessions/\n", ProjectScaffold.gitignoreLines()));
    }

    @Test
    void mergeGitignoreIsIdempotent() {
        String once = ProjectScaffold.mergeGitignore("", ProjectScaffold.gitignoreLines());
        assertEquals(once, ProjectScaffold.mergeGitignore(once, ProjectScaffold.gitignoreLines()));
    }

    @Test
    void mergeGitignoreAddsATrailingNewlineBeforeAppending() {
        assertEquals("build/\n\n# FlowScope\nreports/\nsessions/\n",
            ProjectScaffold.mergeGitignore("build/", ProjectScaffold.gitignoreLines()));
    }
}
