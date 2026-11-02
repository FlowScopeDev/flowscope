package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowSuiteReportTest {
    /** These reports name evidence that was never written, which is the fallback path. */
    @TempDir Path temp;
    private static final Instant STARTED = Instant.parse("2026-10-18T10:15:00.123Z");
    private static final Instant ENDED = Instant.parse("2026-10-18T10:19:42.004Z");

    @Test
    void writesTheDocumentedShapeExactly() {
        String json = FlowSuiteRunner.reportJson(new FlowSuiteRunner.SuiteReport(
            "Nightly regression", "flows/suites/nightly.suite", "/Users/me/app", "TESTSERIAL01",
            true, STARTED, ENDED,
            List.of(
                new FlowSuiteRunner.TestOutcome(1, 6, "tests/login.flow", "Login smoke",
                    "passed", 3210, "01-Login_smoke/report.json", null),
                new FlowSuiteRunner.TestOutcome(2, 7, "tests/terms.flow", "Terms and continue",
                    "failed", 18044, "02-Terms_and_continue/report.json",
                    "Element did not become visible: [text=Welcome]",
                    List.of("force-stop: device not found")))));

        assertEquals("""
            {
              "schemaVersion": 2,
              "kind": "suite",
              "name": "Nightly regression",
              "suiteFile": "flows/suites/nightly.suite",
              "projectRoot": "/Users/me/app",
              "deviceSerial": "TESTSERIAL01",
              "continueOnFailure": true,
              "startedAt": "2026-10-18T10:15:00.123Z",
              "endedAt": "2026-10-18T10:19:42.004Z",
              "status": "failed",
              "totals": {"total": 2, "passed": 1, "failed": 1, "skipped": 0},
              "tests": [
                {"number": 1, "line": 6, "reference": "tests/login.flow", "name": "Login smoke",\
             "status": "passed", "durationMillis": 3210,\
             "report": "01-Login_smoke/report.json", "error": null, "teardownErrors": [],\
             "screenshots": []},
                {"number": 2, "line": 7, "reference": "tests/terms.flow",\
             "name": "Terms and continue", "status": "failed", "durationMillis": 18044,\
             "report": "02-Terms_and_continue/report.json",\
             "error": "Element did not become visible: [text=Welcome]",\
             "teardownErrors": ["force-stop: device not found"], "screenshots": []}
              ]
            }
            """, json);
    }

    @Test
    void aSuiteWhereEverythingPassedIsPassed() {
        String json = FlowSuiteRunner.reportJson(new FlowSuiteRunner.SuiteReport(
            "Smoke", "flows/suites/smoke.suite", "/app", "ABC", false, STARTED, ENDED,
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                "01-A/report.json", null))));
        assertEquals(true, json.contains("\"status\": \"passed\""));
    }

    @Test
    void skippedTestsKeepTheSuiteFailedAndAreCounted() {
        String json = FlowSuiteRunner.reportJson(new FlowSuiteRunner.SuiteReport(
            "Stop early", "s.suite", "/app", "ABC", false, STARTED, ENDED,
            List.of(
                new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "failed", 10,
                    "01-A/report.json", "boom"),
                new FlowSuiteRunner.TestOutcome(2, 4, "tests/b.flow", "B", "skipped", 0,
                    null, null))));
        assertEquals(true, json.contains("\"status\": \"failed\""));
        assertEquals(true,
            json.contains("\"totals\": {\"total\": 2, \"passed\": 0, \"failed\": 1, \"skipped\": 1}"));
        assertEquals(true, json.contains("\"report\": null"));
    }

    @Test
    void aNullProjectRootIsWrittenAsNull() {
        String json = FlowSuiteRunner.reportJson(new FlowSuiteRunner.SuiteReport(
            "No project", "/abs/s.suite", null, "ABC", false, STARTED, ENDED,
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                "01-A/report.json", null))));
        assertEquals(true, json.contains("\"projectRoot\": null"));
    }

    @Test
    void countsTotals() {
        FlowSuiteRunner.Totals totals = FlowSuiteRunner.totals(List.of(
            outcome("passed"), outcome("passed"), outcome("failed"), outcome("skipped")));
        assertEquals(new FlowSuiteRunner.Totals(4, 2, 1, 1), totals);
        assertEquals(new FlowSuiteRunner.Totals(0, 0, 0, 0), FlowSuiteRunner.totals(List.of()));
    }

    @Test
    void htmlAndJUnitEscapeContentAndLinkPerTestReports() {
        FlowSuiteRunner.SuiteReport report = new FlowSuiteRunner.SuiteReport(
            "Smoke & regression", "flows/suites/smoke.suite", "/app", "ABC", false,
            STARTED, ENDED, List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow",
                "A <test>", "failed", 10, "01-A/report.json", "bad <state>")));

        String html = FlowSuiteRunner.reportHtml(report, temp);
        String junit = FlowSuiteRunner.junitXml(report);
        assertEquals(true, html.contains("A &lt;test&gt;"));
        assertEquals(true, html.contains("01-A/report.html"));
        assertEquals(true, junit.contains("Smoke &amp; regression"));
        assertEquals(true, junit.contains("bad &lt;state&gt;"));
    }

    @Test
    void cleanupFailuresReachEverySuiteArtefactNotJustTheTestReport() {
        FlowSuiteRunner.SuiteReport report = new FlowSuiteRunner.SuiteReport(
            "Nightly", "flows/suites/n.suite", "/app", "ABC", false, STARTED, ENDED,
            // `error` is what the runner stores: FlowTestRunner.failureMessage(), already composed.
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "failed", 10,
                "01-A/report.json",
                "tap: no element matched. Cleanup also failed (1): api-request: expected 204, got 500",
                List.of("api-request: expected 204, got 500"))));

        // The structured array is for a CI script, which should not have to parse a sentence.
        assertEquals(true, FlowSuiteRunner.reportJson(report)
            .contains("\"teardownErrors\": [\"api-request: expected 204, got 500\"]"));
        // The human-facing surfaces carry it inside the verdict, which already names the phase, and
        // link to the per-test report for the breakdown — so neither says it twice.
        String junit = FlowSuiteRunner.junitXml(report);
        assertEquals(true, junit.contains("Cleanup also failed (1)"));
        // The per-test report linked from this row holds the phase-by-phase breakdown. The aggregate
        // must not restate it, or every cell says the same thing twice.
        assertEquals(false, junit.contains("cleanup: api-request"));
        assertEquals(true, FlowSuiteRunner.reportHtml(report, temp).contains("Cleanup also failed (1)"));
    }

    @Test
    void screenshotsSerializeAsAJsonArrayOfRelativePaths() {
        String json = FlowSuiteRunner.reportJson(new FlowSuiteRunner.SuiteReport(
            "Smoke", "flows/suites/smoke.suite", "/app", "ABC", false, STARTED, ENDED,
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                "01-A/report.json", null, List.of(),
                List.of("01-A/evidence/step-01.png", "01-A/evidence/step-02.png")))));
        assertEquals(true, json.contains(
            "\"screenshots\": [\"01-A/evidence/step-01.png\", \"01-A/evidence/step-02.png\"]"));
    }

    @Test
    void aScreenshotThatCannotBeReadKeepsItsRelativeLinkRatherThanBreakingTheRun() {
        FlowSuiteRunner.SuiteReport report = new FlowSuiteRunner.SuiteReport(
            "Smoke", "flows/suites/smoke.suite", "/app", "ABC", false, STARTED, ENDED,
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                "01-A/report.json", null, List.of(), List.of("01-A/evidence/step-01.png"))));

        // Nothing was written under `temp`, so there is nothing to embed: the thumbnail falls back
        // to the relative file, which still works for a reader who has the run folder.
        String html = FlowSuiteRunner.reportHtml(report, temp);
        assertEquals(true, html.contains("<img src=\"01-A/evidence/step-01.png\""));
        assertEquals(true, html.contains(">step-01<"));
        // Its thumbnail opens the gallery overlay rather than dropping the reader on a bare file.
        assertEquals(true, html.contains("<a class=\"frame\" href=\"#screenshots-1\">"));
    }

    @Test
    void suiteReportHtmlOmitsTheGalleryRowWhenThereAreNoScreenshots() {
        FlowSuiteRunner.SuiteReport report = new FlowSuiteRunner.SuiteReport(
            "Smoke", "flows/suites/smoke.suite", "/app", "ABC", false, STARTED, ENDED,
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                "01-A/report.json", null)));

        assertEquals(false, FlowSuiteRunner.reportHtml(report, temp).contains("class=\"screenshots\""));
    }

    @Test
    void theSuiteGalleryCollectsEveryTestsScreenshotsAndNamesTheTestThatTookThem() {
        FlowSuiteRunner.SuiteReport report = new FlowSuiteRunner.SuiteReport(
            "Smoke", "flows/suites/smoke.suite", "/app", "ABC", false, STARTED, ENDED,
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                    "01-A/report.json", null, List.of(), List.of("01-A/evidence/step-01.png")),
                new FlowSuiteRunner.TestOutcome(2, 4, "tests/b.flow", "B", "failed", 10,
                    "02-B/report.json", "tap: no element matched", List.of(),
                    List.of("02-B/evidence/failure-02.png"))));

        String html = FlowSuiteRunner.reportHtml(report, temp);
        assertEquals(true, html.contains("<h2>Screenshots</h2>"));
        assertEquals(true, html.contains("2 captures"));
        assertEquals(true, html.contains("<b>Test 1</b> A"));
        assertEquals(true, html.contains("<b>Test 2</b> B"));
        // The per-test strip stays beside each row; the gallery is an addition, not a replacement.
        assertEquals(true, html.contains("class=\"screenshots\""));
        // Its thumbnails open the gallery's overlay, so prev/next works from either entry point.
        assertEquals(true, html.contains("<a class=\"frame\" href=\"#screenshots-1\">"));
        assertEquals(true, html.contains("<a class=\"frame\" href=\"#screenshots-2\">"));
    }

    @Test
    void everyStripThumbnailPointsAtItsOwnPositionInTheGallery() {
        FlowSuiteRunner.SuiteReport report = new FlowSuiteRunner.SuiteReport(
            "Smoke", "flows/suites/smoke.suite", "/app", "ABC", true, STARTED, ENDED,
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                    "01-A/report.json", null, List.of(),
                    List.of("01-A/evidence/step-01.png", "01-A/evidence/step-02.png")),
                // A test that captured nothing must not consume a gallery position.
                new FlowSuiteRunner.TestOutcome(2, 4, "tests/b.flow", "B", "skipped", 0, null, null),
                new FlowSuiteRunner.TestOutcome(3, 5, "tests/c.flow", "C", "passed", 10,
                    "03-C/report.json", null, List.of(), List.of("03-C/evidence/step-01.png"))));

        String html = FlowSuiteRunner.reportHtml(report, temp);
        int first = html.indexOf("01-A/evidence/step-01.png");
        assertEquals(true, html.contains("<a class=\"frame\" href=\"#screenshots-1\">"));
        assertEquals(true, html.contains("<a class=\"frame\" href=\"#screenshots-2\">"));
        assertEquals(true, html.contains("<a class=\"frame\" href=\"#screenshots-3\">"));
        assertEquals(false, html.contains("#screenshots-4"));
        assertEquals(true, first > 0);
    }

    @Test
    void twoTestsThatCapturedTheSamePathStillOpenTheirOwnOverlay() {
        FlowSuiteRunner.SuiteReport report = new FlowSuiteRunner.SuiteReport(
            "Smoke", "flows/suites/smoke.suite", "/app", "ABC", true, STARTED, ENDED,
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                    "01-A/report.json", null, List.of(), List.of("shared/evidence/cart.png")),
                new FlowSuiteRunner.TestOutcome(2, 4, "tests/b.flow", "B", "failed", 10,
                    "02-B/report.json", "tap: no element matched", List.of(),
                    List.of("shared/evidence/cart.png"))));

        String html = FlowSuiteRunner.reportHtml(report, temp);
        // Positional, not keyed by path: the second row must not jump back to the first overlay.
        assertEquals(true, html.contains("<a class=\"frame\" href=\"#screenshots-1\">"));
        assertEquals(true, html.contains("<a class=\"frame\" href=\"#screenshots-2\">"));
        assertEquals(true, html.contains("<b>Test 2</b> B"));
    }

    @Test
    void aSuiteThatCapturedNothingStillCarriesTheLabelledSection() {
        FlowSuiteRunner.SuiteReport report = new FlowSuiteRunner.SuiteReport(
            "Smoke", "flows/suites/smoke.suite", "/app", "ABC", false, STARTED, ENDED,
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                "01-A/report.json", null)));

        String html = FlowSuiteRunner.reportHtml(report, temp);
        assertEquals(true, html.contains("<h2>Screenshots</h2>"));
        assertEquals(true, html.contains("This run captured no screenshots."));
        assertEquals(false, html.contains("<img"));
    }

    @Test
    void aFailedSuiteHookIsNamedInTheVerdictRatherThanOnlyInItsOwnTable() {
        FlowSuiteRunner.SuiteReport report = new FlowSuiteRunner.SuiteReport(
            "Smoke", "flows/suites/smoke.suite", "/app", "ABC", false, STARTED, ENDED,
            List.of(new FlowSuiteRunner.TestOutcome(1, 3, "tests/a.flow", "A", "passed", 10,
                "01-A/report.json", null)),
            List.of(new FlowSuiteRunner.HookOutcome("after", 9, "api-request", "failed", 12,
                "expected 204, got 500", java.util.Map.of())));

        String html = FlowSuiteRunner.reportHtml(report, temp);
        assertEquals(true, html.contains("suite after hook failed: api-request: expected 204, got 500"));
        assertEquals(true, html.contains("head failed"));
    }

    private static FlowSuiteRunner.TestOutcome outcome(String status) {
        return new FlowSuiteRunner.TestOutcome(1, 1, "tests/a.flow", "A", status, 0, null, null);
    }
}
