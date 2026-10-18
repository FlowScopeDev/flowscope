package dev.flowscope.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class Main {
    private static final Set<String> NO_FLAGS = Set.of();

    private Main() {}

    public static void main(String[] args) {
        try {
            int result = run(List.of(args));
            if (result != 0) System.exit(result);
        } catch (IllegalArgumentException invalidInput) {
            System.err.println("Error: " + invalidInput.getMessage());
            System.exit(2);
        } catch (java.nio.file.NoSuchFileException missing) {
            // A file the user named does not exist: invalid input, not a runtime failure.
            System.err.println("Error: file not found: " + missing.getFile());
            System.exit(2);
        } catch (Exception error) {
            System.err.println("Error: " + error.getMessage());
            System.exit(1);
        }
    }

    static int run(List<String> args) throws Exception {
        if (args.isEmpty() || args.getFirst().equals("help") || args.getFirst().equals("--help")) {
            printHelp();
            return 0;
        }
        return switch (args.getFirst()) {
            case "doctor" -> doctor(new AdbClient());
            case "devices" -> devices(new AdbClient());
            case "emulators" -> emulators(new AdbClient(), new EmulatorClient(), args.subList(1, args.size()));
            case "emulator" -> emulator(new AdbClient(), new EmulatorClient(), args.subList(1, args.size()));
            case "mirror" -> mirror(new AdbClient(), args.subList(1, args.size()));
            case "record" -> record(new AdbClient(), args.subList(1, args.size()));
            case "snapshot" -> snapshot(new AdbClient(), args.subList(1, args.size()));
            case "web" -> web(new AdbClient(), args.subList(1, args.size()));
            case "project" -> project(args.subList(1, args.size()));
            case "test" -> test(args.subList(1, args.size()));
            case "suite" -> suite(args.subList(1, args.size()));
            case "schema" -> schema();
            default -> {
                System.err.println("Unknown command: " + args.getFirst());
                printHelp();
                yield 2;
            }
        };
    }

    private static int doctor(AdbClient adb) throws Exception {
        System.out.println("ADB: " + adb.executable());
        AdbClient.CommandResult version = adb.run(List.of("version"));
        if (version.exitCode() != 0) {
            System.err.println(version.stderr());
            return 1;
        }
        System.out.print(version.stdout());
        ScrcpyClient scrcpy = new ScrcpyClient();
        System.out.println("scrcpy: " + scrcpy.versionSummary());
        System.out.println("emulator: " + new EmulatorClient().summary());
        Optional<FlowProject> project = FlowProject.discover(Path.of("."));
        System.out.println("project: " + project
            .map(found -> found.manifest().name() + " · " + found.root())
            .orElse("none in this directory or any parent"));
        return devices(adb);
    }

    private static int mirror(AdbClient adb, List<String> args) throws Exception {
        CliOptions options = CliOptions.parse(args, Set.of("--serial"), Set.of("--read-only"));
        options.requireNoExtraPositionals(0, "mirror");
        boolean readOnly = options.flag("--read-only");

        Device selected = selectDevice(adb, options.value("--serial"));
        ScrcpyClient client = new ScrcpyClient();
        System.out.printf("Opening %s mirror for %s…%n", readOnly ? "read-only" : "interactive",
            selected.displayName());
        try (ScrcpyClient.Session session = client.start(selected.serial(), readOnly)) {
            Thread shutdownHook = new Thread(session::close, "flowscope-scrcpy-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                return session.waitFor();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // The JVM is already shutting down and the hook owns cleanup.
                }
            }
        }
    }

    // --------------------------------------------------------------- emulator

    static int emulators(AdbClient adb, EmulatorClient client, List<String> args) throws Exception {
        CliOptions.parse(args, NO_FLAGS, NO_FLAGS).requireNoExtraPositionals(0, "emulators");
        List<String> avds = client.listAvds();
        if (avds.isEmpty()) {
            System.out.println("No AVDs found. Create one in Android Studio's Device Manager.");
            return 0;
        }
        java.util.Map<String, String> running = client.runningEmulators(adb);
        for (String avd : avds) {
            String serial = running.entrySet().stream()
                .filter(entry -> entry.getValue().equals(avd)).map(java.util.Map.Entry::getKey)
                .findFirst().orElse(null);
            System.out.printf("%-28s %s%n", avd, serial == null ? "stopped" : "running " + serial);
        }
        return 0;
    }

    static int emulator(AdbClient adb, EmulatorClient client, List<String> args) throws Exception {
        if (args.isEmpty()) {
            System.err.println("emulator requires: start <avd> or stop <serial>");
            return 2;
        }
        List<String> rest = args.subList(1, args.size());
        return switch (args.getFirst()) {
            case "start" -> {
                CliOptions options = CliOptions.parse(rest, Set.of("--timeout"),
                    Set.of("--headless", "--wait"));
                options.requireNoExtraPositionals(1, "emulator start");
                String avd = options.positional(0);
                if (avd == null || avd.isBlank()) {
                    System.err.println("emulator start requires <avd>");
                    yield 2;
                }
                long timeout = options.positiveLong("--timeout", EmulatorClient.DEFAULT_BOOT_TIMEOUT_MS);
                client.start(adb, avd, options.flag("--headless"));
                if (!options.flag("--wait")) {
                    System.out.println("Started " + avd + ". Run `flowscope devices` once it has booted.");
                    yield 0;
                }
                String serial = client.waitForBoot(adb, avd, timeout);
                System.out.println(serial);
                yield 0;
            }
            case "stop" -> {
                CliOptions options = CliOptions.parse(rest, NO_FLAGS, NO_FLAGS);
                options.requireNoExtraPositionals(1, "emulator stop");
                String serial = options.positional(0);
                if (serial == null || serial.isBlank()) {
                    System.err.println("emulator stop requires <serial>");
                    yield 2;
                }
                client.stop(adb, serial);
                System.out.println("Stopped " + serial);
                yield 0;
            }
            default -> {
                System.err.println("Unknown emulator command: " + args.getFirst());
                yield 2;
            }
        };
    }

    private static int devices(AdbClient adb) throws Exception {
        List<Device> devices = adb.devices();
        if (devices.isEmpty()) {
            System.out.println("No ADB devices found.");
            return 0;
        }
        for (Device device : devices) {
            System.out.printf("%s  %-12s  %s%n", device.serial(), device.state(), device.displayName());
        }
        return 0;
    }

    private static int record(AdbClient adb, List<String> args) throws Exception {
        CaptureOptions options = CaptureOptions.parse(args, "record", Path.of("sessions"));
        if (options.packageName == null) {
            System.err.println("record requires --package <application-id>");
            return 2;
        }
        Device selected = selectDevice(adb, options.serial);

        try (SessionRecorder recorder = new SessionRecorder(adb, selected, options.packageName,
            options.output, options.mode)) {
            Path session = recorder.recordInteractively();
            System.out.println("Session saved to " + session);
        }
        return 0;
    }

    private static int snapshot(AdbClient adb, List<String> args) throws Exception {
        CaptureOptions options = CaptureOptions.parse(args, "snapshot", Path.of("sessions"));
        if (options.packageName == null) {
            System.err.println("snapshot requires --package <application-id>");
            return 2;
        }
        Device selected = selectDevice(adb, options.serial);
        try (SessionRecorder recorder = new SessionRecorder(adb, selected, options.packageName,
            options.output, options.mode)) {
            recorder.start();
            recorder.close();
            System.out.println(recorder.root());
        }
        return 0;
    }

    private static int web(AdbClient adb, List<String> args) throws Exception {
        if (args.isEmpty() || !args.getFirst().equals("inspect")) {
            System.err.println("web requires: inspect --package <application-id>");
            return 2;
        }
        CliOptions options = CliOptions.parse(args.subList(1, args.size()),
            Set.of("--package", "--serial", "--page", "--mode", "--format"), NO_FLAGS);
        options.requireNoExtraPositionals(0, "web inspect");
        String packageName = options.value("--package");
        if (packageName == null || packageName.isBlank()) {
            System.err.println("web inspect requires --package <application-id>");
            return 2;
        }
        CaptureMode mode = options.mode("--mode", CaptureMode.ZERO_CODE);
        String format = options.value("--format", "text");
        if (!Set.of("text", "json").contains(format)) {
            throw new IllegalArgumentException("--format must be text or json");
        }
        Device selected = selectDevice(adb, options.value("--serial"));
        WebViewSnapshot snapshot = WebViewInspector.inspect(adb, selected, packageName,
            options.value("--page"), mode, 10_000);
        System.out.print(format.equals("json")
            ? WebViewInspector.json(snapshot) + System.lineSeparator()
            : WebViewInspector.text(snapshot));
        return 0;
    }

    // ---------------------------------------------------------------- project

    private static int project(List<String> args) throws Exception {
        if (args.isEmpty()) {
            System.err.println("project requires: init, info, tests, suites, or validate");
            return 2;
        }
        String action = args.getFirst();
        List<String> rest = args.subList(1, args.size());
        return switch (action) {
            case "init" -> projectInit(rest);
            case "info" -> projectInfo(rest);
            case "tests" -> projectList(rest, true);
            case "suites" -> projectList(rest, false);
            case "validate" -> projectValidate(rest);
            default -> {
                System.err.println("Unknown project command: " + action);
                yield 2;
            }
        };
    }

    private static int projectInit(List<String> args) throws Exception {
        CliOptions options = CliOptions.parse(args,
            Set.of("--name", "--package", "--mode", "--timeout", "--serial", "--suite"), NO_FLAGS);
        options.requireNoExtraPositionals(1, "project init");
        Path root = Path.of(options.positional(0) == null ? "." : options.positional(0))
            .toAbsolutePath().normalize();

        String name = options.value("--name");
        if (name == null || name.isBlank()) {
            Path fileName = root.getFileName();
            name = fileName == null ? "FlowScope project" : fileName.toString();
        }
        ProjectManifest manifest = new ProjectManifest(
            ProjectManifest.SCHEMA_VERSION, name,
            ProjectManifest.DEFAULT_FLOWS_DIR, ProjectManifest.DEFAULT_REPORTS_DIR,
            new ProjectManifest.Defaults(
                options.value("--package"),
                options.value("--mode") == null ? null : options.mode("--mode", null),
                options.value("--timeout") == null ? null : options.positiveLong("--timeout", 0),
                options.value("--serial")),
            options.value("--suite"));

        boolean hadGitignore = Files.exists(root.resolve(".gitignore"));
        FlowProject project = ProjectScaffold.initialize(root, manifest);
        System.out.println("Created " + project.manifestPath());
        System.out.println("Created " + project.testsDir());
        System.out.println("Created " + project.suitesDir());
        System.out.println((hadGitignore ? "Updated " : "Created ") + root.resolve(".gitignore"));
        return 0;
    }

    private static int projectInfo(List<String> args) throws Exception {
        CliOptions options = CliOptions.parse(args, NO_FLAGS, NO_FLAGS);
        options.requireNoExtraPositionals(1, "project info");
        FlowProject project = FlowProject.require(directoryArgument(options));
        ProjectManifest manifest = project.manifest();
        ProjectManifest.Defaults defaults = manifest.defaults();

        // Every key is always printed, with "-" for unset, so grep and awk stay stable.
        field("root", project.root().toString());
        field("name", manifest.name());
        field("flows", manifest.flowsDir());
        field("reports", manifest.reportsDir());
        field("package", defaults.packageName());
        field("mode", defaults.mode() == null ? null : defaults.mode().cliName());
        field("timeout", defaults.timeoutMillis() == null
            ? null : Long.toString(defaults.timeoutMillis()));
        field("serial", defaults.serial());
        field("suite", manifest.defaultSuite());
        field("tests", Integer.toString(project.listTests().size()));
        field("suites", Integer.toString(project.listSuites().size()));
        return 0;
    }

    private static void field(String key, String value) {
        System.out.printf("%-12s%s%n", key, value == null || value.isBlank() ? "-" : value);
    }

    private static int projectList(List<String> args, boolean tests) throws Exception {
        CliOptions options = CliOptions.parse(args, NO_FLAGS, NO_FLAGS);
        options.requireNoExtraPositionals(1, tests ? "project tests" : "project suites");
        FlowProject project = FlowProject.require(directoryArgument(options));
        for (Path file : tests ? project.listTests() : project.listSuites()) {
            System.out.println(project.relativize(file));
        }
        return 0;
    }

    /** The CI entry point: validates the manifest, every test, and every suite in one pass. */
    private static int projectValidate(List<String> args) throws Exception {
        CliOptions options = CliOptions.parse(args, NO_FLAGS, NO_FLAGS);
        options.requireNoExtraPositionals(1, "project validate");
        FlowProject project = FlowProject.require(directoryArgument(options));
        List<String> failures = new ArrayList<>();
        int tests = 0;
        int suites = 0;

        for (Path file : project.listTests()) {
            String label = project.relativize(file);
            try {
                FlowTest parsed = FlowTestParser.parse(file);
                System.out.printf("ok    %s (%d steps)%n", label, parsed.steps().size());
                tests++;
            } catch (IllegalArgumentException invalid) {
                System.out.printf("FAIL  %s · %s%n", label, invalid.getMessage());
                failures.add(label);
            }
        }
        for (Path file : project.listSuites()) {
            String label = project.relativize(file);
            try {
                FlowSuite parsed = FlowSuiteParser.parse(file);
                for (FlowSuite.Entry entry : parsed.entries()) {
                    try {
                        project.resolveSuiteEntry(entry.reference());
                    } catch (IllegalArgumentException unresolvable) {
                        throw new IllegalArgumentException(
                            "line " + entry.line() + ": " + unresolvable.getMessage());
                    }
                }
                System.out.printf("ok    %s (%d tests)%n", label, parsed.entries().size());
                suites++;
            } catch (IllegalArgumentException invalid) {
                System.out.printf("FAIL  %s · %s%n", label, invalid.getMessage());
                failures.add(label);
            }
        }

        if (failures.isEmpty()) {
            System.out.printf("Valid: %s (%d tests, %d suites)%n",
                project.manifest().name(), tests, suites);
            return 0;
        }
        System.err.printf("%d of %d file(s) invalid%n",
            failures.size(), tests + suites + failures.size());
        return 2;
    }

    private static Path directoryArgument(CliOptions options) {
        String raw = options.positional(0);
        return Path.of(raw == null ? "." : raw);
    }

    // ------------------------------------------------------------------- test

    private static int test(List<String> args) throws Exception {
        if (args.size() < 2) {
            System.err.println("test requires: validate <file> or run <file>");
            return 2;
        }
        String action = args.getFirst();
        if (!action.equals("validate") && !action.equals("run")) {
            System.err.println("Unknown test command: " + action);
            return 2;
        }

        CliOptions options = CliOptions.parse(args.subList(1, args.size()),
            Set.of("--serial", "--output", "--project"), NO_FLAGS);
        options.requireNoExtraPositionals(1, "test " + action);
        String reference = options.positional(0);
        if (reference == null) {
            System.err.println("test " + action + " requires a file");
            return 2;
        }

        Optional<FlowProject> project = resolveProject(options);
        Path path = resolveTest(reference, project);
        FlowTest flowTest = FlowTestParser.parse(path);

        if (action.equals("validate")) {
            System.out.printf("Valid: %s (%d steps, %s)%n", flowTest.name(),
                flowTest.steps().size(), flowTest.mode().cliName());
            return 0;
        }

        Device selected = selectDevice(new AdbClient(), serialFor(options, project));
        FlowTestRunner runner = new FlowTestRunner(
            new AdbClient(), selected, flowTest, outputFor(options, project, Path.of("test-runs")));
        boolean passed = runner.run();
        System.out.println("Report: " + runner.root().resolve("report.json"));
        return passed ? 0 : 1;
    }

    /**
     * An existing path as given always wins, so every invocation that worked before a project
     * existed keeps working identically. Only then does the project's ladder apply.
     */
    private static Path resolveTest(String reference, Optional<FlowProject> project) {
        Path direct = Path.of(reference);
        if (Files.isRegularFile(direct)) return direct;
        if (project.isPresent()) return project.get().resolveTestArgument(reference);
        throw new IllegalArgumentException("Test not found: " + reference);
    }

    // ------------------------------------------------------------------ suite

    private static int suite(List<String> args) throws Exception {
        if (args.isEmpty()) {
            System.err.println("suite requires: validate <file.suite> or run [<file.suite>]");
            return 2;
        }
        String action = args.getFirst();
        if (!action.equals("validate") && !action.equals("run")) {
            System.err.println("Unknown suite command: " + action);
            return 2;
        }

        CliOptions options = CliOptions.parse(args.subList(1, args.size()),
            Set.of("--serial", "--output", "--project"), NO_FLAGS);
        options.requireNoExtraPositionals(1, "suite " + action);
        Optional<FlowProject> project = resolveProject(options);
        Path path = resolveSuite(options.positional(0), project, action);
        FlowSuite parsed = FlowSuiteParser.parse(path);

        if (action.equals("validate")) {
            int steps = 0;
            for (FlowSuite.Entry entry : parsed.entries()) {
                Path testFile = resolveSuiteEntry(entry, path, project);
                try {
                    steps += FlowTestParser.parse(testFile).steps().size();
                } catch (FlowTestParser.ParseError invalid) {
                    throw new IllegalArgumentException(
                        entry.reference() + " " + invalid.getMessage());
                }
            }
            System.out.printf("Valid: %s (%d tests, %s, %d steps total)%n", parsed.name(),
                parsed.entries().size(),
                parsed.continueOnFailure() ? "continue-on-failure" : "stop-on-failure", steps);
            return 0;
        }

        Device selected = selectDevice(new AdbClient(), serialFor(options, project));
        FlowSuiteRunner runner = new FlowSuiteRunner(new AdbClient(), selected, parsed, path,
            project.orElse(null), outputFor(options, project, Path.of("test-runs")));
        boolean passed = runner.run();
        System.out.println("Report: " + runner.root().resolve("suite-report.json"));
        return passed ? 0 : 1;
    }

    private static Path resolveSuiteEntry(
        FlowSuite.Entry entry, Path suiteFile, Optional<FlowProject> project) {
        try {
            if (project.isPresent()) return project.get().resolveSuiteEntry(entry.reference());
            Path suites = suiteFile.toAbsolutePath().normalize().getParent();
            Path flows = suites == null ? null : suites.getParent();
            Path candidate = flows == null ? null : flows.resolve(entry.reference()).normalize();
            if (candidate == null || !Files.isRegularFile(candidate)) {
                throw new IllegalArgumentException("test not found: " + entry.reference());
            }
            return candidate;
        } catch (IllegalArgumentException unresolvable) {
            throw new IllegalArgumentException(suiteFile.getFileName() + " line " + entry.line()
                + ": " + unresolvable.getMessage());
        }
    }

    private static Path resolveSuite(
        String reference, Optional<FlowProject> project, String action) {
        if (reference == null) {
            FlowProject found = project.orElseThrow(() -> new IllegalArgumentException(
                "suite " + action + " requires a file, or a project whose flowscope.json names a "
                    + "\"suite\""));
            String configured = found.manifest().defaultSuite();
            if (configured == null || configured.isBlank()) {
                throw new IllegalArgumentException(
                    "No suite given and flowscope.json has no \"suite\"");
            }
            return found.resolveSuiteArgument(configured);
        }
        Path direct = Path.of(reference);
        if (Files.isRegularFile(direct)) return direct;
        if (project.isPresent()) return project.get().resolveSuiteArgument(reference);
        throw new IllegalArgumentException("Suite not found: " + reference);
    }

    // ------------------------------------------------------- shared resolution

    private static Optional<FlowProject> resolveProject(CliOptions options) throws IOException {
        String explicit = options.value("--project");
        if (explicit != null) return Optional.of(FlowProject.open(Path.of(explicit)));
        return FlowProject.discover(Path.of("."));
    }

    private static String serialFor(CliOptions options, Optional<FlowProject> project) {
        String explicit = options.value("--serial");
        if (explicit != null) return explicit;
        return project.map(found -> found.manifest().defaults().serial()).orElse(null);
    }

    private static Path outputFor(
        CliOptions options, Optional<FlowProject> project, Path fallback) {
        Path explicit = options.path("--output", null);
        if (explicit != null) return explicit;
        return project.map(FlowProject::reportsDir).orElse(fallback);
    }

    private static int schema() {
        System.out.println("""
            FlowScope test format v1

            flowscope 1
            name "Checkout smoke test"
            package com.example.debug
            mode zero-code
            timeout 10000

            before clear-app-data
            before api-request method=GET url="${env.API_BASE_URL}/health" \\
              expect-status=200 expect-json.status=UP timeout=30000 retry-interval=1000
            step launch
            step open-deep-link uri="https://example.com/checkout" cold-start=true
            step tap-if-visible text="Dismiss" timeout=3000
            step wait-visible id=com.example.debug:id/continue
            step tap text="Continue"
            step tap text-contains="Terms & Policies"
            step type id=com.example.debug:id/email value="qa@example.com"
            step scroll direction=up
            step assert-visible desc="Order complete"
            step assert-not-visible text="Loading" timeout=30000
            step back
            step screenshot name=finished
            after screenshot name=final-state
            after force-stop

            Selectors can combine id=, text=, text-contains=, desc=, and class=.
            text-contains= targets an inline substring within a single-line accessibility node.
            tap-if-visible waits up to its timeout and is a no-op when the selector stays absent.
            Mutating actions wait for UI stability; assertions poll until timeout.
            before runs setup in order; step is the test; after always runs every cleanup action.
            Device actions: clear-app-data, force-stop, grant-permission permission=...,
            revoke-permission permission=..., and reset-app-permissions.
            Deep-link actions: open-deep-link uri=... [activity=...], assert-deep-link-handler uri=...,
            and assert-app-link-verified host=....
            Install actions: install-apk file=..., uninstall-app, and assert-installed with
            version-code= or version-name=. APK files are relative to the .flow file.
            Instrumented debug builds may use set-install-referrer value=... and
            clear-install-referrer with FlowScopeAgent.installReferrerOverride(context).
            WebView actions: web-wait, web-tap, web-type, web-assert-visible,
            web-assert-text, web-assert-url, web-screenshot, web-assert-cookie,
            web-clear-cookies, web-assert-storage, web-clear-storage,
            web-assert-interface, web-assert-secure-context, and web-snapshot.
            DOM actions use css= and require a debuggable WebView owned by the declared package.
            Cookie assertions accept name= plus exists/domain/path/secure/http-only/same-site/session/value.
            web-clear-cookies requires domain= and optionally name=/path=.
            Storage assertions accept area=local|session, key=, and exists=/value=.
            web-clear-storage requires origin= and types=local,session,indexeddb,cache.
            Interface assertions accept name=, method= or methods=, exists=, and source=any|runtime|agent.
            Cookie/storage values are compared in memory and never written to output or reports.
            api-request supports host-side HTTP, headers, body/body-file, expect-status,
            expect-json.<path>, capture.<name>, ${env.NAME}, ${var.name}, and if-var cleanup guards.
            Every run writes report.json, report.html, and junit.xml.

            FlowScope suite format v1

            flowscope-suite 1
            name "Nightly regression"
            continue-on-failure true

            test tests/login.flow
            test tests/terms.flow

            `test` paths are relative to the project's flows directory and must end in .flow.
            continue-on-failure defaults to false: the suite stops at the first failing test.
            A suite run writes JSON, HTML, and JUnit aggregates plus per-test reports.

            FlowScope project layout v1

            flowscope.json          project marker and configuration
            flows/tests/*.flow      test cases
            flows/suites/*.suite    regression suites
            reports/                run output, gitignored by `flowscope project init`

            {
              "schemaVersion": 1,
              "name": "Example app",
              "flowsDir": "flows",
              "reportsDir": "reports",
              "defaults": {"package": "com.example.debug", "mode": "zero-code",
                           "timeout": 10000, "serial": null},
              "suite": "nightly.suite"
            }

            flowscope.json supplies authoring and run defaults only. Every .flow keeps its own
            package/mode/timeout header and runs with no project present.
            """);
        return 0;
    }

    private static Device selectDevice(AdbClient adb, String serial) throws Exception {
        List<Device> available = adb.devices().stream()
            .filter(device -> device.state().equals("device"))
            .toList();
        if (serial != null) {
            return available.stream().filter(device -> device.serial().equals(serial)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Connected device not found: " + serial));
        }
        if (available.size() == 1) return available.getFirst();
        if (available.isEmpty()) throw new IllegalStateException("No authorized ADB device is connected");
        throw new IllegalStateException("Multiple devices connected; pass --serial <serial>");
    }

    private static void printHelp() {
        System.out.println("""
            FlowScope

              doctor
              devices
              emulators
              emulator start <avd> [--headless] [--wait] [--timeout <millis>]
              emulator stop <serial>
              mirror [--serial <adb-serial>] [--read-only]
              record --package <application-id> [--mode zero-code|instrumented]
                     [--serial <adb-serial>] [--output <directory>]
              snapshot --package <application-id> [--mode zero-code|instrumented]
                       [--serial <adb-serial>] [--output <directory>]
              web inspect --package <application-id> [--serial <adb-serial>]
                          [--page <match>] [--mode zero-code|instrumented]
                          [--format text|json]
              project init [<directory>] [--name <name>] [--package <id>]
                           [--mode zero-code|instrumented] [--timeout <millis>]
                           [--serial <adb-serial>] [--suite <file.suite>]
              project info|tests|suites|validate [<directory>]
              test validate <file.flow> [--project <directory>]
              test run <file.flow> [--project <directory>] [--serial <adb-serial>]
                       [--output <directory>]
              suite validate <file.suite> [--project <directory>]
              suite run [<file.suite>] [--project <directory>] [--serial <adb-serial>]
                        [--output <directory>]
              schema

            Inside a project, `test run login` resolves flows/tests/login.flow and writes to
            reports/. Every .flow still carries its own package/mode/timeout header and runs with
            no project present.
            """);
    }

    /** `record` and `snapshot` share exactly these options. */
    private static final class CaptureOptions {
        private String packageName;
        private String serial;
        private Path output;
        private CaptureMode mode = CaptureMode.ZERO_CODE;

        static CaptureOptions parse(List<String> args, String command, Path defaultOutput) {
            CliOptions parsed = CliOptions.parse(args,
                Set.of("--package", "--serial", "--output", "--mode"), NO_FLAGS);
            parsed.requireNoExtraPositionals(0, command);
            CaptureOptions options = new CaptureOptions();
            options.packageName = parsed.value("--package");
            options.serial = parsed.value("--serial");
            options.output = parsed.path("--output", defaultOutput);
            options.mode = parsed.mode("--mode", CaptureMode.ZERO_CODE);
            return options;
        }
    }
}
