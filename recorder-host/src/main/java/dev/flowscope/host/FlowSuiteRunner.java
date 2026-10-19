package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Replays a `.suite` against one device, reusing {@link FlowTestRunner} unchanged for each test.
 *
 * <p>Every referenced `.flow` is resolved and parsed <em>before</em> the device is touched: a typo
 * anywhere must fail the whole suite as invalid input, with no partial mutation of the device.
 * Otherwise the exit code would depend on how far the run happened to get, and the CLI is a
 * documented API.
 */
public final class FlowSuiteRunner {
    private static final DateTimeFormatter WALL_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final AdbClient adb;
    private final Device device;
    private final FlowSuite suite;
    private final String suiteFileLabel;
    private final String projectRootLabel;
    private final List<Resolved> resolved;
    private final Path root;
    private Consumer<Progress> listener = progress -> {};

    /** Progress for a UI: one event when a test starts and one when it finishes. */
    public record Progress(int number, int total, String reference, String status) {}

    public record TestOutcome(int number, int line, String reference, String name, String status,
                              long durationMillis, String report, String error,
                              List<String> teardownErrors) {
        /** A test with clean teardown, or one that never got far enough to have any. */
        public TestOutcome(int number, int line, String reference, String name, String status,
            long durationMillis, String report, String error) {
            this(number, line, reference, name, status, durationMillis, report, error, List.of());
        }
    }

    public record SuiteReport(String name, String suiteFile, String projectRoot,
                              String deviceSerial, boolean continueOnFailure,
                              Instant startedAt, Instant endedAt, List<TestOutcome> tests) {}

    public record Totals(int total, int passed, int failed, int skipped) {}

    private record Resolved(FlowSuite.Entry entry, FlowTest test, String directoryName) {}

    public FlowSuiteRunner(AdbClient adb, Device device, FlowSuite suite, Path suiteFile,
        FlowProject project, Path outputRoot) throws IOException {
        this.adb = adb;
        this.device = device;
        this.suite = suite;
        this.suiteFileLabel = project != null
            ? project.relativize(suiteFile)
            : suiteFile.toAbsolutePath().normalize().toString();
        this.projectRootLabel = project != null ? project.root().toString() : null;
        this.resolved = resolve(suite, suiteFile, project);
        this.root = RunDirectories.create(outputRoot, suite.name());
    }

    /**
     * Resolves and parses every entry up front. A reference may be unresolvable or a referenced
     * test may be invalid; both are input errors, so both propagate before anything runs.
     */
    private static List<Resolved> resolve(FlowSuite suite, Path suiteFile, FlowProject project)
        throws IOException {
        List<Resolved> result = new ArrayList<>();
        for (int index = 0; index < suite.entries().size(); index++) {
            FlowSuite.Entry entry = suite.entries().get(index);
            Path testFile;
            try {
                testFile = project != null
                    ? project.resolveSuiteEntry(entry.reference())
                    : resolveWithoutProject(suiteFile, entry.reference());
            } catch (IllegalArgumentException unresolvable) {
                throw new IllegalArgumentException(
                    suiteFile.getFileName() + " line " + entry.line() + ": "
                        + unresolvable.getMessage());
            }
            FlowTest test;
            try {
                test = FlowTestParser.parse(testFile);
            } catch (FlowTestParser.ParseError invalid) {
                throw new IllegalArgumentException(
                    entry.reference() + ": " + invalid.getMessage());
            }
            result.add(new Resolved(entry, test,
                String.format("%02d-%s", index + 1, RunDirectories.sanitize(test.name()))));
        }
        return result;
    }

    /**
     * With no project, a suite's references resolve against its own grandparent — `flows/suites/`
     * implies `flows/` — so a suite stays validatable and runnable on its own, the same way a
     * `.flow` is.
     */
    private static Path resolveWithoutProject(Path suiteFile, String reference) {
        Path suites = suiteFile.toAbsolutePath().normalize().getParent();
        Path flows = suites == null ? null : suites.getParent();
        if (flows == null) throw new IllegalArgumentException("test not found: " + reference);
        Path candidate = flows.resolve(reference).normalize();
        if (!candidate.startsWith(flows)) {
            throw new IllegalArgumentException("path escapes the flows directory: " + reference);
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("test not found: " + reference);
        }
        return candidate;
    }

    public FlowSuiteRunner observe(Consumer<Progress> listener) {
        this.listener = listener == null ? progress -> {} : listener;
        return this;
    }

