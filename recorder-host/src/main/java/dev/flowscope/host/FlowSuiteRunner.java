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
    private boolean keepHierarchy;
    private RunCancellation cancellation = new RunCancellation();
    public FlowSuiteRunner cancellation(RunCancellation signal) {
        this.cancellation = java.util.Objects.requireNonNull(signal); return this;
    }
    public boolean isCancelled() { return cancellation.isRequested(); }


    public FlowSuiteRunner keepHierarchy(boolean keep) { this.keepHierarchy = keep; return this; }

    private Consumer<Progress> listener = progress -> {};

    /** Progress for a UI: one event when a test starts and one when it finishes. */
    public record Progress(int number, int total, String reference, String status, String label) {
        public Progress(int number, int total, String reference, String status) { this(number, total, reference, status, ""); }
        public String displayName() { return label == null || label.isBlank() ? reference : label; }
    }

    public record TestOutcome(int number, int line, String reference, String name, String status,
                              long durationMillis, String report, String error,
                              List<String> teardownErrors, List<String> screenshots, String label) {
        public TestOutcome(int number, int line, String reference, String name, String status,
            long durationMillis, String report, String error, List<String> teardownErrors, List<String> screenshots) {
            this(number, line, reference, name, status, durationMillis, report, error, teardownErrors, screenshots, "");
        }
        public String displayName() { return label == null || label.isBlank() ? name : label; }
        /** A test with clean teardown and no screenshots, or one that never got far enough for either. */
        public TestOutcome(int number, int line, String reference, String name, String status,
            long durationMillis, String report, String error) {
            this(number, line, reference, name, status, durationMillis, report, error, List.of(),
                List.of());
        }

        /** A test with screenshot evidence but clean teardown. */
        public TestOutcome(int number, int line, String reference, String name, String status,
            long durationMillis, String report, String error, List<String> teardownErrors) {
            this(number, line, reference, name, status, durationMillis, report, error,
                teardownErrors, List.of());
        }
    }

    public record HookOutcome(String phase, int line, String action, String status,
                              long durationMillis, String error, Map<String, String> details, String label) {
        public HookOutcome(String phase, int line, String action, String status, long durationMillis,
            String error, Map<String, String> details) { this(phase, line, action, status, durationMillis, error, details, ""); }
        public String displayName() { return label == null || label.isBlank() ? action : label; }
    }

    public record SuiteReport(String name, String suiteFile, String projectRoot,
                              String deviceSerial, boolean continueOnFailure,
                              Instant startedAt, Instant endedAt, List<TestOutcome> tests,
                              List<HookOutcome> hooks, boolean cancelled) {
        public SuiteReport(String name, String suiteFile, String projectRoot, String deviceSerial,
            boolean continueOnFailure, Instant startedAt, Instant endedAt,
            List<TestOutcome> tests, List<HookOutcome> hooks) {
            this(name, suiteFile, projectRoot, deviceSerial, continueOnFailure, startedAt, endedAt,
                tests, hooks, false);
        }
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
        FlowFiles.validate(sourceDirectory, suite.hooks());
        for (Resolved child : resolved) FlowFiles.validate(child.test().sourceDirectory(), child.test().steps());
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
        List<RunSteps.Group> plan = new ArrayList<>();
        plan.add(RunSteps.hookGroup(FlowTest.Phase.BEFORE, suite.hooks()));
        for (int i = 0; i < resolved.size(); i++) {
            Resolved child = resolved.get(i);
            plan.add(RunSteps.testGroup(i + 1, child.entry().reference(), child.test(), child.directoryName() + "/report.json", child.entry().label()));
        }
        plan.add(RunSteps.hookGroup(FlowTest.Phase.AFTER, suite.hooks()));
        RunSteps.write(root, plan);
        RunProgress progressFile = RunProgress.start(root, "suite", suite.name(), resolved.size());
        try {
            boolean passed = runWithProgress(started, suiteBuiltins, runtimeValues, hostRuntime,
                progressFile);
            progressFile.finish();
            return passed;
        } finally {
            cancellation.finish();
            progressFile.interrupt();
        }
    }

    private boolean runWithProgress(Instant started, Map<String, String> suiteBuiltins,
        RuntimeValues runtimeValues, HostRuntime hostRuntime, RunProgress progressFile)
        throws Exception {
        List<HookOutcome> hooks = new ArrayList<>();
        boolean setupPassed = runHooks(FlowTest.Phase.BEFORE, true, runtimeValues, hostRuntime, hooks, progressFile);
        List<TestOutcome> outcomes = new ArrayList<>();
        int total = resolved.size();
        boolean stopped = !setupPassed;

        for (int index = 0; index < total; index++) {
            Resolved current = resolved.get(index);
            int number = index + 1;
            if (stopped || isCancelled()) {
                reportProgress(progressFile, new Progress(number, total, current.entry().reference(), "skipped", current.entry().label()));
                outcomes.add(new TestOutcome(number, current.entry().line(),
                    current.entry().reference(), current.test().name(), "skipped", 0,
                    null, null, List.of(), List.of(), current.entry().label()));
                continue;
            }

            System.out.printf("TEST %02d/%02d  %s%n", number, total, current.entry().displayName(current.entry().reference()));
            reportProgress(progressFile, new Progress(number, total, current.entry().reference(), "running", current.entry().label()));

            Instant testStarted = Instant.now();
            boolean passed;
            String error = null;
            List<String> teardownErrors = List.of();
            FlowTestRunner runner =
                new FlowTestRunner(adb, device, current.test(), root, current.directoryName(), http,
                    environment, secretSources, runtimeValues, suiteBuiltins)
                    .withoutProgressFile().cancellation(cancellation).keepHierarchy(keepHierarchy).observe(progressFile::step);
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
                error = runner.redact(failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
            }
            long elapsed = Duration.between(testStarted, Instant.now()).toMillis();
            String status = runner.isCancelled() && runner.primaryFailure() == null ? "cancelled" : passed ? "passed" : "failed";
            List<String> screenshots = screenshotPaths(root, current.directoryName());
            outcomes.add(new TestOutcome(number, current.entry().line(),
                current.entry().reference(), current.test().name(), status, elapsed,
                current.directoryName() + "/report.json", error, teardownErrors, screenshots, current.entry().label()));

            System.out.printf("DONE %02d/%02d  %s · %s · %.1fs%n",
                number, total, status, current.entry().displayName(current.test().name()), elapsed / 1000.0);
            reportProgress(progressFile, new Progress(number, total, current.entry().reference(), status, current.entry().label()));

            if (!passed && !suite.continueOnFailure()) stopped = true;
        }

        boolean cleanupPassed = runHooks(FlowTest.Phase.AFTER, false, runtimeValues, hostRuntime, hooks, progressFile);

        cancellation.finish();
        Instant ended = Instant.now();
        SuiteReport report = new SuiteReport(suite.name(), suiteFileLabel, projectRootLabel,
            device.serial(), suite.continueOnFailure(), started, ended, List.copyOf(outcomes),
            List.copyOf(hooks), isCancelled());
        Files.writeString(root.resolve("suite-report.json"), reportJson(report),
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("suite-report.html"), reportHtml(report, root),
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("junit.xml"), junitXml(report), StandardCharsets.UTF_8);
        writePdf(report, root);

        Totals totals = totals(outcomes);
        boolean allPassed = !isCancelled() && setupPassed && cleanupPassed && totals.failed() == 0 && totals.skipped() == 0;
        System.out.printf("SUITE %s · %d passed · %d failed · %d skipped · %d total%n",
            isCancelled() ? "cancelled" : allPassed ? "passed" : "failed",
            totals.passed(), totals.failed(), totals.skipped(), totals.total());
        return allPassed;
    }

    /**
     * `suite-report.pdf`. Best effort for the same reason the test's own PDF is: the suite's verdict
     * belongs to the tests that ran, not to whether a document could be typeset afterwards.
     */
    private static void writePdf(SuiteReport report, Path root) {
        try {
            Files.write(root.resolve("suite-report.pdf"),
                ReportPdf.render(pdfDoc(report), new ReportImages(root)));
        } catch (Exception unwritable) {
            String cause = unwritable.getMessage() == null
                ? unwritable.getClass().getSimpleName() : unwritable.getMessage();
            System.err.println("WARN could not write suite-report.pdf: " + cause
                + " (suite-report.json, suite-report.html and junit.xml were written)");
        }
    }

    static ReportPdf.Doc pdfDoc(SuiteReport report) {
        Totals totals = totals(report.tests());
        long millis = Duration.between(report.startedAt(), report.endedAt()).toMillis();
        List<ReportHtml.Shot> shots = suiteShots(report);

        List<ReportPdf.Row> rows = new ArrayList<>();
        for (TestOutcome test : report.tests()) {
            StringBuilder error = new StringBuilder(test.error() == null ? "" : test.error());
            for (String cleanup : test.teardownErrors()) {
                if (!error.isEmpty()) error.append(' ');
                error.append("Cleanup: ").append(cleanup);
            }
            rows.add(new ReportPdf.Row(String.valueOf(test.number()),
                test.reference(), test.displayName(), test.status(),
                FlowTestRunner.formatMillis(test.durationMillis()), "", error.toString(),
                test.screenshots().isEmpty() ? null : test.screenshots().get(0)));
        }

        List<ReportPdf.Plate> plates = new ArrayList<>();
        for (ReportHtml.Shot shot : shots) {
            plates.add(new ReportPdf.Plate(shot.path(), shot.title(), shot.where(), shot.what(),
                shot.status()));
        }

        List<ReportPdf.Row> hooks = new ArrayList<>();
        for (HookOutcome hook : report.hooks()) {
            hooks.add(new ReportPdf.Row("", ReportHtml.sentenceCase(hook.phase()),
                hook.displayName(), hook.status(),
                FlowTestRunner.formatMillis(hook.durationMillis()), "",
                hook.error() == null ? "" : hook.error(), null));
        }

        String status = report.cancelled() ? "cancelled"
            : totals.failed() > 0 || totals.skipped() > 0 || report.hooks().stream()
                .anyMatch(hook -> hook.status().equals("failed")) ? "failed" : "passed";
        return new ReportPdf.Doc(report.name(), "Test suite", status, suiteVerdict(report),
            List.of(),
            List.of(new ReportPdf.Count("Passed", "" + totals.passed(), "passed"),
                new ReportPdf.Count("Failed", "" + totals.failed(),
                    totals.failed() > 0 ? "failed" : ""),
                new ReportPdf.Count("Skipped", "" + totals.skipped(),
                    totals.skipped() > 0 ? "skipped" : ""),
                new ReportPdf.Count("Duration", FlowTestRunner.formatMillis(millis), ""),
                new ReportPdf.Count("Screenshots", "" + shots.size(), "")),
            List.of(new ReportPdf.Fact("Suite", report.suiteFile()),
                new ReportPdf.Fact("Project",
                    report.projectRoot() == null ? "" : report.projectRoot()),
                new ReportPdf.Fact("Device", report.deviceSerial()),
                new ReportPdf.Fact("On failure",
                    report.continueOnFailure() ? "Continue" : "Stop"),
                new ReportPdf.Fact("Started", ReportHtml.time(report.startedAt())),
                new ReportPdf.Fact("Ended", ReportHtml.time(report.endedAt()))),
            "Tests", "Each test in suite order, with the screenshots it captured.", rows, plates,
            null, List.of(),
            hooks.isEmpty() ? null : "Suite lifecycle",
            "Host-only setup and cleanup hooks; device state belongs to each test.", hooks);
    }

    private void reportProgress(RunProgress progressFile, Progress progress) {
        progressFile.test(progress);
        listener.accept(progress);
    }

    private boolean runHooks(FlowTest.Phase phase, boolean stopOnFailure, RuntimeValues values,
        HostRuntime runtime, List<HookOutcome> outcomes, RunProgress progressFile) {
        boolean passed = true, stopped = false, readinessChecked = false;
        for (FlowTest.Step hook : suite.hooks()) {
            if (hook.phase() != phase) continue;
            int number = suite.hooks().indexOf(hook) + 1;
            if (stopped || (phase != FlowTest.Phase.AFTER && isCancelled())) {
                passed = false;
                progressFile.hook(number, suite.hooks().size(), hook, "skipped");
                outcomes.add(new HookOutcome(phase.value(), hook.line(), hook.action(), "skipped",
                    0, isCancelled() ? "run cancelled" : "earlier " + phase.value() + " hook failed", Map.of(), hook.label()));
                continue;
            }
            if (!values.conditionsPass(hook.arguments())) {
                progressFile.hook(number, suite.hooks().size(), hook, "skipped");
                outcomes.add(new HookOutcome(phase.value(), hook.line(), hook.action(), "skipped",
                    0, "condition not met", Map.of(), hook.label()));
                continue;
            }
            progressFile.hook(number, suite.hooks().size(), hook, "running");
            long started = System.nanoTime();
            try {
                boolean checkReadiness = phase == FlowTest.Phase.BEFORE && !readinessChecked && !resolved.isEmpty();
                readinessChecked = true;
                RunCancellation.Work<Map<String, String>> work = () -> {
                    if (checkReadiness) {
                        DeviceReadiness.beforeSetup(adb, device, resolved.getFirst().test());
                    }
                    Map<String, String> expanded = runtime.expanded(hook.arguments());
                    long timeout = expanded.containsKey("timeout")
                        ? FlowTestParser.parsePositiveLong(expanded.get("timeout"), hook.line(), "timeout")
                        : 10_000;
                    return runtime.execute(hook.action(), hook.arguments(), timeout);
                };
                Map<String, String> details = phase == FlowTest.Phase.AFTER ? work.run() : cancellation.execute(work);
                progressFile.hook(number, suite.hooks().size(), hook, "passed");
                outcomes.add(new HookOutcome(phase.value(), hook.line(), hook.action(), "passed",
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), null, details, hook.label()));
            } catch (RunCancellation.Cancelled cancelled) {
                passed = false;
                progressFile.hook(number, suite.hooks().size(), hook, "cancelled");
                outcomes.add(new HookOutcome(phase.value(), hook.line(), hook.action(), "cancelled",
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), "Cancelled by user", Map.of(), hook.label()));
            } catch (Exception failure) {
                passed = false;
                progressFile.hook(number, suite.hooks().size(), hook, "failed");
                String message = values.redact(failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
                outcomes.add(new HookOutcome(phase.value(), hook.line(), hook.action(), "failed",
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), message, Map.of(), hook.label()));
                if (stopOnFailure) stopped = true;
            }
        }
        return passed;
    }

    /**
     * `evidence/*.png` files a test's own run just wrote, as paths relative to the suite's output
     * directory so `suite-report.html` can link to them the same way it links each `report.html`.
     * Sorted by filename, which already orders `step-01`, `step-02`, … ahead of a trailing
     * `failure-NN`. Best-effort: a directory that was never created (a skipped test) is empty.
     */
    private static List<String> screenshotPaths(Path root, String directoryName) {
        Path evidence = root.resolve(directoryName).resolve("evidence");
        if (!Files.isDirectory(evidence)) return List.of();
        try (var files = Files.list(evidence)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".png"))
                .map(path -> path.getFileName().toString()).sorted()
                .map(name -> directoryName + "/evidence/" + name).toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    static Totals totals(List<TestOutcome> outcomes) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (TestOutcome outcome : outcomes) {
            switch (outcome.status()) {
                case "passed" -> passed++;
                case "failed" -> failed++;
                case "cancelled" -> { if (outcome.teardownErrors().isEmpty()) skipped++; else failed++; }
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
        out.append("  \"status\": \"").append(report.cancelled() ? "cancelled" : allPassed ? "passed" : "failed").append("\",\n");
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
                    .append(",\"action\":").append(Json.quote(hook.action())).append(FlowLabel.json(hook.label()))
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
                .append(", \"name\": ").append(Json.quote(test.name())).append(FlowLabel.json(test.label()))
                .append(", \"status\": ").append(Json.quote(test.status()))
                .append(", \"durationMillis\": ").append(test.durationMillis())
                .append(", \"report\": ").append(Json.quote(test.report()))
                .append(", \"error\": ").append(Json.quote(test.error()))
                .append(", \"teardownErrors\": ").append(jsonStrings(test.teardownErrors()))
                .append(", \"screenshots\": ").append(jsonStrings(test.screenshots()))
                .append("}");
        }
        out.append(report.tests().isEmpty() ? "]\n" : "\n  ]\n");
        out.append("}\n");
        return out.toString();
    }

    static String reportHtml(SuiteReport report, Path root) {
        Totals totals = totals(report.tests());
        long millis = Duration.between(report.startedAt(), report.endedAt()).toMillis();
        ReportImages images = new ReportImages(root);
        List<ReportHtml.Shot> shots = images.embed(suiteShots(report));
        String status = report.cancelled() ? "cancelled"
            : totals.failed() > 0 || totals.skipped() > 0 || report.hooks().stream()
                .anyMatch(hook -> hook.status().equals("failed")) ? "failed" : "passed";
        List<ReportHtml.Section> sections = new ArrayList<>(List.of(
            new ReportHtml.Section("screenshots", "Screenshots"),
            new ReportHtml.Section("tests", "Tests")));
        if (!report.hooks().isEmpty()) {
            sections.add(new ReportHtml.Section("lifecycle", "Suite lifecycle"));
        }
        String note = "Every screenshot each test in this suite captured, in run order."
            + (images.plateBudgetSpent()
                ? " This suite captured more than one page can carry, so the last full-size views open from each test's evidence folder rather than from the page."
                : " They travel inside this page, so it can be shared on its own.");
        return ReportHtml.document(report.name(),
            ReportHtml.head(report.name(), status, suiteVerdict(report)),
            ReportHtml.rail(
                List.of(
                    new ReportHtml.Stat("" + totals.passed(), "Passed", "passed"),
                    new ReportHtml.Stat("" + totals.failed(), "Failed",
                        totals.failed() > 0 ? "failed" : ""),
                    new ReportHtml.Stat("" + totals.skipped(), "Skipped",
                        totals.skipped() > 0 ? "skipped" : ""),
                    new ReportHtml.Stat(FlowTestRunner.formatMillis(millis), "Duration", ""),
                    new ReportHtml.Stat("" + shots.size(), "Screenshots", "")),
                ReportHtml.meta(
                    "Kind=Test suite",
                    "Suite=" + report.suiteFile(),
                    report.projectRoot() == null ? null : "Project=" + report.projectRoot(),
                    "Device=" + report.deviceSerial(),
                    report.continueOnFailure() ? "On failure=Continue" : "On failure=Stop",
                    "Started=" + ReportHtml.time(report.startedAt()),
                    "Ended=" + ReportHtml.time(report.endedAt())),
                sections),
            ReportHtml.card("screenshots", "Screenshots", note,
                shots.size() + (shots.size() == 1 ? " capture" : " captures"),
                ReportHtml.gallery("screenshots", shots))
            + testsHtml(report, images)
            + hooksHtml(report));
    }

    /** The failed hook, named: a suite can fail without any test in its table having failed. */
    private static String suiteVerdict(SuiteReport report) {
        if (report.cancelled()) return "Cancelled by user, so the remaining tests were skipped";
        return report.hooks().stream().filter(hook -> hook.status().equals("failed")).findFirst()
            .map(hook -> "suite " + hook.phase() + " hook failed: " + hook.displayName()
                + (hook.error() == null ? "" : ": " + hook.error()))
            .orElse(null);
    }

    /** One flat, labelled list across the suite, so the gallery reads as the whole run. */
    private static List<ReportHtml.Shot> suiteShots(SuiteReport report) {
        List<ReportHtml.Shot> shots = new ArrayList<>();
        for (TestOutcome test : report.tests()) {
            for (String screenshot : test.screenshots()) {
                shots.add(new ReportHtml.Shot(screenshot, screenshotLabel(screenshot),
                    "Test " + test.number(), test.displayName(), test.status()));
            }
        }
        return shots;
    }

    private static String testsHtml(SuiteReport report, ReportImages images) {
        // The strip beside a row opens the same overlay the gallery does, so stepping through the
        // run with the overlay's prev/next works whichever thumbnail the reader started from. The
        // anchor is counted out in step with suiteShots rather than looked up by path: two tests
        // that captured the same file would otherwise both point at whichever one was indexed last.
        int shot = 0;
        StringBuilder rows = new StringBuilder();
        for (TestOutcome test : report.tests()) {
            rows.append("<tr><td class=\"num\">").append(test.number())
                .append("</td><td class=\"name\">").append(html(test.displayName()))
                .append("<br><span class=\"mono\" style=\"color:var(--low)\">")
                .append(html(test.reference())).append("</span></td><td>")
                .append(ReportHtml.pill(test.status())).append("</td><td class=\"dur\">")
                .append(FlowTestRunner.formatMillis(test.durationMillis())).append("</td><td>");
            if (test.report() != null) {
                rows.append("<a href=\"").append(html(test.report().replace("report.json", "report.html")))
                    .append("\">Open report</a>");
            }
            rows.append("</td><td class=\"err\">").append(html(test.error() == null ? "" : test.error()))
                .append("</td></tr>");
            if (!test.screenshots().isEmpty()) {
                List<ReportHtml.Shot> strip = new ArrayList<>();
                for (String path : test.screenshots()) {
                    strip.add(new ReportHtml.Shot(path, screenshotLabel(path), null, null, null,
                        "#" + ReportHtml.anchor("screenshots", shot++), null, null));
                }
                rows.append("<tr><td></td><td colspan=\"5\" class=\"screenshots\">")
                    .append(ReportHtml.strip(images.embedThumbnails(strip))).append("</td></tr>");
            }
        }
        Totals totals = totals(report.tests());
        return ReportHtml.card("tests", "Tests",
            "Each test in suite order, with its own report and the screenshots it captured.",
            totals.total() + (totals.total() == 1 ? " test" : " tests"),
            rows.isEmpty() ? ReportHtml.empty("This suite ran no tests.")
                : ReportHtml.table("<th>#</th><th>Test</th><th>Status</th><th>Duration</th>"
                    + "<th>Report</th><th>Error</th>", rows.toString()));
    }

    private static String hooksHtml(SuiteReport report) {
        if (report.hooks().isEmpty()) return "";
        StringBuilder rows = new StringBuilder();
        for (HookOutcome hook : report.hooks()) {
            rows.append("<tr><td><span class=\"phase\">")
                .append(html(ReportHtml.sentenceCase(hook.phase())))
                .append("</span></td><td class=\"name\">").append(html(hook.displayName()))
                .append("</td><td>").append(ReportHtml.pill(hook.status()))
                .append("</td><td class=\"dur\">")
                .append(FlowTestRunner.formatMillis(hook.durationMillis()))
                .append("</td><td class=\"err\">").append(html(hook.error() == null ? "" : hook.error()))
                .append("</td></tr>");
        }
        return ReportHtml.card("lifecycle", "Suite lifecycle",
            "Host-only setup and cleanup hooks; device state belongs to each test.",
            report.hooks().size() + (report.hooks().size() == 1 ? " hook" : " hooks"),
            ReportHtml.table("<th>Phase</th><th>Action</th><th>Status</th><th>Duration</th>"
                + "<th>Error</th>", rows.toString()));
    }

    static String junitXml(SuiteReport report) {
        Totals totals = totals(report.tests());
        List<HookOutcome> failedHooks = report.hooks().stream()
            .filter(hook -> hook.status().equals("failed")).toList();
        boolean cancelledLifecycle = report.cancelled() && failedHooks.isEmpty();
        int lifecycleCases = report.hooks().isEmpty() && !cancelledLifecycle ? 0 : 1;
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<testsuite name=\"").append(xml(report.name())).append("\" tests=\"")
            .append(totals.total() + lifecycleCases).append("\" failures=\"")
            .append(totals.failed() + (failedHooks.isEmpty() ? 0 : 1))
            .append("\" skipped=\"").append(totals.skipped() + (cancelledLifecycle ? 1 : 0)).append("\">\n");
        for (TestOutcome test : report.tests()) {
            out.append("  <testcase name=\"").append(xml(test.displayName())).append("\" classname=\"")
                .append(xml(test.reference())).append("\" time=\"")
                .append(test.durationMillis() / 1000.0).append("\">");
            if (test.status().equals("failed") || !test.teardownErrors().isEmpty()) {
                // `error` is the test's own failureMessage(), which already names the phase and every
                // cleanup failure. The phase-by-phase breakdown stays in the per-test junit.xml this
                // row links to; repeating it here would say the same thing twice.
                out.append("<failure message=\"").append(xml(test.error())).append("\">")
                    .append(xml(test.error())).append("</failure>");
            } else if (test.status().equals("skipped") || test.status().equals("cancelled")) out.append("<skipped/>");
            out.append("</testcase>\n");
        }
        if (lifecycleCases == 1) {
            out.append("  <testcase name=\"Suite lifecycle\" classname=\"")
                .append(xml(report.suiteFile())).append("\">");
            if (!failedHooks.isEmpty()) {
                String message = failedHooks.stream().map(hook -> hook.phase() + ": " + hook.displayName()
                    + ": " + hook.error()).collect(java.util.stream.Collectors.joining(" · "));
                out.append("<failure message=\"").append(xml(message)).append("\">")
                    .append(xml(message)).append("</failure>");
            }
            if (cancelledLifecycle) out.append("<skipped message=\"Cancelled by user\"/>");
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

    /** The step name a screenshot was captured under, read back out of its evidence file name. */
    private static String screenshotLabel(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
    }

    private static String html(String value) {
        return ReportHtml.escape(value);
    }

    private static String xml(String value) {
        return html(value == null ? "" : value).replace("'", "&apos;");
    }
}
