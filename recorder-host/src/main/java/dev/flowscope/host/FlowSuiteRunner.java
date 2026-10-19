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
import java.util.Map;
import java.util.UUID;
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
    private final Path sourceDirectory;
    private final FlowHttpClient http;
    private final Map<String, String> environment;
    private final Map<String, String> secretSources;
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

    public record HookOutcome(String phase, int line, String action, String status,
                              long durationMillis, String error, Map<String, String> details) {}

    public record SuiteReport(String name, String suiteFile, String projectRoot,
                              String deviceSerial, boolean continueOnFailure,
                              Instant startedAt, Instant endedAt, List<TestOutcome> tests,
                              List<HookOutcome> hooks) {
        public SuiteReport(String name, String suiteFile, String projectRoot, String deviceSerial,
            boolean continueOnFailure, Instant startedAt, Instant endedAt,
            List<TestOutcome> tests) {
            this(name, suiteFile, projectRoot, deviceSerial, continueOnFailure, startedAt, endedAt,
                tests, List.of());
        }
    }

    public record Totals(int total, int passed, int failed, int skipped) {}

    private record Resolved(FlowSuite.Entry entry, FlowTest test, String directoryName) {}

    public FlowSuiteRunner(AdbClient adb, Device device, FlowSuite suite, Path suiteFile,
        FlowProject project, Path outputRoot) throws IOException {
        this(adb, device, suite, suiteFile, project, outputRoot, null);
    }

    public FlowSuiteRunner(AdbClient adb, Device device, FlowSuite suite, Path suiteFile,
        FlowProject project, Path outputRoot, Path secretsFile) throws IOException {
        this(adb, device, suite, suiteFile, project, outputRoot, FlowHttpClient.platform(),
            System.getenv(), SecretFile.load(secretsFile, System.getenv()));
    }

    FlowSuiteRunner(AdbClient adb, Device device, FlowSuite suite, Path suiteFile,
        FlowProject project, Path outputRoot, FlowHttpClient http,
        Map<String, String> environment, Map<String, String> secretSources) throws IOException {
        this.adb = adb;
        this.device = device;
        this.suite = suite;
        this.suiteFileLabel = project != null
            ? project.relativize(suiteFile)
            : suiteFile.toAbsolutePath().normalize().toString();
        this.projectRootLabel = project != null ? project.root().toString() : null;
        this.resolved = resolve(suite, suiteFile, project);
        this.root = RunDirectories.create(outputRoot, suite.name());
        this.sourceDirectory = suiteFile.toAbsolutePath().normalize().getParent();
        this.http = http;
        this.environment = Map.copyOf(environment);
        this.secretSources = Map.copyOf(secretSources);
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
        Map<String, String> suiteBuiltins = Map.of("id", UUID.randomUUID().toString(),
            "name", suite.name(), "startedAt", started.toString());
        RuntimeValues runtimeValues = new RuntimeValues(environment, secretSources, Map.of(),
            suite.name(), device.serial(), started, UUID.randomUUID(), suiteBuiltins);
        HostRuntime hostRuntime = new HostRuntime(runtimeValues, http, sourceDirectory);
        for (FlowTest.Requirement requirement : suite.requirements()) runtimeValues.require(requirement);
        for (FlowTest.Step hook : suite.hooks()) if (hook.action().equals("secret")) {
            runtimeValues.bindSecret(hook.arguments().get("name"), hook.arguments().get("source"));
        }
        for (FlowTest.Step hook : suite.hooks()) for (String value : hook.arguments().values()) {
            runtimeValues.preflightReferences(value);
        }
        for (FlowTest.Step hook : suite.hooks()) if (hook.arguments().containsKey("body-file")) {
            String raw = hook.arguments().get("body-file");
            if (raw.contains("${var.") || raw.contains("${suite.var.")) {
                throw new IllegalArgumentException("body-file path cannot use runtime variables");
            }
            Path file = hostRuntime.resolveRelativeFile(runtimeValues.interpolate(raw));
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("API body file not found: " + raw);
            }
            runtimeValues.preflightReferences(Files.readString(file, StandardCharsets.UTF_8));
        }
        List<HookOutcome> hooks = new ArrayList<>();
        boolean setupPassed = runHooks(FlowTest.Phase.BEFORE, true, runtimeValues, hostRuntime, hooks);
        List<TestOutcome> outcomes = new ArrayList<>();
        int total = resolved.size();
        boolean stopped = !setupPassed;

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
                new FlowTestRunner(adb, device, current.test(), root, current.directoryName(), http,
                    environment, secretSources, runtimeValues.variables(), suiteBuiltins);
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

        boolean cleanupPassed = runHooks(FlowTest.Phase.AFTER, false, runtimeValues, hostRuntime, hooks);

        Instant ended = Instant.now();
        SuiteReport report = new SuiteReport(suite.name(), suiteFileLabel, projectRootLabel,
            device.serial(), suite.continueOnFailure(), started, ended, List.copyOf(outcomes),
            List.copyOf(hooks));
        Files.writeString(root.resolve("suite-report.json"), reportJson(report),
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("suite-report.html"), reportHtml(report),
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("junit.xml"), junitXml(report), StandardCharsets.UTF_8);

        Totals totals = totals(outcomes);
        boolean allPassed = setupPassed && cleanupPassed && totals.failed() == 0 && totals.skipped() == 0;
        System.out.printf("SUITE %s · %d passed · %d failed · %d skipped · %d total%n",
            allPassed ? "passed" : "failed",
            totals.passed(), totals.failed(), totals.skipped(), totals.total());
        return allPassed;
    }

    private boolean runHooks(FlowTest.Phase phase, boolean stopOnFailure, RuntimeValues values,
        HostRuntime runtime, List<HookOutcome> outcomes) {
        boolean passed = true, stopped = false;
        for (FlowTest.Step hook : suite.hooks()) {
            if (hook.phase() != phase) continue;
            if (stopped) {
                outcomes.add(new HookOutcome(phase.value(), hook.line(), hook.action(), "skipped",
                    0, "earlier " + phase.value() + " hook failed", Map.of()));
                continue;
            }
            if (!values.conditionsPass(hook.arguments())) {
                outcomes.add(new HookOutcome(phase.value(), hook.line(), hook.action(), "skipped",
                    0, "condition not met", Map.of()));
                continue;
            }
            long started = System.nanoTime();
            try {
                Map<String, String> expanded = runtime.expanded(hook.arguments());
                long timeout = expanded.containsKey("timeout")
                    ? FlowTestParser.parsePositiveLong(expanded.get("timeout"), hook.line(), "timeout")
                    : 10_000;
                Map<String, String> details = runtime.execute(hook.action(), hook.arguments(), timeout);
                outcomes.add(new HookOutcome(phase.value(), hook.line(), hook.action(), "passed",
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), null, details));
            } catch (Exception failure) {
                passed = false;
                String message = values.redact(failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
                outcomes.add(new HookOutcome(phase.value(), hook.line(), hook.action(), "failed",
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), message, Map.of()));
                if (stopOnFailure) stopped = true;
            }
        }
        return passed;
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
        boolean hooksPassed = report.hooks().stream().noneMatch(hook -> hook.status().equals("failed"));
        boolean allPassed = hooksPassed && totals.failed() == 0 && totals.skipped() == 0;
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
        if (!report.hooks().isEmpty()) {
            out.append("  \"hooks\": [");
            for (int index = 0; index < report.hooks().size(); index++) {
                HookOutcome hook = report.hooks().get(index);
                if (index > 0) out.append(',');
                out.append("{\"phase\":").append(Json.quote(hook.phase()))
                    .append(",\"line\":").append(hook.line())
                    .append(",\"action\":").append(Json.quote(hook.action()))
                    .append(",\"status\":").append(Json.quote(hook.status()))
                    .append(",\"durationMillis\":").append(hook.durationMillis())
                    .append(",\"error\":").append(Json.quote(hook.error()))
                    .append(",\"details\":").append(jsonMap(hook.details())).append('}');
            }
            out.append("],\n");
        }
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
        StringBuilder hookRows = new StringBuilder();
        for (HookOutcome hook : report.hooks()) hookRows.append("<tr><td>")
            .append(html(hook.phase())).append("</td><td>").append(html(hook.action()))
            .append("</td><td class=\"").append(hook.status()).append("\">")
            .append(hook.status()).append("</td><td>")
            .append(html(hook.error() == null ? "" : hook.error())).append("</td></tr>");
        String hooks = report.hooks().isEmpty() ? "" : "<h2>Suite lifecycle</h2><table><tr>"
            + "<th>Phase</th><th>Action</th><th>Status</th><th>Error</th></tr>" + hookRows + "</table>";
        return "<!doctype html><meta charset=\"utf-8\"><title>" + html(report.name())
            + " · FlowScope</title><style>body{font:14px system-ui;margin:32px;color:#171b22}"
            + ".passed{color:#147a50}.failed{color:#c9323c}.skipped{color:#687382}"
            + "table{border-collapse:collapse;width:100%;margin-top:24px}th,td{padding:9px;"
            + "border-bottom:1px solid #dae0e8;text-align:left}</style><h1>"
            + html(report.name()) + "</h1><p>" + totals.passed() + " passed · "
            + totals.failed() + " failed · " + totals.skipped() + " skipped</p><table><tr>"
            + "<th>#</th><th>Test</th><th>Status</th><th>Duration</th><th>Report</th>"
            + "<th>Error</th></tr>" + rows + "</table>" + hooks;
    }

    static String junitXml(SuiteReport report) {
        Totals totals = totals(report.tests());
        List<HookOutcome> failedHooks = report.hooks().stream()
            .filter(hook -> hook.status().equals("failed")).toList();
        int lifecycleCases = report.hooks().isEmpty() ? 0 : 1;
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<testsuite name=\"").append(xml(report.name())).append("\" tests=\"")
            .append(totals.total() + lifecycleCases).append("\" failures=\"")
            .append(totals.failed() + (failedHooks.isEmpty() ? 0 : 1))
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
        if (lifecycleCases == 1) {
            out.append("  <testcase name=\"Suite lifecycle\" classname=\"")
                .append(xml(report.suiteFile())).append("\">");
            if (!failedHooks.isEmpty()) {
                String message = failedHooks.stream().map(hook -> hook.phase() + ": " + hook.action()
                    + ": " + hook.error()).collect(java.util.stream.Collectors.joining(" · "));
                out.append("<failure message=\"").append(xml(message)).append("\">")
                    .append(xml(message)).append("</failure>");
            }
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

    private static String jsonMap(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{"); int index = 0;
        for (var entry : values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (index++ > 0) out.append(',');
            out.append(Json.quote(entry.getKey())).append(':').append(Json.quote(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static String xml(String value) {
        return html(value == null ? "" : value).replace("'", "&apos;");
    }
}