    public Path root() {
        return root;
    }

    public boolean run() throws Exception {
        Instant started = Instant.now();
        List<TestOutcome> outcomes = new ArrayList<>();
        int total = resolved.size();
        boolean stopped = false;

        for (int index = 0; index < total; index++) {
            Resolved current = resolved.get(index);
            int number = index + 1;
            if (stopped) {
                outcomes.add(new TestOutcome(number, current.entry().line(),
                    current.entry().reference(), current.test().name(), "skipped", 0,
                    null, null));
                continue;
            }

            System.out.printf("TEST %02d/%02d  %s%n", number, total, current.entry().reference());
            listener.accept(new Progress(number, total, current.entry().reference(), "running"));

            Instant testStarted = Instant.now();
            boolean passed;
            String error = null;
            List<String> teardownErrors = List.of();
            FlowTestRunner runner =
                new FlowTestRunner(adb, device, current.test(), root, current.directoryName());
            try {
                passed = runner.run();
                // A failed step assertion returns false rather than throwing, so the reason has to
                // be read back from the runner or the aggregate report would say only "failed".
                if (!passed) error = runner.failureMessage();
                // Read unconditionally: a test whose body passed but whose cleanup failed is already
                // `failed`, and a leaked fixture must reach the aggregate report either way.
                teardownErrors = runner.teardownFailures();
            } catch (Exception failure) {
                // The device or ADB died mid-test. That is this test's failure, and
                // continue-on-failure still decides whether the suite goes on.
                passed = false;
                error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            }
            long elapsed = Duration.between(testStarted, Instant.now()).toMillis();
            String status = passed ? "passed" : "failed";
            outcomes.add(new TestOutcome(number, current.entry().line(),
                current.entry().reference(), current.test().name(), status, elapsed,
                current.directoryName() + "/report.json", error, teardownErrors));

            System.out.printf("DONE %02d/%02d  %s · %s · %.1fs%n",
                number, total, status, current.test().name(), elapsed / 1000.0);
            listener.accept(new Progress(number, total, current.entry().reference(), status));

            if (!passed && !suite.continueOnFailure()) stopped = true;
        }

        Instant ended = Instant.now();
        SuiteReport report = new SuiteReport(suite.name(), suiteFileLabel, projectRootLabel,
            device.serial(), suite.continueOnFailure(), started, ended, List.copyOf(outcomes));
        Files.writeString(root.resolve("suite-report.json"), reportJson(report),
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("suite-report.html"), reportHtml(report),
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("junit.xml"), junitXml(report), StandardCharsets.UTF_8);

        Totals totals = totals(outcomes);
        boolean allPassed = totals.failed() == 0 && totals.skipped() == 0;
        System.out.printf("SUITE %s · %d passed · %d failed · %d skipped · %d total%n",
            allPassed ? "passed" : "failed",
            totals.passed(), totals.failed(), totals.skipped(), totals.total());
        return allPassed;
    }

