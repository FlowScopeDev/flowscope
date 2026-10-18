package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Device-free: the constructor only touches the filesystem, and {@code new AdbClient()} resolves an
 * executable path without spawning anything.
 */
class FlowTestRunnerDirectoryTest {
    @TempDir
    Path temp;

    private static final Device DEVICE =
        new Device("TESTSERIAL01", "device", Map.of("model", "Pixel"));

    @Test
    void theFiveArgumentConstructorUsesTheGivenNameVerbatim() throws IOException {
        FlowTestRunner runner = new FlowTestRunner(
            new AdbClient(), DEVICE, test("Login smoke"), temp, "01-Login_smoke");

        assertEquals(temp.resolve("01-Login_smoke").toAbsolutePath(), runner.root());
        assertTrue(Files.isDirectory(runner.root().resolve("evidence")));
    }

    @Test
    void theFourArgumentConstructorStillTimestampsAndSanitisesTheTestName() throws IOException {
        FlowTestRunner runner = new FlowTestRunner(
            new AdbClient(), DEVICE, test("Terms & Policies / continue"), temp);

        String directory = runner.root().getFileName().toString();
        assertTrue(directory.matches("\\d{8}-\\d{6}-Terms___Policies___continue"), directory);
        assertTrue(Files.isDirectory(runner.root().resolve("evidence")));
    }

    @Test
    void callerNamedDirectoriesDoNotCollideForTheSameTestNameTwice() throws IOException {
        FlowTest sameName = test("Login smoke");
        FlowTestRunner first =
            new FlowTestRunner(new AdbClient(), DEVICE, sameName, temp, "01-Login_smoke");
        FlowTestRunner second =
            new FlowTestRunner(new AdbClient(), DEVICE, sameName, temp, "02-Login_smoke");

        assertNotEquals(first.root(), second.root());
        assertTrue(Files.isDirectory(first.root().resolve("evidence")));
        assertTrue(Files.isDirectory(second.root().resolve("evidence")));
    }

    @Test
    void sanitizeReplacesEverythingUnsafeAndNeverReturnsBlank() {
        assertEquals("Login_smoke", RunDirectories.sanitize("Login smoke"));
        assertEquals("a.b-c_d", RunDirectories.sanitize("a.b-c_d"));
        assertEquals("___", RunDirectories.sanitize("中文字"));
        assertEquals("run", RunDirectories.sanitize(""));
        assertEquals("run", RunDirectories.sanitize(null));
    }

    @Test
    void createMakesATimestampedDirectory() throws IOException {
        Path created = RunDirectories.create(temp.resolve("reports"), "Nightly regression");
        assertTrue(Files.isDirectory(created));
        assertTrue(created.getFileName().toString().endsWith("-Nightly_regression"));
        assertTrue(created.getFileName().toString().matches("\\d{8}-\\d{6}-.*"));
    }

    private static FlowTest test(String name) {
        return new FlowTest(name, "com.example.debug", CaptureMode.ZERO_CODE, 10_000,
            List.of(new FlowTest.Step(7, "launch", Map.of())));
    }
}
