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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FlowTestRunner {
    private static final long POLL_MILLIS = 200;
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{(env|var)\\.([A-Za-z_][A-Za-z0-9_.-]*)}");
    private static final Pattern GRANTED = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_.]+): granted=true(?:,.*)?$");
    private final AdbClient adb;
    private final Device device;
    private final FlowTest test;
    private final Path root;
    private final FlowHttpClient http;
    private final FlowWebViewClient web;
    private final ApkInspector apkInspector;
    private final Map<String, String> environment;
    private final Map<String, String> variables = new LinkedHashMap<>();
    private final List<StepResult> results = new ArrayList<>();
    private WebViewState previousWebState;

    public FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot) throws IOException {
        this(adb, device, test, outputRoot, RunDirectories.stamp() + "-" + RunDirectories.sanitize(test.name()));
    }

    public FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName) throws IOException {
        this(adb, device, test, outputRoot, directoryName, FlowHttpClient.platform(), System.getenv());
    }

    FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName, FlowHttpClient http, Map<String, String> environment) throws IOException {
        this(adb, device, test, outputRoot, directoryName, http, environment,
            new CdpWebViewClient(adb, device, test.packageName(),
                test.mode() == CaptureMode.INSTRUMENTED), ApkInspector.platform());
    }

    FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName, FlowHttpClient http, Map<String, String> environment,
        FlowWebViewClient web) throws IOException {
        this(adb, device, test, outputRoot, directoryName, http, environment, web,
            ApkInspector.platform());
    }

    FlowTestRunner(AdbClient adb, Device device, FlowTest test, Path outputRoot,
        String directoryName, FlowHttpClient http, Map<String, String> environment,
        FlowWebViewClient web, ApkInspector apkInspector) throws IOException {
        this.adb = adb; this.device = device; this.test = test; this.http = http;
        this.web = web;
        this.apkInspector = apkInspector;
        this.environment = Map.copyOf(environment);
        this.root = outputRoot.resolve(directoryName).toAbsolutePath();
        Files.createDirectories(root.resolve("evidence"));
    }

    public Path root() { return root; }

    /** Metadata-only cookie/storage changes observed at WebView action boundaries. */
    public List<String> webChanges() {
        return results.stream().map(StepResult::details).map(details -> details.get("webChanges"))
            .filter(Objects::nonNull).flatMap(value -> Arrays.stream(value.split("; "))).toList();
    }

    public String failureMessage() {
        return results.stream().filter(r -> r.status.equals("failed")).findFirst()
            .map(r -> r.error == null ? r.step.action() + " failed" : r.step.action() + ": " + r.error)
            .orElse(null);
    }

    public boolean run() throws Exception {
        try {
            preflight();
            Instant started = Instant.now();
            boolean before = runPhase(FlowTest.Phase.BEFORE, true);
            boolean main;
            if (before) main = runPhase(FlowTest.Phase.TEST, true);
            else { skipPhase(FlowTest.Phase.TEST, "before phase failed"); main = false; }
            boolean after = runPhase(FlowTest.Phase.AFTER, false);
            boolean passed = before && main && after;
            writeReports(passed, started, Instant.now());
            return passed;
        } finally {
            web.close();
        }
    }

    private boolean runPhase(FlowTest.Phase phase, boolean stopOnFailure) {
        boolean passed = true, stopped = false;
        for (int i = 0; i < test.steps().size(); i++) {
            FlowTest.Step step = test.steps().get(i);
            if (step.phase() != phase) continue;
            if (stopped) { addSkipped(i, step, "earlier " + phase.value() + " instruction failed"); continue; }
            String guard = step.arguments().get("if-var");
            if (guard != null && !variables.containsKey(guard)) { addSkipped(i, step, "variable not set: " + guard); continue; }
            long started = System.nanoTime();
            try {
                Map<String, String> details = execute(step, i);
                results.add(new StepResult(i + 1, step, "passed", elapsed(started), null, details));
                System.out.printf("PASS %s %02d  %s%n", phase.value().toUpperCase(), i + 1, step.action());
            } catch (Exception failure) {
                passed = false;
                captureEvidence("failure-" + String.format("%02d", i + 1));
                String message = redact(message(failure));
                results.add(new StepResult(i + 1, step, "failed", elapsed(started), message, Map.of()));
                System.err.printf("FAIL %s %02d  %s: %s%n", phase.value().toUpperCase(), i + 1, step.action(), message);
                if (stopOnFailure) stopped = true;
            }
        }
        return passed;
    }

    private void addSkipped(int i, FlowTest.Step step, String reason) {
        results.add(new StepResult(i + 1, step, "skipped", 0, reason, Map.of()));
        System.out.printf("SKIP %s %02d  %s%n", step.phase().value().toUpperCase(), i + 1, step.action());
    }

    private void skipPhase(FlowTest.Phase phase, String reason) {
        for (int i = 0; i < test.steps().size(); i++) if (test.steps().get(i).phase() == phase) addSkipped(i, test.steps().get(i), reason);
    }

    private Map<String, String> execute(FlowTest.Step step, int index) throws Exception {
        Map<String, String> args = expanded(step.arguments());
        long timeout = args.containsKey("timeout") ? FlowTestParser.parsePositiveLong(args.get("timeout"), step.line(), "timeout") : test.timeoutMillis();
        return switch (step.action()) {
            case "launch" -> { launch(); settle(); yield Map.of(); }
            case "wait-visible", "assert-visible" -> { requireNode(args, timeout); yield Map.of(); }
            case "tap" -> { var n = requireNode(args, timeout); shell("input", "tap", "" + n.tapX(args), "" + n.centerY()); settle(); yield Map.of(); }
            case "tap-if-visible" -> { var n = findNode(args, timeout); if (n != null) { shell("input", "tap", "" + n.tapX(args), "" + n.centerY()); settle(); } yield Map.of("matched", "" + (n != null)); }
            case "type" -> { if (hasSelector(args)) { var n = requireNode(args, timeout); shell("input", "tap", "" + n.centerX(), "" + n.centerY()); } typeText(args.get("value")); settle(); yield Map.of(); }
            case "scroll" -> { scroll(args.get("direction")); settle(); yield Map.of(); }
            case "back" -> { shell("input", "keyevent", "KEYCODE_BACK"); settle(); yield Map.of(); }
            case "assert-not-visible" -> { requireAbsent(args, timeout); yield Map.of(); }
            case "screenshot" -> { String name = args.getOrDefault("name", "step-" + String.format("%02d", index + 1)); captureScreenshot(name); yield Map.of("evidence", "evidence/" + safeFile(name) + ".png"); }
            case "api-request" -> apiRequest(args, timeout);
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
        Path apk = resolveArtifactFile(environmentPath(args.get("file")), ".apk");
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

    private Map<String, String> apiRequest(Map<String, String> args, long timeout) throws Exception {
        String method = args.getOrDefault("method", "GET").toUpperCase();
        URI uri = URI.create(args.get("url"));
        if (!Set.of("http", "https").contains(uri.getScheme())) throw new IllegalArgumentException("api-request URL must use http or https");
        Map<String, String> headers = new LinkedHashMap<>();
        args.forEach((k, v) -> { if (k.startsWith("header.")) headers.put(k.substring(7), v); });
        String body = args.get("body");
        if (args.containsKey("body-file")) body = interpolate(Files.readString(resolveBodyFile(environmentPath(args.get("body-file"))), StandardCharsets.UTF_8));
        long interval = args.containsKey("retry-interval") ? Long.parseLong(args.get("retry-interval")) : 0;
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        Exception last = null;
        do {
            try {
                long remaining = Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
                var response = http.send(method, uri, headers, body, remaining);
                assertResponse(response, args);
                Map<String, String> captured = capture(response.body(), args);
                variables.putAll(captured);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("method", method); details.put("url", safeUrl(uri)); details.put("statusCode", "" + response.status());
                if (!headers.isEmpty()) details.put("headerNames", String.join(",", headers.keySet()));
                if (!captured.isEmpty()) details.put("captured", String.join(",", captured.keySet()));
                return Map.copyOf(details);
            } catch (Exception failure) {
                last = failure;
                if (interval <= 0 || System.nanoTime() >= deadline) break;
                Thread.sleep(Math.min(interval, Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis())));
            }
        } while (System.nanoTime() < deadline);
        throw last == null ? new IllegalStateException("API request failed") : last;
    }

    private void assertResponse(FlowHttpClient.Response response, Map<String, String> args) {
        if (args.containsKey("expect-status")) {
            Set<Integer> expected = new HashSet<>();
            for (String value : args.get("expect-status").split(",")) expected.add(Integer.parseInt(value.strip()));
            if (!expected.contains(response.status())) throw new IllegalStateException("Expected HTTP " + args.get("expect-status") + " but received " + response.status());
        }
        var assertions = args.entrySet().stream().filter(e -> e.getKey().startsWith("expect-json.")).toList();
        if (!assertions.isEmpty()) {
            Object json = Json.parse(response.body());
            for (var assertion : assertions) {
                String path = assertion.getKey().substring(12);
                if (!scalar(jsonPath(json, path)).equals(assertion.getValue())) throw new IllegalStateException("JSON field " + path + " did not match expected value");
            }
        }
    }

    private Map<String, String> capture(String body, Map<String, String> args) {
        var captures = args.entrySet().stream().filter(e -> e.getKey().startsWith("capture.")).toList();
        if (captures.isEmpty()) return Map.of();
        Object json = Json.parse(body); Map<String, String> values = new LinkedHashMap<>();
        for (var entry : captures) values.put(entry.getKey().substring(8), scalar(jsonPath(json, entry.getValue())));
        return values;
    }

    private static Object jsonPath(Object root, String path) {
        Object current = root;
        for (String segment : path.replaceFirst("^\\$\\.", "").split("\\.")) {
            Matcher part = Pattern.compile("([^\\[]+)(?:\\[(\\d+)])?").matcher(segment);
            if (!part.matches() || !(current instanceof Map<?, ?> map) || !map.containsKey(part.group(1))) throw new IllegalStateException("JSON path not found: " + path);
            current = map.get(part.group(1));
            if (part.group(2) != null) {
                if (!(current instanceof List<?> list)) throw new IllegalStateException("JSON path is not an array: " + path);
                int index = Integer.parseInt(part.group(2)); if (index >= list.size()) throw new IllegalStateException("JSON array index out of range: " + path); current = list.get(index);
            }
        }
        return current;
    }

    private static String scalar(Object value) {
        if (value == null) return "null";
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalStateException("JSON path does not resolve to a scalar value");
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
            for (String value : step.arguments().values()) requireEnvironment(value);
            String bodyFile = step.arguments().get("body-file");
            if (bodyFile != null) {
                Path file = resolveBodyFile(environmentPath(bodyFile));
                if (!Files.isRegularFile(file)) throw new IllegalArgumentException("API body file not found: " + bodyFile);
                requireEnvironment(Files.readString(file, StandardCharsets.UTF_8));
            }
            String apkFile = step.arguments().get("file");
            if (step.action().equals("install-apk") && apkFile != null) {
                Path file = resolveArtifactFile(environmentPath(apkFile), ".apk");
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

    private void requireEnvironment(String value) {
        Matcher matcher = VARIABLE.matcher(value);
        while (matcher.find()) if (matcher.group(1).equals("env") && !environment.containsKey(matcher.group(2))) throw new IllegalArgumentException("Missing environment variable: " + matcher.group(2));
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
        Path relative = Path.of(raw);
        if (relative.isAbsolute() || raw.contains("\\")) throw new IllegalArgumentException(label + " must be a relative path");
        for (Path segment : relative) if (segment.toString().equals("..")) throw new IllegalArgumentException(label + " must not contain '..'");
        Path base = test.sourceDirectory().toAbsolutePath().normalize(), resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) throw new IllegalArgumentException(label + " escapes the test directory");
        if (Files.exists(resolved)) {
            try {
                Path realBase = base.toRealPath();
                Path realResolved = resolved.toRealPath();
                if (!realResolved.startsWith(realBase)) {
                    throw new IllegalArgumentException(label + " escapes the test directory through a symbolic link");
                }
                return realResolved;
            } catch (IOException invalid) {
                throw new IllegalArgumentException("Could not resolve " + label + ": " + raw, invalid);
            }
        }
        return resolved;
    }

    private String environmentPath(String value) {
        Matcher matcher = VARIABLE.matcher(value); StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            if (!matcher.group(1).equals("env")) throw new IllegalArgumentException("body-file path cannot use runtime variables");
            matcher.appendReplacement(out, Matcher.quoteReplacement(environment.get(matcher.group(2))));
        }
        matcher.appendTail(out); return out.toString();
    }

    private Map<String, String> expanded(Map<String, String> input) {
        Map<String, String> output = new LinkedHashMap<>(); input.forEach((k, v) -> output.put(k, interpolate(v))); return output;
    }

    private String interpolate(String value) {
        Matcher matcher = VARIABLE.matcher(value); StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group(1).equals("env") ? environment.get(matcher.group(2)) : variables.get(matcher.group(2));
            if (replacement == null) throw new IllegalStateException("Variable is not set: " + matcher.group(2));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out); return out.toString();
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
        do { var found = UiHierarchy.find(UiHierarchy.parse(dumpHierarchy()), args); if (found != null) return found; Thread.sleep(POLL_MILLIS); } while (System.nanoTime() < deadline); return null;
    }
    private void requireAbsent(Map<String, String> args, long timeout) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        do { if (UiHierarchy.find(UiHierarchy.parse(dumpHierarchy()), args) == null) return; Thread.sleep(POLL_MILLIS); } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Element remained visible: " + selectorDescription(args));
    }
    private byte[] dumpHierarchy() throws Exception {
        var dump = adb.run(List.of("-s", device.serial(), "shell", "uiautomator", "dump", "/data/local/tmp/flowscope-test-window.xml"));
        if (dump.exitCode() != 0) throw new IOException("UI hierarchy failed: " + (dump.stdout() + dump.stderr()).strip());
        return adb.execOut(device.serial(), "cat", "/data/local/tmp/flowscope-test-window.xml");
    }
    private void settle() { try { byte[] old = dumpHierarchy(); long end = System.nanoTime() + 1_500_000_000L; do { Thread.sleep(250); byte[] now = dumpHierarchy(); if (Arrays.equals(old, now)) return; old = now; } while (System.nanoTime() < end); } catch (Exception ignored) {} }
    private void typeText(String value) throws Exception { if (!value.matches("[a-zA-Z0-9@._+ -]*")) throw new IllegalArgumentException("type currently supports letters, numbers, spaces, and @ . _ + -"); shell("input", "text", value.replace(" ", "%s")); }
    private void scroll(String direction) throws Exception {
        var sizeResult = adb.run(List.of("-s", device.serial(), "shell", "wm", "size")); Matcher size = Pattern.compile("(\\d+)x(\\d+)").matcher(sizeResult.stdout());
        if (!size.find()) throw new IOException("Could not determine device size"); int w = Integer.parseInt(size.group(1)), h = Integer.parseInt(size.group(2)); int sx=w/2,sy=h/2,ex=sx,ey=sy;
        switch(direction){case"up"->{sy+=h/3;ey-=h/3;}case"down"->{sy-=h/3;ey+=h/3;}case"left"->{sx+=w/3;ex-=w/3;}case"right"->{sx-=w/3;ex+=w/3;}default->throw new IllegalArgumentException("Unknown scroll direction: "+direction);} shell("input","swipe",""+sx,""+sy,""+ex,""+ey,"450");
    }
    private void captureEvidence(String name) { try { captureScreenshot(name); } catch(Exception ignored){} try { Files.write(root.resolve("evidence").resolve(name+".xml"),dumpHierarchy()); } catch(Exception ignored){} }
    private void captureScreenshot(String name) throws Exception { Files.write(root.resolve("evidence").resolve(safeFile(name)+".png"),adb.execOut(device.serial(),"screencap","-p")); }
    private void shell(String... args) throws Exception { String[] command=new String[args.length+1];command[0]="shell";System.arraycopy(args,0,command,1,args.length);command(command); }
    private void command(String... args) throws Exception { List<String> command=new ArrayList<>(List.of("-s",device.serial()));command.addAll(List.of(args));var result=adb.run(command);if(result.exitCode()!=0)throw new IOException("ADB failed: "+(result.stdout()+result.stderr()).strip()); }

    private void writeReports(boolean passed, Instant started, Instant ended) throws IOException {
        Files.writeString(root.resolve("report.json"), reportJson(passed, started, ended), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("report.html"), reportHtml(passed, started, ended), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("junit.xml"), junitXml(passed, Duration.between(started, ended).toMillis()), StandardCharsets.UTF_8);
    }
    private String reportJson(boolean passed, Instant started, Instant ended) {
        String primary=results.stream().filter(r->r.status.equals("failed")&&r.step.phase()!=FlowTest.Phase.AFTER).map(r->r.error).findFirst().orElse(null); List<String> teardown=results.stream().filter(r->r.status.equals("failed")&&r.step.phase()==FlowTest.Phase.AFTER).map(r->r.error).toList();
        StringBuilder out=new StringBuilder("{\n  \"schemaVersion\": 2,\n  \"kind\": \"test\",\n").append("  \"name\": ").append(Json.quote(test.name())).append(",\n  \"packageName\": ").append(Json.quote(test.packageName())).append(",\n  \"mode\": ").append(Json.quote(test.mode().cliName())).append(",\n  \"deviceSerial\": ").append(Json.quote(device.serial())).append(",\n  \"startedAt\": ").append(Json.quote(started.toString())).append(",\n  \"endedAt\": ").append(Json.quote(ended.toString())).append(",\n  \"status\": ").append(Json.quote(passed?"passed":"failed")).append(",\n  \"primaryFailure\": ").append(Json.quote(primary)).append(",\n  \"teardownFailures\": [");
        for(int i=0;i<teardown.size();i++){if(i>0)out.append(", ");out.append(Json.quote(teardown.get(i)));} out.append("],\n  \"webView\": ").append(previousWebState == null ? "null" : WebViewInspector.json(previousWebState.sanitized())).append(",\n  \"steps\": [\n");
        for(int i=0;i<results.size();i++){var r=results.get(i);out.append("    {\"number\": ").append(r.number).append(", \"line\": ").append(r.step.line()).append(", \"phase\": ").append(Json.quote(r.step.phase().value())).append(", \"action\": ").append(Json.quote(r.step.action())).append(", \"status\": ").append(Json.quote(r.status)).append(", \"durationMillis\": ").append(r.durationMillis).append(", \"error\": ").append(Json.quote(r.error)).append(", \"details\": {");int d=0;for(var e:r.details.entrySet()){if(d++>0)out.append(", ");out.append(Json.quote(e.getKey())).append(": ").append(Json.quote(e.getValue()));}out.append("}}");if(i+1<results.size())out.append(',');out.append('\n');} return out.append("  ]\n}\n").toString();
    }
    private String reportHtml(boolean passed,Instant started,Instant ended){StringBuilder rows=new StringBuilder();for(var r:results){String details=htmlDetails(r.details);rows.append("<tr><td>").append(r.number).append("</td><td>").append(html(r.step.phase().value().toUpperCase())).append("</td><td>").append(html(r.step.action())).append("</td><td class=\"").append(r.status).append("\">").append(r.status).append("</td><td>").append(r.durationMillis).append(" ms</td><td>").append(details).append("</td><td>").append(html(r.error==null?"":r.error)).append("</td></tr>");}return "<!doctype html><meta charset=\"utf-8\"><title>"+html(test.name())+" · FlowScope</title><style>body{font:14px system-ui;margin:32px;color:#171b22}.passed{color:#147a50}.failed{color:#c9323c}.skipped{color:#687382}table{border-collapse:collapse;width:100%;margin-top:24px}th,td{padding:9px;border-bottom:1px solid #dae0e8;text-align:left}a{color:#315fb5}</style><h1>"+html(test.name())+"</h1><strong class=\""+(passed?"passed":"failed")+"\">"+(passed?"PASSED":"FAILED")+"</strong><p>"+html(test.packageName())+" · "+html(device.serial())+"</p><p>"+started+" — "+ended+"</p><table><tr><th>#</th><th>Phase</th><th>Action</th><th>Status</th><th>Duration</th><th>Details</th><th>Error</th></tr>"+rows+"</table>";}
    private String junitXml(boolean passed,long millis){String failure=failureMessage();return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\""+xml(test.name())+"\" tests=\"1\" failures=\""+(passed?0:1)+"\" time=\""+(millis/1000.0)+"\"><testcase classname=\""+xml(test.packageName())+"\" name=\""+xml(test.name())+"\" time=\""+(millis/1000.0)+"\">"+(passed?"":"<failure message=\""+xml(failure)+"\">"+xml(failure)+"</failure>")+"</testcase></testsuite>\n";}

    private static long elapsed(long n){return Duration.ofNanos(System.nanoTime()-n).toMillis();} private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();} private static String safeFile(String n){return n.replaceAll("[^a-zA-Z0-9._-]","_");}
    private String safeUrl(URI u){String path=u.getPath()==null?"":u.getPath();for(String value:variables.values())if(value!=null&&!value.isBlank())path=path.replace(value,"{captured}");return u.getScheme()+"://"+u.getAuthority()+path;}
    private String redact(String value){String redacted=value;for(String secret:environment.values())if(secret!=null&&!secret.isBlank())redacted=redacted.replace(secret,"{env}");for(String captured:variables.values())if(captured!=null&&!captured.isBlank())redacted=redacted.replace(captured,"{captured}");return redacted;}
    private Map<String,String> packageDetails(){return Map.of("package",test.packageName());} private Map<String,String> permissionDetails(Map<String,String>a){return Map.of("package",test.packageName(),"permission",a.get("permission"));} private static boolean hasSelector(Map<String,String>a){return a.keySet().stream().anyMatch(Set.of("id","text","text-contains","desc","class")::contains);} private static String selectorDescription(Map<String,String>a){return a.entrySet().stream().filter(e->Set.of("id","text","text-contains","desc","class").contains(e.getKey())).map(e->e.getKey()+"="+e.getValue()).toList().toString();} private static String htmlDetails(Map<String,String> details){return details.entrySet().stream().map(e->e.getKey().equals("evidence")?"<a href=\""+html(e.getValue())+"\">evidence</a>":html(e.getKey()+"="+e.getValue())).collect(java.util.stream.Collectors.joining(" · "));} private static String html(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");} private static String xml(String s){return html(s==null?"":s).replace("'","&apos;");}
    private record StepResult(int number,FlowTest.Step step,String status,long durationMillis,String error,Map<String,String>details){}
}
