package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class RunReportTest {
    @TempDir
    Path temp;

    @Test
    void readsATestReport() throws IOException {
        Path run = writeTestReport(
            "20261018-101500-Login_smoke", "passed", "passed", "passed", "passed");

        RunReport report = RunReport.read(run).orElseThrow();
        assertEquals("test", report.kind());
        assertEquals("Login smoke", report.name());
        assertEquals("passed", report.status());
        assertEquals("2026-10-18T10:15:00.123Z", report.startedAt());
        assertEquals(3, report.total());
        assertEquals(3, report.passed());
        assertEquals(0, report.failed());
        assertTrue(report.isPassed());
        assertFalse(report.isSuite());
    }

    @Test
    void countsFailedStepsInATestReport() throws IOException {
        Path run = writeTestReport("20261018-101500-Login", "failed", "passed", "failed");
        RunReport report = RunReport.read(run).orElseThrow();
        assertEquals(2, report.total());
        assertEquals(1, report.passed());
        assertEquals(1, report.failed());
        assertFalse(report.isPassed());
    }

    @Test
    void readsASuiteReportAndPrefersItOverATestReportInTheSameDirectory() throws IOException {
        Path run = Files.createDirectories(temp.resolve("20261018-101500-Nightly"));
        Files.writeString(run.resolve(RunReport.SUITE_REPORT),
            FlowSuiteRunner.reportJson(new FlowSuiteRunner.SuiteReport(
                "Nightly regression", "flows/suites/nightly.suite", "/app", "ABC", true,
                java.time.Instant.parse("2026-10-18T10:15:00.123Z"),
                java.time.Instant.parse("2026-10-18T10:19:42.004Z"),
                List.of(
                    new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                        "01-A/report.json", null),
                    new FlowSuiteRunner.TestOutcome(2, 4, "tests/b.flow", "B", "failed", 20,
                        "02-B/report.json", "boom")))),
            StandardCharsets.UTF_8);
        Files.writeString(run.resolve(RunReport.TEST_REPORT), "{\"not\": \"read\"}");

        RunReport report = RunReport.read(run).orElseThrow();
        assertEquals("suite", report.kind());
        assertTrue(report.isSuite());
        assertEquals("Nightly regression", report.name());
        assertEquals("failed", report.status());
        assertEquals(2, report.total());
        assertEquals(1, report.passed());
        assertEquals(1, report.failed());
    }

    @Test
    void returnsEmptyForADirectoryWithNeitherReportOrAnUnreadableOne() throws IOException {
        Path bare = Files.createDirectories(temp.resolve("bare"));
        assertEquals(Optional.empty(), RunReport.read(bare));

        Path broken = Files.createDirectories(temp.resolve("broken"));
        Files.writeString(broken.resolve(RunReport.TEST_REPORT), "{not json");
        assertEquals(Optional.empty(), RunReport.read(broken));

        Path incomplete = Files.createDirectories(temp.resolve("incomplete"));
        Files.writeString(incomplete.resolve(RunReport.TEST_REPORT), "{\"status\": \"passed\"}");
        assertEquals(Optional.empty(), RunReport.read(incomplete));
    }

    @Test
    void listsTheNewestRunsFirstAndSkipsWhatIsNotARun() throws IOException {
        writeTestReport("20261018-090000-Early", "passed", "passed");
        writeTestReport("20261018-120000-Late", "failed", "failed");
        writeTestReport("20261018-100000-Middle", "passed", "passed");
        Files.createDirectories(temp.resolve("20261018-110000-NotARun"));
        Files.writeString(temp.resolve("stray.txt"), "x");

        assertEquals(List.of("Late", "Middle", "Early"),
            RunReport.list(temp, 10).stream().map(RunReport::name).toList());
        assertEquals(List.of("Late"), RunReport.list(temp, 1).stream()
            .map(RunReport::name).toList());
    }

    @Test
    void listingAMissingDirectoryIsEmptyNotAnError() throws IOException {
        assertEquals(List.of(), RunReport.list(temp.resolve("reports"), 10));
    }

    private Path writeTestReport(String directory, String status, String... stepStatuses)
        throws IOException {
        Path run = Files.createDirectories(temp.resolve(directory));
        StringBuilder steps = new StringBuilder();
        for (int index = 0; index < stepStatuses.length; index++) {
            if (index > 0) steps.append(",\n");
            steps.append("    {\"number\": ").append(index + 1)
                .append(", \"line\": ").append(index + 7)
                .append(", \"action\": \"tap\", \"status\": \"").append(stepStatuses[index])
                .append("\", \"durationMillis\": 10, \"error\": null}");
        }
        String name = directory.substring(directory.indexOf('-', 9) + 1).replace('_', ' ');
        Files.writeString(run.resolve(RunReport.TEST_REPORT), """
            {
              "schemaVersion": 1,
              "name": "%s",
              "packageName": "com.example.debug",
              "mode": "zero-code",
              "deviceSerial": "ABC",
              "startedAt": "2026-10-18T10:15:00.123Z",
              "endedAt": "2026-10-18T10:15:04.000Z",
              "status": "%s",
              "steps": [
            %s
              ]
            }
            """.formatted(name, status, steps), StandardCharsets.UTF_8);
        return run;
    }
}