    static Totals totals(List<TestOutcome> outcomes) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (TestOutcome outcome : outcomes) {
            switch (outcome.status()) {
                case "passed" -> passed++;
                case "failed" -> failed++;
                default -> skipped++;
            }
        }
        return new Totals(outcomes.size(), passed, failed, skipped);
    }

    /**
     * Deterministic by contract — CI diffs these. Paths are relative so the bundle stays movable;
     * `projectRoot` is the one absolute path.
     */
    static String reportJson(SuiteReport report) {
        Totals totals = totals(report.tests());
        boolean allPassed = totals.failed() == 0 && totals.skipped() == 0;
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"schemaVersion\": 2,\n");
        out.append("  \"kind\": \"suite\",\n");
        out.append("  \"name\": ").append(Json.quote(report.name())).append(",\n");
        out.append("  \"suiteFile\": ").append(Json.quote(report.suiteFile())).append(",\n");
        out.append("  \"projectRoot\": ").append(Json.quote(report.projectRoot())).append(",\n");
        out.append("  \"deviceSerial\": ").append(Json.quote(report.deviceSerial())).append(",\n");
        out.append("  \"continueOnFailure\": ").append(report.continueOnFailure()).append(",\n");
        out.append("  \"startedAt\": ").append(Json.quote(WALL_TIME.format(report.startedAt())))
            .append(",\n");
        out.append("  \"endedAt\": ").append(Json.quote(WALL_TIME.format(report.endedAt())))
            .append(",\n");
        out.append("  \"status\": \"").append(allPassed ? "passed" : "failed").append("\",\n");
        out.append("  \"totals\": {\"total\": ").append(totals.total())
            .append(", \"passed\": ").append(totals.passed())
            .append(", \"failed\": ").append(totals.failed())
            .append(", \"skipped\": ").append(totals.skipped()).append("},\n");
        out.append("  \"tests\": [");
        for (int index = 0; index < report.tests().size(); index++) {
            TestOutcome test = report.tests().get(index);
            out.append(index == 0 ? "\n" : ",\n");
            out.append("    {\"number\": ").append(test.number())
                .append(", \"line\": ").append(test.line())
                .append(", \"reference\": ").append(Json.quote(test.reference()))
                .append(", \"name\": ").append(Json.quote(test.name()))
                .append(", \"status\": ").append(Json.quote(test.status()))
                .append(", \"durationMillis\": ").append(test.durationMillis())
                .append(", \"report\": ").append(Json.quote(test.report()))
                .append(", \"error\": ").append(Json.quote(test.error()))
                .append(", \"teardownErrors\": ").append(jsonStrings(test.teardownErrors()))
                .append("}");
        }
        out.append(report.tests().isEmpty() ? "]\n" : "\n  ]\n");
        out.append("}\n");
        return out.toString();
    }

    static String reportHtml(SuiteReport report) {
        Totals totals = totals(report.tests());
        StringBuilder rows = new StringBuilder();
        for (TestOutcome test : report.tests()) {
            rows.append("<tr><td>").append(test.number()).append("</td><td>")
                .append(html(test.name())).append("</td><td class=\"").append(test.status())
                .append("\">").append(test.status()).append("</td><td>")
                .append(test.durationMillis()).append(" ms</td><td>");
            if (test.report() != null) {
                rows.append("<a href=\"").append(html(test.report().replace("report.json", "report.html")))
                    .append("\">Open report</a>");
            }
            rows.append("</td><td>").append(html(test.error() == null ? "" : test.error()))
                .append("</td></tr>");
        }
        return "<!doctype html><meta charset=\"utf-8\"><title>" + html(report.name())
            + " · FlowScope</title><style>body{font:14px system-ui;margin:32px;color:#171b22}"
            + ".passed{color:#147a50}.failed{color:#c9323c}.skipped{color:#687382}"
            + "table{border-collapse:collapse;width:100%;margin-top:24px}th,td{padding:9px;"
            + "border-bottom:1px solid #dae0e8;text-align:left}</style><h1>"
            + html(report.name()) + "</h1><p>" + totals.passed() + " passed · "
            + totals.failed() + " failed · " + totals.skipped() + " skipped</p><table><tr>"
            + "<th>#</th><th>Test</th><th>Status</th><th>Duration</th><th>Report</th>"
            + "<th>Error</th></tr>" + rows + "</table>";
    }

    static String junitXml(SuiteReport report) {
        Totals totals = totals(report.tests());
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<testsuite name=\"").append(xml(report.name())).append("\" tests=\"")
            .append(totals.total()).append("\" failures=\"").append(totals.failed())
            .append("\" skipped=\"").append(totals.skipped()).append("\">\n");
        for (TestOutcome test : report.tests()) {
            out.append("  <testcase name=\"").append(xml(test.name())).append("\" classname=\"")
                .append(xml(test.reference())).append("\" time=\"")
                .append(test.durationMillis() / 1000.0).append("\">");
            if (test.status().equals("failed")) {
                // `error` is the test's own failureMessage(), which already names the phase and every
                // cleanup failure. The phase-by-phase breakdown stays in the per-test junit.xml this
                // row links to; repeating it here would say the same thing twice.
                out.append("<failure message=\"").append(xml(test.error())).append("\">")
                    .append(xml(test.error())).append("</failure>");
            } else if (test.status().equals("skipped")) out.append("<skipped/>");
            out.append("</testcase>\n");
        }
        return out.append("</testsuite>\n").toString();
    }

    /** A JSON array of strings, quoted by the same strict writer as every other report field. */
    private static String jsonStrings(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) out.append(", ");
            out.append(Json.quote(values.get(index)));
        }
        return out.append("]").toString();
    }

    private static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static String xml(String value) {
        return html(value == null ? "" : value).replace("'", "&apos;");
    }
}
