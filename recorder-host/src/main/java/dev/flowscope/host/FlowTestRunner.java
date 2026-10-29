package dev.flowscope.host;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.flowscope.host.ReportHtml.Shot;

public final class FlowTestRunner {
    private static final long POLL_MILLIS = 200;
    private static final int DUMP_ATTEMPTS = 5;
    private static final long DUMP_RETRY_MILLIS = 400;
    /**
     * Steps that look an element up before (or instead of) changing anything, so they may start from
     * the hierarchy settle() recorded. Their own input goes through command(), which drops the cache.
     */
    private static final Set<String> REUSES_SETTLED_DUMP = Set.of(
        "wait-visible", "assert-visible", "assert-not-visible", "screenshot", "tap", "tap-if-visible", "type",
        "long-press", "swipe", "scroll-to", "clear-text");
    /** How long consecutive hierarchies are compared after a mutation (design: up to 1.5 s). */
    private static final long SETTLE_WINDOW_NANOS = 1_500_000_000L;
    /** The dump taken at the end of settle() may answer the next lookup for this long. */
    private static final long SETTLED_DUMP_MAX_AGE_NANOS = 3_000_000_000L;
    private static final Pattern GRANTED = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_.]+): granted=true(?:,.*)?$");
    /** Detail keys whose value is a file beside the report rather than a value to read. */
    private static final Set<String> LINKED_DETAILS = Set.of("evidence", "hierarchy", "screenshot");
    /** `failure-NN` names the step that failed, so its screenshot can be labelled with that step. */
    private static final Pattern FAILURE_SHOT = Pattern.compile("failure-(\\d+)");
    private final AdbClient adb;
    private final Device device;
    private final FlowTest test;
    private final Path root;
    private final FlowWebViewClient web;
    private final ApkInspector apkInspector;
    private final RuntimeValues values;
    private final HostRuntime hostRuntime;
    private final List<StepResult> results = new ArrayList<>();
    private WebViewState previousWebState;
    private Set<String> sweepBefore;
    private Consumer<StepProgress> listener = progress -> {};
    // A suite child reports through the suite's own progress file instead.
    private boolean writesProgressFile = true;
    private RunProgress progressFile;
    private boolean keepHierarchy;
    private RunCancellation cancellation = new RunCancellation();

    public FlowTestRunner cancellation(RunCancellation signal) {
        this.cancellation = Objects.requireNonNull(signal);
        return this;
    }

    public boolean isCancelled() { return cancellation.isRequested(); }
    private String status(boolean passed) { return isCancelled() ? "cancelled" : passed ? "passed" : "failed"; }

    private boolean readinessChecked;
    private boolean foregroundChecked;
    private List<UiHierarchy.UiNode> lookupNodes = List.of();

    public FlowTestRunner keepHierarchy(boolean keep) {
        this.keepHierarchy = keep;
        return this;
    }

    /**
     * Progress for a UI: one event before a step runs and one when it finishes. Never carries an
     * interpolated argument value — only the literal action, phase and line number from the `.flow`
     * file — so it is safe to render live, before any redaction pass.
     */
    public record StepProgress(int number, int total, String phase, String action, int line, String status, String label) {
        public StepProgress(int number, int total, String phase, String action, int line, String status) {
            this(number, total, phase, action, line, status, "");
        }
        public String displayName() { return label == null || label.isBlank() ? action : label; }
    }

    public FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot) throws IOException {
        this(adb, device, test, outputRoot, (Path) null);
    }

    public FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        Path secretsFile) throws IOException {
        this(adb, device, test, outputRoot,
            RunDirectories.stamp() + "-" + RunDirectories.sanitize(test.name()),
            FlowHttpClient.platform(), System.getenv(), SecretFile.load(secretsFile, System.getenv()),
            Map.of(), Map.of());
    }

    public FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName) throws IOException {
        this(adb, device, test, outputRoot, directoryName, FlowHttpClient.platform(), System.getenv(),
            Map.of(), Map.of(), Map.of());
    }

    FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName, FlowHttpClient http, Map<String, String> environment) throws IOException {
        this(adb, device, test, outputRoot, directoryName, http, environment, Map.of(), Map.of(), Map.of(),
            new CdpWebViewClient(adb, device, test.packageName(),
                test.mode() == CaptureMode.INSTRUMENTED), ApkInspector.platform(), null);
    }

    FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName, FlowHttpClient http, Map<String, String> environment,
        FlowWebViewClient web) throws IOException {
        this(adb, device, test, outputRoot, directoryName, http, environment, Map.of(), Map.of(), Map.of(), web,
            ApkInspector.platform(), null);
    }

    FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName, FlowHttpClient http, Map<String, String> environment,
        FlowWebViewClient web, ApkInspector apkInspector) throws IOException {
        this(adb, device, test, outputRoot, directoryName, http, environment, Map.of(), Map.of(), Map.of(), web,
            apkInspector, null);
    }

    FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName, FlowHttpClient http, Map<String, String> environment,
        Map<String, String> secretSources, Map<String, RuntimeValues.Value> suiteVariables,
        Map<String, String> suiteBuiltins) throws IOException {
        this(adb, device, test, outputRoot, directoryName, http, environment, secretSources,
            suiteVariables, suiteBuiltins,
            new CdpWebViewClient(adb, device, test.packageName(), test.mode() == CaptureMode.INSTRUMENTED),
            ApkInspector.platform(), null);
    }

    FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName, FlowHttpClient http, Map<String, String> environment,
        Map<String, String> secretSources, RuntimeValues suiteValues,
        Map<String, String> suiteBuiltins) throws IOException {
        this(adb, device, test, outputRoot, directoryName, http, environment, secretSources,
            suiteValues.variables(), suiteBuiltins,
            new CdpWebViewClient(adb, device, test.packageName(), test.mode() == CaptureMode.INSTRUMENTED),
            ApkInspector.platform(), suiteValues.redactor());
    }

    private FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName, FlowHttpClient http, Map<String, String> environment,
        Map<String, String> secretSources, Map<String, RuntimeValues.Value> suiteVariables,
        Map<String, String> suiteBuiltins, FlowWebViewClient web, ApkInspector apkInspector,
        RuntimeValues.Redactor redactor)
        throws IOException {
        this.adb = adb; this.device = device; this.test = test;
        this.web = web;
        this.apkInspector = apkInspector;
        Instant startedAt = Instant.now();
        this.values = new RuntimeValues(environment, secretSources, suiteVariables, test.name(),
            device.serial(), startedAt, UUID.randomUUID(), suiteBuiltins, redactor);
        this.hostRuntime = new HostRuntime(values, http, test.sourceDirectory());
        this.root = outputRoot.resolve(directoryName).toAbsolutePath();
        Files.createDirectories(root.resolve("evidence"));
    }

    public Path root() { return root; }

    public FlowTestRunner observe(Consumer<StepProgress> listener) {
        this.listener = listener == null ? progress -> {} : listener;
        return this;
    }

    /** A suite's child test: its steps go into the suite's `progress.jsonl`, not one of its own. */
    FlowTestRunner withoutProgressFile() {
        this.writesProgressFile = false;
        return this;
    }

    private void progress(StepProgress event) {
        // File first: a listener may be what reads it.
        if (progressFile != null) progressFile.step(event);
        listener.accept(event);
    }

    /** Metadata-only cookie/storage changes observed at WebView action boundaries. */
    public List<String> webChanges() {
        return results.stream().map(StepResult::details).map(details -> details.get("webChanges"))
            .filter(Objects::nonNull).flatMap(value -> Arrays.stream(value.split("; "))).toList();
    }

    /**
     * The first failed setup or test step, or null when both phases passed.
     *
     * <p>Deliberately excludes the `after` phase. Cleanup is a different kind of event, and folding
     * it in here would present a dirty teardown as a failed assertion.
     */
    public String primaryFailure() {
        return results.stream()
            .filter(r -> r.status.equals("failed") && r.step.phase() != FlowTest.Phase.AFTER)
            .findFirst().map(FlowTestRunner::describe).orElse(null);
    }

    /** Every failed cleanup step, formatted the same way. Empty when teardown was clean. */
    public List<String> teardownFailures() {
        return results.stream()
            .filter(r -> r.status.equals("failed") && r.step.phase() == FlowTest.Phase.AFTER)
            .map(FlowTestRunner::describe).toList();
    }

    /**
     * One line naming the phase that broke, or null when the run passed. Every surface with room for
     * a single string — the CLI, `junit.xml`'s message attribute, a suite outcome — reads this, so
     * none of them can mistake a cleanup failure for a test failure.
     */
    public String failureMessage() {
        String primary = primaryFailure();
        List<String> teardown = teardownFailures();
        if (isCancelled()) return "Cancelled" + (primary == null ? "" : " · " + primary)
            + (teardown.isEmpty() ? "" : " · cleanup also failed: " + String.join(" · ", teardown));
        if (primary == null) {
            return teardown.isEmpty() ? null : "cleanup failed · " + String.join(" · ", teardown);
        }
        String verdict = (setupFailed() ? "setup failed · " : "") + primary;
        if (teardown.isEmpty()) return verdict;
        return verdict + " · cleanup also failed (" + teardown.size() + "): "
            + String.join(" · ", teardown);
    }

    /** True when the primary failure came from `before`, so the test body never ran. */
    private boolean setupFailed() {
        return results.stream()
            .filter(r -> r.status.equals("failed") && r.step.phase() != FlowTest.Phase.AFTER)
            .findFirst().map(r -> r.step.phase() == FlowTest.Phase.BEFORE).orElse(false);
    }

    private static String describe(StepResult result) {
        return result.error == null ? result.step.displayName() + " failed"
            : result.step.displayName() + ": " + result.error;
    }

    public boolean run() throws Exception {
        Thread sweepHook = null;
        try {
            Set<String> observed;
            try {
                observed = cancellation.execute(() -> {
                    preflight();
                    return uiautomatorSnapshot();
                });
            } catch (RunCancellation.Cancelled cancelled) { observed = null; }
            final Set<String> uiautomatorBefore = observed;
            sweepHook = new Thread(() -> sweepUiautomator(uiautomatorBefore), "flowscope-uiautomator-sweep");
            Runtime.getRuntime().addShutdownHook(sweepHook);
            sweepBefore = uiautomatorBefore;
            RunSteps.write(root, List.of(RunSteps.testGroup(0, "", test, "report.json")));
            if (writesProgressFile) progressFile = RunProgress.start(root, "test", test.name(), test.steps().size());
            Instant started = Instant.now();
            if (test.mode() == CaptureMode.INSTRUMENTED) {
                agentEvents = AgentEventCapture.start(adb, device.serial(), test.packageName());
            }
            boolean before = runPhase(FlowTest.Phase.BEFORE, true);
            boolean main;
            if (before) main = runPhase(FlowTest.Phase.TEST, true);
            else { skipPhase(FlowTest.Phase.TEST, isCancelled() ? "run cancelled" : "before phase failed"); main = false; }
            if (isCancelled()) sweepUiautomator(sweepBefore);
            boolean after = runPhase(FlowTest.Phase.AFTER, false);
            if (writesProgressFile) cancellation.finish();
            boolean passed = before && main && after && !isCancelled();
            if (agentEvents != null) agentEvents.stop();
            writeReports(passed, started, Instant.now());
            if (progressFile != null) progressFile.finish();
            return passed;
        } finally {
            if (writesProgressFile) cancellation.finish();
            if (progressFile != null) progressFile.interrupt();
            web.close();
            if (sweepHook != null) {
                sweepUiautomator(sweepBefore);
                try {
                    Runtime.getRuntime().removeShutdownHook(sweepHook);
                } catch (IllegalStateException shuttingDown) {
                    // The JVM is already exiting and the hook owns the sweep.
                }
            }
        }
    }

    /** The device's uiautomator pids before the run, or null when they cannot be read. */
    private Set<String> uiautomatorSnapshot() {
        try {
            return adb.uiautomatorPids(device.serial());
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * Kills uiautomator processes this run left behind (a dump that outlived a killed or timed-out
     * run). Only pids absent from the starting snapshot are touched, and never fails the run.
     */
    private void sweepUiautomator(Set<String> before) {
        if (before == null) return;
        try {
            adb.killNewUiautomator(device.serial(), before);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // The device may already be gone; cleanup is best effort.
        }
    }

    // `uiautomator dump` starts a new process each time (about 2 s on a real device), so dumps are the
    // dominant cost of a run. These counters feed the per-step report; the cache removes redundant dumps.
    private int dumpCount;
    private AgentEventCapture agentEvents;
    private final Map<Integer, Instant[]> stepWindows = new HashMap<>();
    private long dumpNanos;
    private byte[] settledDump;
    private long settledAtNanos;

    private boolean runPhase(FlowTest.Phase phase, boolean stopOnFailure) {
        boolean passed = true, stopped = false;
        int total = test.steps().size();
        Deque<Boolean> visibleBlocks = new ArrayDeque<>();
        for (int i = 0; i < test.steps().size(); i++) {
            FlowTest.Step step = test.steps().get(i);
            if (step.phase() != phase) continue;
            if (phase != FlowTest.Phase.AFTER && isCancelled()) {
                addSkipped(i, step, "run cancelled"); passed = false; continue;
            }
            if (stopped) { addSkipped(i, step, "earlier " + phase.value() + " instruction failed"); continue; }
            boolean ending = step.action().equals("end");
            if (ending && !visibleBlocks.isEmpty()) visibleBlocks.pop();
            if (!ending && (visibleBlocks.contains(false) || !values.conditionsPass(step.arguments()))) {
                if (step.action().equals("if-visible")) visibleBlocks.push(false);
                addSkipped(i, step, "condition not met"); continue;
            }
            progress(new StepProgress(i + 1, total, phase.value(), step.action(), step.line(), "running", step.label()));
            long started = System.nanoTime();
            Instant stepStarted = Instant.now();
            // Any other step can change the UI before it looks, so it must not see a remembered dump.
            if (!REUSES_SETTLED_DUMP.contains(step.action())) settledDump = null;
            int dumpsBefore = dumpCount;
            long dumpNanosBefore = dumpNanos;
            try {
                final int stepIndex = i;
                RunCancellation.Work<Map<String, String>> work = () -> {
                    checkReadiness(step);
                    return execute(step, stepIndex);
                };
                Map<String, String> details = new LinkedHashMap<>(phase == FlowTest.Phase.AFTER
                    ? work.run() : cancellation.execute(work));
                if (step.action().equals("if-visible")) visibleBlocks.push(Boolean.parseBoolean(details.get("matched")));
                retainHierarchy(i, details);
                results.add(new StepResult(i + 1, step, "passed", elapsed(started), null, details,
                    dumpCount - dumpsBefore, Duration.ofNanos(dumpNanos - dumpNanosBefore).toMillis()));
                System.out.printf("PASS %s %02d  %s%n", phase.value().toUpperCase(), i + 1, step.displayName());
                progress(new StepProgress(i + 1, total, phase.value(), step.action(), step.line(), "passed", step.label()));
            } catch (RunCancellation.Cancelled cancelled) {
                passed = false;
                results.add(new StepResult(i + 1, step, "cancelled", elapsed(started),
                    "Cancelled by user", Map.of(), dumpCount - dumpsBefore,
                    Duration.ofNanos(dumpNanos - dumpNanosBefore).toMillis()));
                progress(new StepProgress(i + 1, total, phase.value(), step.action(), step.line(), "cancelled", step.label()));
            } catch (Exception failure) {
                passed = false;
                captureEvidence("failure-" + String.format("%02d", i + 1));
                String message = redact(message(failure));
                Map<String, String> details = new LinkedHashMap<>();
                retainHierarchy(i, details);
                if (step.action().equals("if-visible")) visibleBlocks.push(false);
                results.add(new StepResult(i + 1, step, "failed", elapsed(started), message, details,
                    dumpCount - dumpsBefore, Duration.ofNanos(dumpNanos - dumpNanosBefore).toMillis()));
                System.err.printf("FAIL %s %02d  %s: %s%n", phase.value().toUpperCase(), i + 1, step.displayName(), message);
                progress(new StepProgress(i + 1, total, phase.value(), step.action(), step.line(), "failed", step.label()));
                if (stopOnFailure) stopped = true;
            } finally {
                stepWindows.put(i + 1, new Instant[] {stepStarted, Instant.now()});
            }
        }
        return passed;
    }

    private void addSkipped(int i, FlowTest.Step step, String reason) {
        results.add(new StepResult(i + 1, step, "skipped", 0, reason, Map.of(), 0, 0));
        System.out.printf("SKIP %s %02d  %s%n", step.phase().value().toUpperCase(), i + 1, step.displayName());
        progress(new StepProgress(i + 1, test.steps().size(), step.phase().value(), step.action(),
            step.line(), "skipped", step.label()));
    }

    private void skipPhase(FlowTest.Phase phase, String reason) {
        for (int i = 0; i < test.steps().size(); i++) if (test.steps().get(i).phase() == phase) addSkipped(i, test.steps().get(i), reason);
    }

    private Map<String, String> execute(FlowTest.Step step, int index) throws Exception {
        Map<String, String> args = hostRuntime.expanded(step.arguments());
        long timeout = args.containsKey("timeout") ? FlowTestParser.parsePositiveLong(args.get("timeout"), step.line(), "timeout") : test.timeoutMillis();
        return switch (step.action()) {
            case "launch" -> { launch(); settle(); DeviceReadiness.check(adb, device.serial(), test.packageName(), true); yield Map.of(); }
            case "wait-visible", "assert-visible" -> { requireNode(args, timeout); yield Map.of(); }
            case "tap" -> { var n = requireNode(args, timeout); ensureUncovered(n, n.tapX(args)); shell("input", "tap", "" + n.tapX(args), "" + n.centerY()); settle(); yield Map.of(); }
            case "tap-if-visible" -> { var n = findNode(args, timeout); if (n != null) { ensureUncovered(n, n.tapX(args)); shell("input", "tap", "" + n.tapX(args), "" + n.centerY()); settle(); } yield Map.of("matched", "" + (n != null)); }
            case "type" -> { if (hasSelector(args)) { var n = requireNode(args, timeout); ensureUncovered(n, n.centerX()); shell("input", "tap", "" + n.centerX(), "" + n.centerY()); } typeText(args.get("value")); settle(); yield Map.of(); }
            case "scroll" -> { scroll(args.get("direction")); settle(); yield Map.of(); }
            case "long-press" -> longPress(args, timeout);
            case "swipe" -> swipeStep(args, timeout);
            case "scroll-to" -> scrollTo(args);
            case "press-key" -> { String key = args.get("key"); shell("input", "keyevent", pressKeycode(key)); settle(); yield Map.of("key", key); }
            case "clear-text" -> clearText(args, timeout);
            case "hide-keyboard" -> hideKeyboard();
            case "back" -> { shell("input", "keyevent", "KEYCODE_BACK"); settle(); yield Map.of(); }
            case "assert-not-visible" -> { requireAbsent(args, timeout); yield Map.of(); }
            case "if-visible" -> Map.of("matched", "" + (findNode(args, args.containsKey("timeout") ? timeout : 0) != null));
            case "end" -> Map.of();
            case "screenshot-if-visible" -> {
                boolean matched = findNode(args, args.containsKey("timeout") ? timeout : 0) != null;
                if (!matched) yield Map.of("matched", "false");
                String name = args.getOrDefault("name", "step-" + String.format("%02d", index + 1));
                captureScreenshot(name);
                yield Map.of("matched", "true", "evidence", "evidence/" + safeFile(name) + ".png");
            }
            case "screenshot" -> { String name = args.getOrDefault("name", "step-" + String.format("%02d", index + 1)); captureScreenshot(name); yield Map.of("evidence", "evidence/" + safeFile(name) + ".png"); }
            case "api-request", "set-date", "set-value", "transform", "secret" ->
                hostRuntime.execute(step.action(), step.arguments(), timeout);
            case "clear-app-data" -> { command("shell", "pm", "clear", test.packageName()); yield packageDetails(); }
            case "force-stop" -> { command("shell", "am", "force-stop", test.packageName()); yield packageDetails(); }
            case "grant-permission" -> { permission("grant", args.get("permission")); yield permissionDetails(args); }
            case "revoke-permission" -> { permission("revoke", args.get("permission")); yield permissionDetails(args); }
            case "reset-app-permissions" -> resetPermissions();
            case "open-deep-link" -> openDeepLink(args);
            case "assert-deep-link-handler" -> assertDeepLinkHandler(args);
            case "assert-app-link-verified" -> assertAppLinkVerified(args);
            case "install-apk" -> installApk(args);
            case "uninstall-app" -> uninstallApp(args);
            case "assert-installed" -> assertInstalled(args);
            case "set-install-referrer" -> setInstallReferrer(args.get("value"));
            case "clear-install-referrer" -> clearInstallReferrer();
            case "web-wait", "web-assert-visible" -> { web.waitVisible(args, timeout); yield webInspection(args, timeout); }
            case "web-tap" -> { web.tap(args, timeout); settle(); yield webInspection(args, timeout); }
            case "web-type" -> { web.type(args, timeout); yield webInspection(args, timeout); }
            case "web-assert-text" -> webAssertText(args, timeout);
            case "web-assert-url" -> webAssertUrl(args, timeout);
            case "web-screenshot" -> webScreenshot(args, index, timeout);
            case "web-assert-cookie" -> webAssertCookie(args, timeout);
            case "web-clear-cookies" -> webClearCookies(args, timeout);
            case "web-assert-storage" -> webAssertStorage(args, timeout);
            case "web-clear-storage" -> webClearStorage(args, timeout);
            case "web-assert-interface" -> webAssertInterface(args, timeout);
            case "web-assert-secure-context" -> webAssertSecureContext(args, timeout);
            case "web-snapshot" -> webSnapshot(args, index, timeout);
            default -> throw new IllegalArgumentException("Unsupported action: " + step.action());
        };
    }

    private Map<String, String> openDeepLink(Map<String, String> args) throws Exception {
        URI uri = requireLink(args.get("uri"));
        if (Boolean.parseBoolean(args.getOrDefault("cold-start", "false"))) {
            command("shell", "am", "force-stop", test.packageName());
        }
        List<String> command = new ArrayList<>(List.of("shell", "am", "start", "-W", "-a",
            args.getOrDefault("action", "android.intent.action.VIEW")));
        String categories = args.getOrDefault("category", "android.intent.category.BROWSABLE");
        for (String category : categories.split(",")) {
            if (!category.isBlank()) { command.add("-c"); command.add(category.strip()); }
        }
        String component = deepLinkComponent(args, uri);
        command.add("-d"); command.add(uri.toString()); command.add("-n"); command.add(component);
        command(command.toArray(String[]::new));
        settle();
        return Map.of("uri", safeLink(uri), "package", test.packageName(), "component", component);
    }

    private Map<String, String> assertDeepLinkHandler(Map<String, String> args) throws Exception {
        URI uri = requireLink(args.get("uri"));
        List<String> handlers = deepLinkHandlers(args, uri);
        if (handlers.isEmpty()) {
            throw new IllegalStateException("Declared package has no matching deep-link activity");
        }
        if (args.containsKey("activity")) deepLinkComponent(args, uri);
        return Map.of("uri", safeLink(uri), "package", test.packageName(),
            "handlerCount", Integer.toString(handlers.size()));
    }

    private String deepLinkComponent(Map<String, String> args, URI uri) throws Exception {
        List<String> handlers = deepLinkHandlers(args, uri);
        String requested = args.get("activity");
        if (requested != null) {
            String component = requested.contains("/") ? requested
                : test.packageName() + "/" + requested;
            if (!component.startsWith(test.packageName() + "/")) {
                throw new IllegalArgumentException("Deep-link activity must belong to " + test.packageName());
            }
            String expanded = component.substring(component.indexOf('/') + 1).startsWith(".")
                ? test.packageName() + "/" + test.packageName() + component.substring(component.indexOf('/') + 1)
                : component;
            if (!handlers.contains(component) && !handlers.contains(expanded)) {
                throw new IllegalStateException("Requested activity does not handle this deep link");
            }
            return handlers.contains(component) ? component : expanded;
        }
        if (handlers.size() == 1) return handlers.getFirst();
        if (handlers.isEmpty()) throw new IllegalStateException("Declared package has no matching deep-link activity");
        throw new IllegalStateException("Multiple activities handle this deep link; add activity= to select one");
    }

    private List<String> deepLinkHandlers(Map<String, String> args, URI uri) throws Exception {
        List<String> query = new ArrayList<>(List.of("-s", device.serial(), "shell", "cmd", "package",
            "query-activities", "--brief", "-a", args.getOrDefault("action", "android.intent.action.VIEW")));
        String categories = args.getOrDefault("category", "android.intent.category.BROWSABLE");
        for (String category : categories.split(",")) {
            if (!category.isBlank()) { query.add("-c"); query.add(category.strip()); }
        }
        query.add("-d"); query.add(uri.toString()); query.add(test.packageName());
        var result = adb.run(query);
        if (result.exitCode() != 0) return List.of();
        Matcher matcher = Pattern.compile("(" + Pattern.quote(test.packageName())
            + "/[A-Za-z0-9_.$]+)").matcher(result.stdout());
        LinkedHashSet<String> handlers = new LinkedHashSet<>();
        while (matcher.find()) handlers.add(matcher.group(1));
        return List.copyOf(handlers);
    }

    private Map<String, String> assertAppLinkVerified(Map<String, String> args) throws Exception {
        String host = args.get("host").toLowerCase(Locale.ROOT);
        var result = adb.run(List.of("-s", device.serial(), "shell", "pm", "get-app-links",
            test.packageName()));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("App-link verification state is unavailable on this Android version");
        }
        String state = appLinkState(result.stdout(), host);
        if (!Set.of("verified", "approved", "system_configured").contains(state)) {
            throw new IllegalStateException("App Link host is not verified: " + host + " (state " + state + ")");
        }
        return Map.of("host", host, "state", state);
    }

    static String appLinkState(String output, String host) {
        if (output == null) return "missing";
        Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(host)
            + "\\s*:\\s*([A-Za-z0-9_-]+)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(output);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "missing";
    }

    private Map<String, String> installApk(Map<String, String> args) throws Exception {
        Path apk = resolveArtifactFile(args.get("file"), ".apk");
        List<String> command = new ArrayList<>(List.of("-s", device.serial(), "install"));
        if (Boolean.parseBoolean(args.getOrDefault("replace", "false"))) command.add("-r");
        if (Boolean.parseBoolean(args.getOrDefault("downgrade", "false"))) command.add("-d");
        if (Boolean.parseBoolean(args.getOrDefault("grant-permissions", "false"))) command.add("-g");
        if (Boolean.parseBoolean(args.getOrDefault("test-only", "false"))) command.add("-t");
        command.add(apk.toString());
        var result = adb.run(command);
        if (result.exitCode() != 0 || !(result.stdout() + result.stderr()).contains("Success")) {
            throw new IOException("APK install failed: " + (result.stdout() + result.stderr()).strip());
        }
        Map<String, String> installed = installedPackageDetails();
        return Map.of("package", test.packageName(), "file", apk.getFileName().toString(),
            "sha256", sha256(apk), "versionCode", installed.getOrDefault("versionCode", "unknown"));
    }

    private Map<String, String> uninstallApp(Map<String, String> args) throws Exception {
        List<String> command = new ArrayList<>(List.of("-s", device.serial(), "uninstall"));
        if (Boolean.parseBoolean(args.getOrDefault("keep-data", "false"))) command.add("-k");
        command.add(test.packageName());
        var result = adb.run(command);
        String output = (result.stdout() + result.stderr()).strip();
        if (output.toLowerCase(Locale.ROOT).contains("unknown package")
            || output.toLowerCase(Locale.ROOT).contains("not installed")) {
            return Map.of("package", test.packageName(), "alreadyAbsent", "true");
        }
        if (result.exitCode() != 0 || !output.contains("Success")) {
            throw new IOException("App uninstall failed: " + output);
        }
        return packageDetails();
    }

    private Map<String, String> assertInstalled(Map<String, String> args) throws Exception {
        Map<String, String> details = installedPackageDetails();
        if (args.containsKey("version-code")
            && !args.get("version-code").equals(details.get("versionCode"))) {
            throw new IllegalStateException("Expected version code " + args.get("version-code")
                + " but found " + details.getOrDefault("versionCode", "unknown"));
        }
        if (args.containsKey("version-name")
            && !args.get("version-name").equals(details.get("versionName"))) {
            throw new IllegalStateException("Expected version name " + args.get("version-name")
                + " but found " + details.getOrDefault("versionName", "unknown"));
        }
        return details;
    }

    private Map<String, String> setInstallReferrer(String value) throws Exception {
        Path local = Files.createTempFile("flowscope-referrer-", ".txt");
        String remote = "/data/local/tmp/flowscope-referrer-" + UUID.randomUUID() + ".txt";
        try {
            Files.writeString(local, value, StandardCharsets.UTF_8);
            command("push", local.toString(), remote);
            command("shell", "run-as", test.packageName(), "mkdir", "-p", "files");
            command("shell", "run-as", test.packageName(), "cp", remote,
                "files/flowscope-install-referrer");
            return Map.of("package", test.packageName(), "referrerInjected", "true");
        } finally {
            try { adb.run(List.of("-s", device.serial(), "shell", "rm", "-f", remote)); }
            catch (Exception ignored) {}
            Files.deleteIfExists(local);
        }
    }

    private Map<String, String> clearInstallReferrer() throws Exception {
        command("shell", "run-as", test.packageName(), "rm", "-f",
            "files/flowscope-install-referrer");
        return Map.of("package", test.packageName(), "referrerInjected", "false");
    }

    private Map<String, String> installedPackageDetails() throws Exception {
        var path = adb.run(List.of("-s", device.serial(), "shell", "pm", "path", test.packageName()));
        if (path.exitCode() != 0 || !path.stdout().contains("package:")) {
            throw new IllegalStateException("Target package is not installed: " + test.packageName());
        }
        var dump = adb.run(List.of("-s", device.serial(), "shell", "dumpsys", "package", test.packageName()));
        if (dump.exitCode() != 0) throw new IOException("Could not inspect installed package");
        Map<String, String> details = new LinkedHashMap<>();
        details.put("package", test.packageName());
        Matcher code = Pattern.compile("\\bversionCode=(\\d+)").matcher(dump.stdout());
        Matcher name = Pattern.compile("\\bversionName=([^\\s]+)").matcher(dump.stdout());
        if (code.find()) details.put("versionCode", code.group(1));
        if (name.find()) details.put("versionName", name.group(1));
        return Map.copyOf(details);
    }

    private Map<String, String> webAssertText(Map<String, String> args, long timeout) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        do {
            RunCancellation.checkCurrent();
            if (web.text(args, Math.max(1, remainingMillis(deadline))).equals(args.get("text"))) {
                return webInspection(args, Math.max(1, remainingMillis(deadline)));
            }
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Web element text did not match");
    }

    private Map<String, String> webAssertUrl(Map<String, String> args, long timeout) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        do {
            RunCancellation.checkCurrent();
            String actual = web.url(args, Math.max(1, remainingMillis(deadline)));
            boolean matches = !args.containsKey("url") || actual.equals(args.get("url"));
            if (args.containsKey("path")) {
                try { matches &= Objects.equals(URI.create(actual).getPath(), args.get("path")); }
                catch (Exception invalid) { matches = false; }
            }
            if (args.containsKey("contains")) matches &= actual.contains(args.get("contains"));
            if (matches) return webInspection(args, Math.max(1, remainingMillis(deadline)));
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("WebView URL did not match the expected location");
    }

    private Map<String, String> webScreenshot(Map<String, String> args, int index, long timeout)
        throws Exception {
        String name = args.getOrDefault("name", "web-step-" + String.format("%02d", index + 1));
        String file = safeFile(name) + ".png";
        Files.write(root.resolve("evidence").resolve(file), web.screenshot(args, timeout));
        Map<String, String> details = new LinkedHashMap<>(web.metadata());
        details.put("evidence", "evidence/" + file);
        details.putAll(webInspection(args, timeout));
        return Map.copyOf(details);
    }

    private Map<String, String> webAssertCookie(Map<String, String> args, long timeout) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        String failure = "Cookie assertion did not match";
        do {
            RunCancellation.checkCurrent();
            WebViewState state = web.inspect(args, Math.max(1, remainingMillis(deadline)));
            requireCapability(state, "Cookie inspection");
            List<WebViewState.Cookie> matches = state.cookies().stream()
                .filter(cookie -> cookie.name().equals(args.get("name")))
                .filter(cookie -> !args.containsKey("domain") || cookieDomainMatches(cookie.domain(), args.get("domain")))
                .filter(cookie -> !args.containsKey("path") || cookie.path().equals(args.get("path")))
                .toList();
            boolean expectedExists = Boolean.parseBoolean(args.getOrDefault("exists", "true"));
            if (!expectedExists && matches.isEmpty()) return rememberWebState(state);
            if (expectedExists && matches.size() > 1) {
                throw new IllegalStateException("Cookie name is ambiguous; add domain= and path=");
            }
            if (expectedExists && matches.size() == 1 && cookieMatches(matches.getFirst(), args)) {
                return rememberWebState(state);
            }
            failure = expectedExists ? "Cookie assertion did not match" : "Cookie still exists";
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException(failure + ": " + args.get("name"));
    }

    private static boolean cookieMatches(WebViewState.Cookie cookie, Map<String, String> args) {
        if (args.containsKey("secure") && cookie.secure() != Boolean.parseBoolean(args.get("secure"))) return false;
        if (args.containsKey("http-only") && cookie.httpOnly() != Boolean.parseBoolean(args.get("http-only"))) return false;
        if (args.containsKey("session") && cookie.session() != Boolean.parseBoolean(args.get("session"))) return false;
        if (args.containsKey("same-site") && !cookie.sameSite().equalsIgnoreCase(args.get("same-site"))) return false;
        return !args.containsKey("value") || Objects.equals(cookie.value(), args.get("value"));
    }

    private Map<String, String> webClearCookies(Map<String, String> args, long timeout) throws Exception {
        if (!args.get("domain").matches("\\.?[A-Za-z0-9.-]+")) {
            throw new IllegalArgumentException("web-clear-cookies domain must be a hostname");
        }
        web.clearCookies(args, timeout);
        Map<String, String> details = new LinkedHashMap<>(webInspection(args, timeout));
        details.put("domain", args.get("domain"));
        if (args.containsKey("name")) details.put("cookie", args.get("name"));
        details.put("values", "hidden");
        return Map.copyOf(details);
    }

    private Map<String, String> webAssertStorage(Map<String, String> args, long timeout) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        do {
            RunCancellation.checkCurrent();
            WebViewState state = web.inspect(args, Math.max(1, remainingMillis(deadline)));
            List<WebViewState.Storage> matches = state.storage().stream()
                .filter(entry -> entry.area().equals(args.get("area")) && entry.key().equals(args.get("key")))
                .toList();
            boolean expectedExists = Boolean.parseBoolean(args.getOrDefault("exists", "true"));
            if (!expectedExists && matches.isEmpty()) return rememberWebState(state);
            if (expectedExists && matches.size() == 1
                && (!args.containsKey("value") || Objects.equals(matches.getFirst().value(), args.get("value")))) {
                return rememberWebState(state);
            }
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Web Storage assertion did not match: " + args.get("area")
            + "/" + args.get("key"));
    }

    private Map<String, String> webClearStorage(Map<String, String> args, long timeout) throws Exception {
        URI origin = URI.create(args.get("origin"));
        if (!Set.of("http", "https").contains(origin.getScheme()) || origin.getAuthority() == null
            || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
            || (origin.getPath() != null && !origin.getPath().isEmpty() && !origin.getPath().equals("/"))) {
            throw new IllegalArgumentException("web-clear-storage origin must contain only http(s) scheme and authority");
        }
        web.clearStorage(args, timeout);
        Map<String, String> details = new LinkedHashMap<>(webInspection(args, timeout));
        details.put("origin", WebViewState.safeUrl(args.get("origin")));
        details.put("storageTypes", args.get("types"));
        details.put("values", "hidden");
        return Map.copyOf(details);
    }

    private Map<String, String> webAssertInterface(Map<String, String> args, long timeout) throws Exception {
        String name = args.get("name");
        List<String> methods = args.containsKey("methods")
            ? Arrays.stream(args.get("methods").split(",")).map(String::strip).filter(s -> !s.isBlank()).toList()
            : args.containsKey("method") ? List.of(args.get("method")) : List.of();
        boolean expected = Boolean.parseBoolean(args.getOrDefault("exists", "true"));
        String source = args.getOrDefault("source", "any");
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        do {
            RunCancellation.checkCurrent();
            boolean present;
            WebViewState state = web.inspect(args, Math.max(1, remainingMillis(deadline)));
            if (source.equals("agent")) {
                present = state.bridges().stream().filter(bridge -> bridge.source().equals("agent"))
                    .anyMatch(bridge -> bridge.name().equals(name) && bridge.methods().containsAll(methods));
            } else {
                present = web.interfaceExists(args, name, null, Math.max(1, remainingMillis(deadline)));
                for (String method : methods) {
                    present &= web.interfaceExists(args, name, method, Math.max(1, remainingMillis(deadline)));
                }
            }
            if (present == expected) {
                Map<String, String> details = new LinkedHashMap<>(rememberWebState(state));
                details.put("interface", name); details.put("source", source);
                details.put("methodCount", Integer.toString(methods.size()));
                return Map.copyOf(details);
            }
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("JavaScript interface assertion did not match: " + name);
    }

    private Map<String, String> webAssertSecureContext(Map<String, String> args, long timeout) throws Exception {
        boolean expected = Boolean.parseBoolean(args.get("value"));
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        do {
            RunCancellation.checkCurrent();
            WebViewState state = web.inspect(args, Math.max(1, remainingMillis(deadline)));
            if (state.security().secureContext() == expected) return rememberWebState(state);
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("WebView secure-context assertion did not match");
    }

    private Map<String, String> webSnapshot(Map<String, String> args, int index, long timeout)
        throws Exception {
        WebViewState state = web.inspect(args, timeout);
        WebViewSnapshot snapshot = state.sanitized();
        String name = args.getOrDefault("name", "web-state-" + String.format("%02d", index + 1));
        String file = safeFile(name) + ".webview.json";
        Files.writeString(root.resolve("evidence").resolve(file), WebViewInspector.json(snapshot),
            StandardCharsets.UTF_8);
        Map<String, String> details = new LinkedHashMap<>(rememberWebState(state));
        details.put("evidence", "evidence/" + file);
        details.put("cookies", Integer.toString(snapshot.cookies().size()));
        details.put("storageKeys", Integer.toString(snapshot.storage().size()));
        details.put("bridges", Integer.toString(snapshot.bridges().size()));
        details.put("values", "hidden");
        return Map.copyOf(details);
    }

    private Map<String, String> webInspection(Map<String, String> args, long timeout) throws Exception {
        return rememberWebState(web.inspect(args, timeout));
    }

    private Map<String, String> rememberWebState(WebViewState state) {
        Map<String, String> details = new LinkedHashMap<>(webSummary(state));
        List<String> changes = webChanges(previousWebState, state);
        if (!changes.isEmpty()) details.put("webChanges", String.join("; ", changes));
        previousWebState = state;
        return Map.copyOf(details);
    }

    private static List<String> webChanges(WebViewState before, WebViewState after) {
        if (before == null) return List.of();
        List<String> changes = new ArrayList<>();
        Map<String, WebViewState.Cookie> oldCookies = new LinkedHashMap<>();
        for (WebViewState.Cookie cookie : before.cookies()) oldCookies.put(cookieKey(cookie), cookie);
        Map<String, WebViewState.Cookie> newCookies = new LinkedHashMap<>();
        for (WebViewState.Cookie cookie : after.cookies()) newCookies.put(cookieKey(cookie), cookie);
        for (String key : newCookies.keySet()) {
            if (!oldCookies.containsKey(key)) changes.add("cookie added:" + newCookies.get(key).name());
            else if (!Objects.equals(oldCookies.get(key).value(), newCookies.get(key).value())) {
                changes.add("cookie changed:" + newCookies.get(key).name());
            }
        }
        for (String key : oldCookies.keySet()) if (!newCookies.containsKey(key)) {
            changes.add("cookie removed:" + oldCookies.get(key).name());
        }
        Map<String, WebViewState.Storage> oldStorage = new LinkedHashMap<>();
        for (WebViewState.Storage entry : before.storage()) oldStorage.put(storageKey(entry), entry);
        Map<String, WebViewState.Storage> newStorage = new LinkedHashMap<>();
        for (WebViewState.Storage entry : after.storage()) newStorage.put(storageKey(entry), entry);
        for (String key : newStorage.keySet()) {
            WebViewState.Storage entry = newStorage.get(key);
            if (!oldStorage.containsKey(key)) changes.add("storage added:" + entry.area() + "/" + entry.key());
            else if (!Objects.equals(oldStorage.get(key).value(), entry.value())) {
                changes.add("storage changed:" + entry.area() + "/" + entry.key());
            }
        }
        for (String key : oldStorage.keySet()) if (!newStorage.containsKey(key)) {
            WebViewState.Storage entry = oldStorage.get(key);
            changes.add("storage removed:" + entry.area() + "/" + entry.key());
        }
        return List.copyOf(changes);
    }

    private static String cookieKey(WebViewState.Cookie cookie) {
        return cookie.domain() + "\n" + cookie.path() + "\n" + cookie.name();
    }

    private static String storageKey(WebViewState.Storage entry) {
        return entry.area() + "\n" + entry.origin() + "\n" + entry.key();
    }

    private static Map<String, String> webSummary(WebViewState state) {
        WebViewSnapshot snapshot = state.sanitized();
        return Map.of("webTargetUrl", snapshot.targetUrl(),
            "cookies", Integer.toString(snapshot.cookies().size()),
            "storageKeys", Integer.toString(snapshot.storage().size()),
            "bridges", Integer.toString(snapshot.bridges().size()), "values", "hidden");
    }

    private static void requireCapability(WebViewState state, String prefix) {
        state.warnings().stream().filter(warning -> warning.startsWith(prefix)).findFirst()
            .ifPresent(warning -> { throw new IllegalStateException(warning); });
    }

    private static boolean cookieDomainMatches(String actual, String expected) {
        String a = actual.startsWith(".") ? actual.substring(1) : actual;
        String e = expected.startsWith(".") ? expected.substring(1) : expected;
        return a.equalsIgnoreCase(e) || a.toLowerCase(Locale.ROOT).endsWith("." + e.toLowerCase(Locale.ROOT));
    }

    private Map<String, String> resetPermissions() throws Exception {
        var dump = adb.run(List.of("-s", device.serial(), "shell", "dumpsys", "package", test.packageName()));
        if (dump.exitCode() != 0) throw new IOException("Could not inspect target app permissions");
        int revoked = 0;
        for (String line : dump.stdout().split("\\R")) {
            Matcher matcher = GRANTED.matcher(line); if (!matcher.matches()) continue;
            var result = adb.run(List.of("-s", device.serial(), "shell", "pm", "revoke", test.packageName(), matcher.group(1)));
            if (result.exitCode() == 0) revoked++;
        }
        return Map.of("package", test.packageName(), "revokedCount", "" + revoked);
    }

    private void permission(String action, String permission) throws Exception {
        var result = adb.run(List.of("-s", device.serial(), "shell", "pm", action, test.packageName(), permission));
        String output = (result.stdout() + result.stderr()).strip();
        if (result.exitCode() != 0 && !(action.equals("revoke") && output.toLowerCase().contains("not granted"))) throw new IOException("ADB permission " + action + " failed: " + output);
    }

    private void preflight() throws IOException {
        FlowFiles.validate(test.sourceDirectory(), test.steps());
        for (FlowTest.Requirement requirement : test.requirements()) values.require(requirement);
        for (FlowTest.Step step : test.steps()) if (step.action().equals("secret")) {
            values.bindSecret(step.arguments().get("name"), step.arguments().get("source"));
        }
        for (FlowTest.Step step : test.steps()) {
            if (Set.of("set-install-referrer", "clear-install-referrer").contains(step.action())
                && test.mode() != CaptureMode.INSTRUMENTED) {
                throw new IllegalArgumentException(step.action()
                    + " requires mode instrumented and an app-owned referrer abstraction");
            }
            if (step.action().equals("web-assert-interface")
                && step.arguments().getOrDefault("source", "any").equals("agent")
                && test.mode() != CaptureMode.INSTRUMENTED) {
                throw new IllegalArgumentException(
                    "web-assert-interface source=agent requires mode instrumented");
            }
            for (String value : step.arguments().values()) values.preflightReferences(value);
            String bodyFile = step.arguments().get("body-file");
            if (bodyFile != null) {
                Path file = resolveBodyFile(values.interpolate(bodyFile));
                if (!Files.isRegularFile(file)) throw new IllegalArgumentException("API body file not found: " + bodyFile);
                values.preflightReferences(Files.readString(file, StandardCharsets.UTF_8));
            }
            String apkFile = step.arguments().get("file");
            if (step.action().equals("install-apk") && apkFile != null) {
                Path file = resolveArtifactFile(values.interpolate(apkFile), ".apk");
                if (!Files.isRegularFile(file)) {
                    throw new IllegalArgumentException("APK file not found: " + apkFile);
                }
                try {
                    String actualPackage = apkInspector.packageName(file);
                    if (!test.packageName().equals(actualPackage)) {
                        throw new IllegalArgumentException("APK package " + actualPackage
                            + " does not match test package " + test.packageName());
                    }
                } catch (IllegalArgumentException mismatch) {
                    throw mismatch;
                } catch (Exception inspection) {
                    throw new IllegalArgumentException(inspection.getMessage(), inspection);
                }
            }
        }
    }

    private Path resolveBodyFile(String raw) {
        return resolveRelativeFile(raw, "body-file");
    }

    private Path resolveArtifactFile(String raw, String extension) {
        Path resolved = resolveRelativeFile(raw, "artifact file");
        if (!resolved.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw new IllegalArgumentException("artifact file must end with " + extension);
        }
        return resolved;
    }

    private Path resolveRelativeFile(String raw, String label) {
        return FlowFiles.resolve(test.sourceDirectory(), raw, label);
    }

    private void launch() throws Exception {
        var resolved = adb.run(List.of("-s", device.serial(), "shell", "cmd", "package", "resolve-activity", "--brief", test.packageName()));
        String component = resolved.stdout().lines().filter(l -> l.contains("/")).reduce((a, b) -> b).orElseThrow(() -> new IllegalStateException("No launchable activity for " + test.packageName()));
        command("shell", "am", "start", "-W", "-n", component.strip());
    }

    private static URI requireLink(String raw) {
        URI uri;
        try { uri = URI.create(raw); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid deep-link URI"); }
        if (uri.getScheme() == null || uri.getScheme().isBlank()) {
            throw new IllegalArgumentException("Deep-link URI requires a scheme");
        }
        return uri;
    }

    private static String safeLink(URI uri) {
        StringBuilder safe = new StringBuilder(uri.getScheme()).append(':');
        if (uri.getRawAuthority() != null) safe.append("//").append(uri.getRawAuthority());
        if (uri.getRawPath() != null) safe.append(uri.getRawPath());
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) safe.append("?{redacted}");
        return safe.toString();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long remainingMillis(long deadlineNanos) {
        return Duration.ofNanos(Math.max(1, deadlineNanos - System.nanoTime())).toMillis();
    }

    private UiHierarchy.UiNode requireNode(Map<String, String> args, long timeout) throws Exception {
        var found = findNode(args, timeout); if (found != null) return found;
        if (opaqueWebView()) {
            throw new IllegalStateException("Element did not become visible because the WebView exposes no useful accessibility nodes; use web-* actions with a debuggable WebView");
        }
        throw new IllegalStateException("Element did not become visible: " + selectorDescription(args));
    }

    private boolean opaqueWebView() {
        try {
            List<UiHierarchy.UiNode> nodes = UiHierarchy.parse(dumpHierarchy());
            boolean hasWebView = nodes.stream().anyMatch(node -> node.className().endsWith("WebView"));
            long semantic = nodes.stream().filter(node -> !node.className().endsWith("WebView"))
                .filter(node -> !node.text().isBlank() || !node.description().isBlank()
                    || (!node.id().isBlank() && !node.id().equals(test.packageName() + ":id/webview")))
                .count();
            return hasWebView && semantic == 0;
        } catch (Exception ignored) { return false; }
    }
    private UiHierarchy.UiNode findNode(Map<String, String> args, long timeout) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        boolean first = true;
        do {
            RunCancellation.checkCurrent();
            // Only the first look may reuse the dump settle() just took; later polls must see live UI.
            byte[] dump = first ? currentHierarchy() : dumpHierarchy();
            first = false;
            lookupNodes = UiHierarchy.parse(dump);
            var found = UiHierarchy.find(lookupNodes, args);
            if (found != null) return found;
            if (timeout > 0) Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        return null;
    }
    private void requireAbsent(Map<String, String> args, long timeout) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        boolean first = true;
        do {
            RunCancellation.checkCurrent();
            byte[] dump = first ? currentHierarchy() : dumpHierarchy();
            first = false;
            if (UiHierarchy.find(UiHierarchy.parse(dump), args) == null) return;
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Element remained visible: " + selectorDescription(args));
    }
    private byte[] dumpHierarchy() throws Exception {
        long begin = System.nanoTime();
        try {
            return readHierarchy();
        } finally {
            dumpCount++;
            dumpNanos += System.nanoTime() - begin;
        }
    }

    /** The hierarchy settle() last observed if it is still fresh, otherwise a new dump. */
    private byte[] currentHierarchy() throws Exception {
        byte[] cached = settledDump;
        if (cached != null && System.nanoTime() - settledAtNanos < SETTLED_DUMP_MAX_AGE_NANOS) return cached;
        return dumpHierarchy();
    }

    /**
     * Dumps the hierarchy into a cleared file. `uiautomator dump` can exit 0 without writing it
     * ("null root node", "could not get idle state") while a window is starting or animating, so a
     * missing or non-XML file is retried a few times before it fails.
     */
    private byte[] readHierarchy() throws Exception {
        String detail = "";
        for (int attempt = 1; attempt <= DUMP_ATTEMPTS; attempt++) {
            var removed = adb.run(List.of("-s", device.serial(), "shell", "rm", "-f", "/data/local/tmp/flowscope-test-window.xml"));
            if (removed.exitCode() != 0) throw new IOException("Could not clear previous UI hierarchy");
            var dump = adb.run(List.of("-s", device.serial(), "shell", "uiautomator", "dump", "/data/local/tmp/flowscope-test-window.xml"), AdbClient.DUMP_TIMEOUT_MILLIS);
            if (dump.exitCode() != 0) throw new IOException("UI hierarchy failed: " + (dump.stdout() + dump.stderr()).strip());
            byte[] xml = adb.execOut(device.serial(), "cat", "/data/local/tmp/flowscope-test-window.xml");
            if (looksLikeXml(xml)) return xml;
            detail = (dump.stdout() + dump.stderr()).strip();
            if (attempt < DUMP_ATTEMPTS) Thread.sleep(DUMP_RETRY_MILLIS);
        }
        throw new IOException("UI hierarchy was not written after " + DUMP_ATTEMPTS + " attempts: " + detail);
    }

    private static boolean looksLikeXml(byte[] bytes) {
        for (byte b : bytes) {
            if (b == '<') return true;
            if (!Character.isWhitespace(b) && b != (byte) 0xEF && b != (byte) 0xBB && b != (byte) 0xBF) return false;
        }
        return false;
    }
    /**
     * Waits for the UI to stop changing after a mutation and remembers the final hierarchy so the next
     * lookup need not dump again. Consecutive hierarchies are compared for up to 1.5 s; when one dump
     * alone takes that long it has already watched the UI for the whole window, so a second dump would
     * only add latency.
     */
    private void settle() {
        settledDump = null;
        try {
            long begin = System.nanoTime();
            byte[] old = dumpHierarchy();
            if (System.nanoTime() - begin >= SETTLE_WINDOW_NANOS) { rememberSettled(old); return; }
            long end = begin + SETTLE_WINDOW_NANOS;
            do {
            RunCancellation.checkCurrent();
                Thread.sleep(250);
                byte[] now = dumpHierarchy();
                if (Arrays.equals(old, now)) { rememberSettled(now); return; }
                old = now;
            } while (System.nanoTime() < end);
            rememberSettled(old);
        } catch (Exception ignored) {}
    }

    private void rememberSettled(byte[] dump) { settledDump = dump; settledAtNanos = System.nanoTime(); }
    private void typeText(String value) throws Exception { if (!value.matches("[a-zA-Z0-9@._+ -]*")) throw new IllegalArgumentException("type currently supports letters, numbers, spaces, and @ . _ + -"); shell("input", "text", value.replace(" ", "%s")); }
    private void scroll(String direction) throws Exception {
        var sizeResult = adb.run(List.of("-s", device.serial(), "shell", "wm", "size")); Matcher size = Pattern.compile("(\\d+)x(\\d+)").matcher(sizeResult.stdout());
        if (!size.find()) throw new IOException("Could not determine device size"); int w = Integer.parseInt(size.group(1)), h = Integer.parseInt(size.group(2)); int sx=w/2,sy=h/2,ex=sx,ey=sy;
        switch(direction){case"up"->{sy+=h/3;ey-=h/3;}case"down"->{sy-=h/3;ey+=h/3;}case"left"->{sx+=w/3;ex-=w/3;}case"right"->{sx-=w/3;ex+=w/3;}default->throw new IllegalArgumentException("Unknown scroll direction: "+direction);} shell("input","swipe",""+sx,""+sy,""+ex,""+ey,"450");
    }
    private static final Map<String, String> KEYCODES = Map.of(
        "enter", "KEYCODE_ENTER", "search", "KEYCODE_SEARCH", "delete", "KEYCODE_DEL", "tab", "KEYCODE_TAB",
        "home", "KEYCODE_HOME", "app-switch", "KEYCODE_APP_SWITCH",
        "volume-up", "KEYCODE_VOLUME_UP", "volume-down", "KEYCODE_VOLUME_DOWN");
    private int[] screenSize;

    private static String pressKeycode(String key) {
        String keycode = KEYCODES.get(key);
        if (keycode == null) throw new IllegalArgumentException("Unsupported key: " + key);
        return keycode;
    }

    /** An optional numeric argument, checked again here because `${var.x}` values are only known at run time. */
    private static long boundedArg(Map<String, String> args, String key, long fallback, long min, long max) {
        String raw = args.get(key);
        if (raw == null) return fallback;
        long value;
        try {
            value = Long.parseLong(raw.strip());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return value;
    }

    /** Holds a finger on the element: a zero-length `input swipe` lasts exactly {@code duration} ms. */
    private Map<String, String> longPress(Map<String, String> args, long timeout) throws Exception {
        var node = requireNode(args, timeout);
        long duration = boundedArg(args, "duration", 800,
            FlowTestParser.LONG_PRESS_MIN_MILLIS, FlowTestParser.LONG_PRESS_MAX_MILLIS);
        int x = node.tapX(args), y = node.centerY();
        ensureUncovered(node, x);
        shell("input", "swipe", "" + x, "" + y, "" + x, "" + y, "" + duration);
        settle();
        return Map.of("duration", "" + duration);
    }

    private Map<String, String> swipeStep(Map<String, String> args, long timeout) throws Exception {
        int percent = (int) boundedArg(args, "distance", 50,
            FlowTestParser.SWIPE_MIN_PERCENT, FlowTestParser.SWIPE_MAX_PERCENT);
        long duration = boundedArg(args, "duration", 300,
            FlowTestParser.SWIPE_MIN_MILLIS, FlowTestParser.SWIPE_MAX_MILLIS);
        var anchor = hasSelector(args) ? requireNode(args, timeout) : null;
        swipe(args.get("direction"), anchor == null ? null : anchor.centerX(),
            anchor == null ? null : anchor.centerY(), percent, duration);
        settle();
        return Map.of("direction", args.get("direction"), "distance", percent + "%");
    }

    /**
     * Swipes by {@code percent} of the screen along the axis. The finger moves in {@code direction}
     * (like `scroll`): without an anchor the path is centred on the screen, with one it starts there.
     */
    private void swipe(String direction, Integer anchorX, Integer anchorY, int percent, long millis) throws Exception {
        if (screenSize == null) {
            screenSize = adb.displaySize(device.serial());
            if (screenSize == null) throw new IOException("Could not determine device size");
        }
        int w = screenSize[0], h = screenSize[1], dx = 0, dy = 0;
        switch (direction) {
            case "up" -> dy = -h * percent / 100;
            case "down" -> dy = h * percent / 100;
            case "left" -> dx = -w * percent / 100;
            case "right" -> dx = w * percent / 100;
            default -> throw new IllegalArgumentException("Unknown swipe direction: " + direction);
        }
        int sx = anchorX == null ? w / 2 - dx / 2 : anchorX;
        int sy = anchorY == null ? h / 2 - dy / 2 : anchorY;
        int ex = Math.max(1, Math.min(w - 2, sx + dx)), ey = Math.max(1, Math.min(h - 2, sy + dy));
        shell("input", "swipe", "" + sx, "" + sy, "" + ex, "" + ey, "" + millis);
    }

    /**
     * Swipes until the element is visible. Stops at {@code max-swipes}, or earlier when a swipe leaves
     * the scroll container children unchanged across fresh dumps (the end of the content), so it can never scroll forever.
     * With {@code from-id} every swipe starts at that element's centre, so a pinned panel over the
     * screen's lower half cannot swallow it. With {@code clear-of-id} (comma-separated ids) the element only counts once it
     * overlaps none of them (panels or floating buttons drawn over the content), so a later tap reaches it.
     * {@code distance} and {@code duration} are as for `swipe`; shorter, slower swipes fling less.
     */
    private Map<String, String> scrollTo(Map<String, String> args) throws Exception {
        String direction = args.getOrDefault("direction", "up");
        int maxSwipes = (int) boundedArg(args, "max-swipes", 10, 1, FlowTestParser.SCROLL_TO_MAX_SWIPES_LIMIT);
        String fromId = args.get("from-id");
        String clearOfId = args.get("clear-of-id");
        int percent = (int) boundedArg(args, "distance", 50,
            FlowTestParser.SWIPE_MIN_PERCENT, FlowTestParser.SWIPE_MAX_PERCENT);
        long millis = boundedArg(args, "duration", 1000, FlowTestParser.SWIPE_MIN_MILLIS, FlowTestParser.SWIPE_MAX_MILLIS);
        String previous = null;
        for (int swipes = 0; ; swipes++) {
            byte[] dump = dumpHierarchy();
            var nodes = UiHierarchy.parse(dump);
            var target = UiHierarchy.find(nodes, args);
            if (target != null && !covered(target, nodes, clearOfId)
                && UiHierarchy.covering(nodes, target, target.tapX(args), target.centerY()) == null) {
                rememberSettled(dump);
                return Map.of("swipes", "" + swipes);
            }
            if (swipes >= maxSwipes) {
                throw new IllegalStateException("Element not found after " + swipes + " swipes: " + selectorDescription(args));
            }
            var anchor = fromId == null ? null : UiHierarchy.find(nodes, Map.of("id", fromId));
            if (fromId != null && anchor == null) {
                throw new IllegalStateException("scroll-to start element not found: id=" + fromId);
            }
            var container = scrollContainer(nodes, anchor, args.get("within-id"));
            String signature = container == null ? null : UiHierarchy.contentSignature(nodes, container);
            if (signature != null && !signature.isEmpty() && signature.equals(previous)) {
                // A cached/transient dump is not proof of the end: confirm with another fresh dump.
                Thread.sleep(POLL_MILLIS);
                var confirmedNodes = UiHierarchy.parse(dumpHierarchy());
                var confirmedAnchor = fromId == null ? null : UiHierarchy.find(confirmedNodes, Map.of("id", fromId));
                var confirmedContainer = scrollContainer(confirmedNodes, confirmedAnchor, args.get("within-id"));
                var confirmedTarget = UiHierarchy.find(confirmedNodes, args);
                if (confirmedTarget == null && confirmedContainer != null
                    && signature.equals(UiHierarchy.contentSignature(confirmedNodes, confirmedContainer))) {
                    throw new IllegalStateException("Reached the end of the content after " + swipes
                        + " swipes without finding: " + selectorDescription(args));
                }
                if (confirmedTarget != null && !covered(confirmedTarget, confirmedNodes, clearOfId)
                    && UiHierarchy.covering(confirmedNodes, confirmedTarget, confirmedTarget.tapX(args), confirmedTarget.centerY()) == null) {
                    return Map.of("swipes", "" + swipes);
                }
            }
            previous = signature;
            swipe(direction, anchor == null ? null : anchor.centerX(), anchor == null ? null : anchor.centerY(), percent, millis);
            settle();
        }
    }

    private static UiHierarchy.UiNode scrollContainer(List<UiHierarchy.UiNode> nodes,
        UiHierarchy.UiNode anchor, String withinId) {
        if (anchor != null) {
            for (var node = anchor; node != null; node = node.parent()) if (node.scrollable()) return node;
        }
        if (withinId != null) {
            var scope = UiHierarchy.find(nodes, Map.of("id", withinId));
            if (scope != null && scope.scrollable()) return scope;
        }
        return nodes.stream().filter(n -> n.scrollable() && n.hasArea())
            .filter(n -> withinId == null || n.id().equals(withinId) || n.matches(Map.of("within-id", withinId)))
            .filter(n -> anchor == null || n.contains(anchor.centerX(), anchor.centerY()))
            .max(Comparator.comparingLong(UiHierarchy.UiNode::area)).orElse(null);
    }

    /** Whether any element named in the comma-separated {@code clearOfIds} overlaps {@code target}. */
    private static boolean covered(UiHierarchy.UiNode target, List<UiHierarchy.UiNode> nodes, String clearOfIds) {
        if (clearOfIds == null) return false;
        for (String id : clearOfIds.split(",")) {
            var cover = UiHierarchy.find(nodes, Map.of("id", id.trim()));
            if (cover != null && overlaps(target, cover)) return true;
        }
        return false;
    }

    private static boolean overlaps(UiHierarchy.UiNode a, UiHierarchy.UiNode b) {
        return a.left() < b.right() && b.left() < a.right() && a.top() < b.bottom() && b.top() < a.bottom();
    }

    /** Taps the field, moves to the end of its text, and deletes as many characters as it holds. */
    private Map<String, String> clearText(Map<String, String> args, long timeout) throws Exception {
        var node = requireNode(args, timeout);
        ensureUncovered(node, node.centerX());
        shell("input", "tap", "" + node.centerX(), "" + node.centerY());
        // Ctrl+End jumps to the end of multi-line text on Android 11+; older builds ignore it, and
        // End still reaches the end of the current line.
        adb.run(List.of("-s", device.serial(), "shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_MOVE_END"));
        shell("input", "keyevent", "KEYCODE_MOVE_END");
        int length = node.text().length();
        for (int left = length; left > 0; left -= 100) {
            String[] command = new String[2 + Math.min(100, left)];
            command[0] = "input";
            command[1] = "keyevent";
            Arrays.fill(command, 2, command.length, "KEYCODE_DEL");
            shell(command);
        }
        settle();
        return Map.of("deleted", "" + length);
    }

    /** Sends Back only while the soft keyboard is showing, so it can never navigate away by accident. */
    private Map<String, String> hideKeyboard() throws Exception {
        boolean shown = adb.run(List.of("-s", device.serial(), "shell", "dumpsys", "input_method"))
            .stdout().contains("mInputShown=true");
        if (shown) { shell("input", "keyevent", "KEYCODE_BACK"); settle(); }
        return Map.of("keyboard", shown ? "hidden" : "not-shown");
    }

    private void ensureUncovered(UiHierarchy.UiNode node, int x) {
        var cover = UiHierarchy.covering(lookupNodes, node, x, node.centerY());
        if (cover != null) throw new IllegalStateException("Target is covered by "
            + (cover.id().isBlank() ? cover.className() : cover.id()));
    }

    static boolean uiStep(FlowTest.Step step) {
        return REUSES_SETTLED_DUMP.contains(step.action()) || step.action().startsWith("web-")
            || Set.of("if-visible", "screenshot-if-visible", "scroll", "back", "press-key", "hide-keyboard").contains(step.action());
    }

    private void checkReadiness(FlowTest.Step step) throws Exception {
        if (!readinessChecked) {
            readinessChecked = true;
            DeviceReadiness.beforeSetup(adb, device, test);
        }
        if (!foregroundChecked && uiStep(step)) {
            DeviceReadiness.check(adb, device.serial(), test.packageName(), true);
            foregroundChecked = true;
        }
    }

    private void retainHierarchy(int index, Map<String, String> details) {
        if (!keepHierarchy) return;
        String path = "evidence/step-" + String.format("%02d", index + 1) + ".xml";
        try {
            Files.write(root.resolve(path), UiHierarchy.redact(currentHierarchy(), this::redact));
            details.put("hierarchy", path);
        } catch (Exception failure) {
            details.put("hierarchyError", redact(message(failure)));
        }
    }

    private void captureEvidence(String name) { try { captureScreenshot(name); } catch(Exception ignored){} try { Files.write(root.resolve("evidence").resolve(name+".xml"),UiHierarchy.redact(dumpHierarchy(), this::redact)); } catch(Exception ignored){} }
    private void captureScreenshot(String name) throws Exception { Files.write(root.resolve("evidence").resolve(safeFile(name)+".png"),adb.execOut(device.serial(),"screencap","-p")); }
    private void shell(String... args) throws Exception { String[] command=new String[args.length+1];command[0]="shell";System.arraycopy(args,0,command,1,args.length);command(command); }
    private void command(String... args) throws Exception { settledDump = null; List<String> command=new ArrayList<>(List.of("-s",device.serial()));command.addAll(List.of(args));var result=adb.run(command);if(result.exitCode()!=0)throw new IOException("ADB failed: "+(result.stdout()+result.stderr()).strip()); }


    private List<StepResult> slowestSteps() {
        return results.stream().sorted(Comparator.comparingLong((StepResult r) -> r.durationMillis).reversed())
            .limit(5).toList();
    }

    private String performanceJson(Instant started, Instant ended) {
        StringBuilder out = new StringBuilder("{\"totalMillis\": ")
            .append(Duration.between(started, ended).toMillis())
            .append(", \"uiDumps\": ").append(dumpCount)
            .append(", \"uiDumpMillis\": ").append(Duration.ofNanos(dumpNanos).toMillis())
            .append(", \"slowestSteps\": [");
        List<StepResult> slow = slowestSteps();
        for (int i = 0; i < slow.size(); i++) {
            StepResult r = slow.get(i);
            if (i > 0) out.append(", ");
            out.append("{\"number\": ").append(r.number).append(", \"action\": ").append(Json.quote(r.step.action())).append(FlowLabel.json(redact(r.step.label())))
                .append(", \"durationMillis\": ").append(r.durationMillis).append("}");
        }
        return out.append("]}").toString();
    }

    private String performanceHtml(Instant started, Instant ended) {
        long total = Duration.between(started, ended).toMillis();
        long dumpMillis = Duration.ofNanos(dumpNanos).toMillis();
        StringBuilder rows = new StringBuilder();
        for (StepResult r : slowestSteps()) {
            rows.append("<tr><td class=\"num\">#").append(r.number).append("</td><td class=\"name\">")
                .append(html(redact(r.step.displayName()))).append("</td><td class=\"dur\">")
                .append(formatMillis(r.durationMillis)).append(ReportHtml.bar(r.durationMillis, total))
                .append("</td><td class=\"dur\">").append(r.dumps).append(" · ")
                .append(formatMillis(r.dumpMillis)).append("</td></tr>");
        }
        String share = total > 0 ? Math.round(100.0 * dumpMillis / total) + "% of the run" : "";
        String note = dumpCount + " UI hierarchy dump" + (dumpCount == 1 ? "" : "s") + " took "
            + formatMillis(dumpMillis) + (share.isEmpty() ? "" : " · " + share)
            + ". Dumps are the dominant cost of a run, so the slowest steps are usually the ones that looked hardest for an element.";
        String body = "<div class=\"body\"><p>" + html(note) + "</p></div>" + (rows.isEmpty() ? ""
            : ReportHtml.table("<th>#</th><th>Slowest steps</th><th>Duration</th><th>UI dumps</th>", rows.toString()));
        return ReportHtml.card("performance", "Performance", null, formatMillis(total) + " total", body);
    }

    /** The step whose window contains the event, else the latest step that had started by then. */
    private Integer stepAt(Instant at) {
        Integer latest = null;
        Instant latestStart = null;
        for (var entry : stepWindows.entrySet()) {
            Instant start = entry.getValue()[0];
            if (!start.isAfter(at) && (latestStart == null || start.isAfter(latestStart))) {
                latest = entry.getKey();
                latestStart = start;
            }
        }
        return latest;
    }

    private String agentEventsJson(Instant started) {
        if (agentEvents == null) {
            return "{\"captured\": false, \"note\": \"zero-code mode does not use the agent\"}";
        }
        if (!agentEvents.captured()) {
            return "{\"captured\": false, \"note\": " + Json.quote(agentEvents.unavailableReason()) + "}";
        }
        StringBuilder out = new StringBuilder("{\"captured\": true, \"counts\": {");
        int c = 0;
        for (var entry : agentEvents.counts().entrySet()) {
            if (c++ > 0) out.append(", ");
            out.append(Json.quote(entry.getKey())).append(": ").append(entry.getValue());
        }
        out.append("}, \"events\": [");
        List<AgentEventCapture.Event> events = agentEvents.events();
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            Integer step = stepAt(event.arrivedAt());
            if (i > 0) out.append(", ");
            out.append("{\"offsetMillis\": ").append(Duration.between(started, event.arrivedAt()).toMillis())
                .append(", \"step\": ").append(step == null ? "null" : step)
                .append(", \"kind\": ").append(Json.quote(event.kind()))
                .append(", \"summary\": ").append(Json.quote(event.summary())).append("}");
        }
        return out.append("]}").toString();
    }

    private String agentEventsHtml(Instant started) {
        if (agentEvents == null) return "";
        if (!agentEvents.captured()) {
            return ReportHtml.card("agent", "Agent events", null, "not captured",
                ReportHtml.empty("Not captured: " + agentEvents.unavailableReason() + "."));
        }
        StringBuilder summary = new StringBuilder();
        int kinds = 0;
        for (var entry : agentEvents.counts().entrySet()) {
            if (kinds++ > 0) summary.append(" · ");
            summary.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        List<AgentEventCapture.Event> events = agentEvents.events();
        if (kinds == 0) {
            return ReportHtml.card("agent", "Agent events", null, "0 events",
                ReportHtml.empty("No events arrived. Is the debug agent in this build?"));
        }
        StringBuilder rows = new StringBuilder();
        for (var event : events) {
            Integer step = stepAt(event.arrivedAt());
            rows.append("<tr><td class=\"num\">+")
                .append(formatMillis(Duration.between(started, event.arrivedAt()).toMillis()))
                .append("</td><td class=\"num\">").append(step == null ? "" : "#" + step)
                .append("</td><td class=\"name\">").append(html(event.kind()))
                .append("</td><td class=\"sub\">").append(html(event.summary())).append("</td></tr>");
        }
        String body = "<div class=\"body\"><p>" + html(summary.toString()) + "</p></div>"
            + (rows.isEmpty() ? "" : ReportHtml.table(
                "<th>Time</th><th>Step</th><th>Kind</th><th>Event</th>", rows.toString()));
        return ReportHtml.card("agent", "Agent events",
            "Instrumented events the debug agent streamed while the run executed.",
            events.size() + (events.size() == 1 ? " event" : " events"), body);
    }

    private void writeReports(boolean passed, Instant started, Instant ended) throws IOException {
        Files.writeString(root.resolve("report.json"), reportJson(passed, started, ended), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("report.html"), reportHtml(passed, started, ended), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("junit.xml"), junitXml(passed, Duration.between(started, ended).toMillis()), StandardCharsets.UTF_8);
    }
    private String reportJson(boolean passed, Instant started, Instant ended) {
        // Both read the shared phase-aware API, so the report, junit.xml and the console agree
        // instead of spelling the same failure three different ways.
        String primary=primaryFailure(); List<String> teardown=teardownFailures();
        StringBuilder out=new StringBuilder("{\n  \"schemaVersion\": 2,\n  \"kind\": \"test\",\n").append("  \"name\": ").append(Json.quote(test.name())).append(",\n  \"packageName\": ").append(Json.quote(test.packageName())).append(",\n  \"mode\": ").append(Json.quote(test.mode().cliName())).append(",\n  \"deviceSerial\": ").append(Json.quote(device.serial())).append(",\n  \"startedAt\": ").append(Json.quote(started.toString())).append(",\n  \"endedAt\": ").append(Json.quote(ended.toString())).append(",\n  \"status\": ").append(Json.quote(status(passed))).append(",\n  \"primaryFailure\": ").append(Json.quote(primary)).append(",\n  \"teardownFailures\": [");
        for(int i=0;i<teardown.size();i++){if(i>0)out.append(", ");out.append(Json.quote(teardown.get(i)));} out.append("],\n  \"webView\": ").append(previousWebState == null ? "null" : WebViewInspector.json(previousWebState.sanitized())).append(",\n  \"steps\": [\n");
        for(int i=0;i<results.size();i++){var r=results.get(i);out.append("    {\"number\": ").append(r.number).append(", \"line\": ").append(r.step.line()).append(", \"phase\": ").append(Json.quote(r.step.phase().value())).append(", \"action\": ").append(Json.quote(r.step.action())).append(FlowLabel.json(redact(r.step.label()))).append(", \"status\": ").append(Json.quote(r.status)).append(", \"durationMillis\": ").append(r.durationMillis).append(", \"uiDumps\": ").append(r.dumps).append(", \"uiDumpMillis\": ").append(r.dumpMillis).append(", \"error\": ").append(Json.quote(r.error)).append(", \"details\": {");int d=0;for(var e:r.details.entrySet()){if(d++>0)out.append(", ");out.append(Json.quote(e.getKey())).append(": ").append(Json.quote(e.getValue()));}out.append("}}");if(i+1<results.size())out.append(',');out.append('\n');} out.append("  ],\n  \"performance\": ").append(performanceJson(started, ended));
        out.append(",\n  \"agentEvents\": ").append(agentEventsJson(started));
        return out.append("\n}\n").toString();
    }
    private String reportHtml(boolean passed, Instant started, Instant ended) {
        String status = status(passed);
        long total = Duration.between(started, ended).toMillis();
        List<Shot> shots = screenshots();
        return ReportHtml.document(test.name(), ReportHtml.hero("Flow test", test.name(), status,
            failureMessage(), ReportHtml.meta(
                "package=" + test.packageName(),
                "mode=" + test.mode().cliName(),
                "device=" + device.serial(),
                "started=" + ReportHtml.time(started),
                "ended=" + ReportHtml.time(ended)))
            + ReportHtml.stats(List.of(
                new ReportHtml.Stat("" + count("passed"), "Passed", "passed"),
                new ReportHtml.Stat("" + count("failed"), "Failed", count("failed") > 0 ? "failed" : ""),
                new ReportHtml.Stat("" + (count("skipped") + count("cancelled")), "Skipped",
                    count("skipped") + count("cancelled") > 0 ? "skipped" : ""),
                new ReportHtml.Stat(formatMillis(total), "Duration", ""),
                new ReportHtml.Stat("" + shots.size(), "Screenshots", "")))
            + screenshotsHtml(shots)
            + stepsHtml()
            + performanceHtml(started, ended)
            + agentEventsHtml(started));
    }

    /** The at-a-glance gallery. The same files stay linked from the step that captured them. */
    private String screenshotsHtml(List<Shot> shots) {
        return ReportHtml.card("screenshots", "Screenshots",
            "Every screenshot this run captured, in the order the steps took them.",
            shots.size() + (shots.size() == 1 ? " capture" : " captures"),
            ReportHtml.gallery("screenshots", shots));
    }

    private String stepsHtml() {
        // A failing step's screenshot is written straight to disk, so the row that failed would
        // otherwise be the one row with no evidence to click. This is a link, not a report field:
        // `report.json` still records only what the step itself returned.
        Map<Integer, String> failureShots = new HashMap<>();
        for (String path : evidenceScreenshots()) {
            Matcher failure = FAILURE_SHOT.matcher(shotName(path));
            if (failure.matches()) failureShots.put(Integer.parseInt(failure.group(1)), path);
        }
        StringBuilder rows = new StringBuilder();
        for (var r : results) {
            Map<String, String> details = new LinkedHashMap<>(r.details);
            String shot = failureShots.get(r.number);
            if (shot != null) details.putIfAbsent("screenshot", shot);
            rows.append("<tr><td class=\"num\">").append(r.number)
                .append("</td><td><span class=\"phase\">")
                .append(html(r.step.phase().value().toUpperCase(Locale.ROOT)))
                .append("</span></td><td class=\"name\">").append(html(redact(r.step.displayName())))
                .append("</td><td>").append(ReportHtml.pill(r.status))
                .append("</td><td class=\"dur\">").append(formatMillis(r.durationMillis))
                .append("</td><td>").append(htmlDetails(details))
                .append("</td><td class=\"err\">").append(html(r.error == null ? "" : r.error))
                .append("</td></tr>");
        }
        String body = teardownHtml() + ReportHtml.table(
            "<th>#</th><th>Phase</th><th>Action</th><th>Status</th><th>Duration</th>"
                + "<th>Evidence &amp; details</th><th>Error</th>", rows.toString());
        return ReportHtml.card("steps", "Steps",
            "Setup, test and cleanup instructions in execution order; cleanup failures mean a dirty device, not a broken assertion.",
            results.size() + (results.size() == 1 ? " step" : " steps"), body);
    }

    private long count(String status) {
        return results.stream().filter(r -> r.status.equals(status)).count();
    }

    /**
     * Every screenshot the run wrote, labelled by the step that took it. Step order comes first
     * because that is the order a reader follows; a failing step's screenshot is written straight
     * to the evidence directory rather than recorded as a step detail, so the directory is swept
     * afterwards for anything the step details did not already name.
     */
    private List<Shot> screenshots() {
        List<Shot> shots = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var r : results) {
            for (var entry : r.details.entrySet()) {
                String path = entry.getValue();
                if (path == null || !path.endsWith(".png") || !seen.add(path)) continue;
                shots.add(new Shot(path, shotName(path),
                    "Step " + r.number + " · " + redact(r.step.displayName()), r.status));
            }
        }
        for (String path : evidenceScreenshots()) {
            if (!seen.add(path)) continue;
            Matcher failure = FAILURE_SHOT.matcher(shotName(path));
            StepResult step = failure.matches() ? resultNumbered(Integer.parseInt(failure.group(1))) : null;
            shots.add(new Shot(path, shotName(path),
                step == null ? "Captured during the run"
                    : "Step " + step.number + " failed · " + redact(step.step.displayName()),
                step == null ? "" : step.status));
        }
        return shots;
    }

    private StepResult resultNumbered(int number) {
        return results.stream().filter(r -> r.number == number).findFirst().orElse(null);
    }

    /** Best effort: a run that failed before its evidence directory existed simply has none. */
    private List<String> evidenceScreenshots() {
        Path directory = root.resolve("evidence");
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".png"))
                .sorted().map(name -> "evidence/" + name).toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    private static String shotName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
    }

    /** Durations read as time, not as a six-digit millisecond count. */
    static String formatMillis(long millis) {
        if (millis < 1_000) return millis + " ms";
        if (millis < 60_000) return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        return String.format(Locale.ROOT, "%d m %02d s", millis / 60_000, millis % 60_000 / 1000);
    }
    private String junitXml(boolean passed, long millis) {
        boolean failed = primaryFailure() != null || !teardownFailures().isEmpty();
        boolean skipped = isCancelled() && !failed;
        String failure = failureMessage();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"" + xml(test.name())
            + "\" tests=\"1\" failures=\"" + (failed || (!passed && !skipped) ? 1 : 0)
            + "\" skipped=\"" + (skipped ? 1 : 0) + "\" time=\"" + millis / 1000.0
            + "\"><testcase classname=\"" + xml(test.packageName()) + "\" name=\"" + xml(test.name())
            + "\" time=\"" + millis / 1000.0 + "\">"
            + (skipped ? "<skipped message=\"Cancelled by user\"/>" : passed ? ""
                : "<failure message=\"" + xml(failure == null ? "failed" : failure) + "\">"
                    + xml(failureDetail()) + "</failure>")
            + "</testcase></testsuite>\n";
    }

    /**
     * The `<failure>` body: the primary failure labelled by its phase, then one line per cleanup
     * failure. A junit summary shows only the message attribute, so the body is the only place a
     * teardown failure can survive alongside a test failure instead of being dropped.
     */
    private String failureDetail() {
        StringBuilder detail = new StringBuilder();
        String primary = primaryFailure();
        if (primary != null) detail.append(setupFailed() ? "setup: " : "test: ").append(primary);
        for (String cleanup : teardownFailures()) {
            if (!detail.isEmpty()) detail.append('\n');
            detail.append("cleanup: ").append(cleanup);
        }
        return detail.isEmpty() ? "failed" : detail.toString();
    }

    /** Cleanup failures get their own banner: they are easy to miss as two rows in a long table. */
    private String teardownHtml() {
        List<String> teardown = teardownFailures();
        if (teardown.isEmpty()) return "";
        StringBuilder out = new StringBuilder("Cleanup failures · the device or its fixtures may be dirty<ul>");
        for (String cleanup : teardown) out.append("<li>").append(html(cleanup)).append("</li>");
        return ReportHtml.note("", out.append("</ul>").toString());
    }

    private static long elapsed(long n){return Duration.ofNanos(System.nanoTime()-n).toMillis();} private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();} private static String safeFile(String n){return n.replaceAll("[^a-zA-Z0-9._-]","_");}
    private String safeUrl(URI uri){return values.redact(HostRuntime.safeUrl(uri));}
    String redact(String value){return values.redact(value);}
    private Map<String,String> packageDetails(){return Map.of("package",test.packageName());} private Map<String,String> permissionDetails(Map<String,String>a){return Map.of("package",test.packageName(),"permission",a.get("permission"));} private static boolean hasSelector(Map<String,String>a){return a.keySet().stream().anyMatch(UiHierarchy.SELECTOR_KEYS::contains);} private static String selectorDescription(Map<String,String>a){return a.entrySet().stream().filter(e->UiHierarchy.SELECTOR_KEYS.contains(e.getKey())||UiHierarchy.STATE_KEYS.contains(e.getKey())||e.getKey().equals("index")||e.getKey().equals("within-id")).map(e->e.getKey()+"="+e.getValue()).toList().toString();} private static String htmlDetails(Map<String,String> details){if(details.isEmpty())return "";return details.entrySet().stream().map(e->LINKED_DETAILS.contains(e.getKey())?"<a href=\""+html(e.getValue())+"\"><code>"+html(e.getKey())+"</code></a>":"<code>"+html(e.getKey()+"="+e.getValue())+"</code>").collect(java.util.stream.Collectors.joining("","<div class=\"detail\">","</div>"));} private static String html(String s){return ReportHtml.escape(s);} private static String xml(String s){return html(s==null?"":s).replace("'","&apos;");}
    private record StepResult(int number,FlowTest.Step step,String status,long durationMillis,String error,Map<String,String>details,int dumps,long dumpMillis){}
}
