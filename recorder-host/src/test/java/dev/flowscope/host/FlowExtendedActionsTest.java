package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowExtendedActionsTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    @Test
    void deepLinksArePackageScopedAndQueryValuesAreRedacted() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step assert-deep-link-handler uri="myapp://customer/${env.ID}?token=${env.TOKEN}"
            step open-deep-link uri="myapp://customer/${env.ID}?token=${env.TOKEN}" cold-start=true
            step assert-app-link-verified host=example.com
            """, "links.flow");
        FakeAdb adb = new FakeAdb();
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, test, temp, "links",
            emptyHttp(), Map.of("ID", "42", "TOKEN", "secret"), new FakeWeb());

        assertTrue(runner.run());
        List<String> start = adb.commands.stream().filter(c -> c.contains("start")).findFirst().orElseThrow();
        assertEquals("com.example.debug/.DeepLinkActivity", start.getLast());
        assertTrue(start.contains("myapp://customer/42?token=secret"));
        String report = Files.readString(runner.root().resolve("report.json"));
        assertTrue(report.contains("myapp://customer/42?{redacted}"));
        assertFalse(report.contains("secret"));
    }

    @Test
    void ambiguousDeepLinkRequiresAnExplicitPackageOwnedActivity() throws Exception {
        FlowTest ambiguous = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step open-deep-link uri="myapp://customer/42"
            """, "ambiguous.flow");
        FakeAdb adb = new FakeAdb("""
            com.example.debug/.FirstActivity
            com.example.debug/com.example.debug.SecondActivity
            """);
        FlowTestRunner failed = new FlowTestRunner(adb, DEVICE, ambiguous, temp, "ambiguous",
            emptyHttp(), Map.of(), new FakeWeb());
        assertFalse(failed.run());
        assertTrue(failed.failureMessage().contains("Multiple activities"));

        FlowTest selected = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step open-deep-link uri="myapp://customer/42" activity=.FirstActivity
            """, "selected.flow");
        FlowTestRunner passed = new FlowTestRunner(adb, DEVICE, selected, temp, "selected",
            emptyHttp(), Map.of(), new FakeWeb());
        assertTrue(passed.run());
        List<String> start = adb.commands.stream().filter(c -> c.contains("start")).findFirst().orElseThrow();
        assertTrue(start.contains("com.example.debug/.FirstActivity"));
    }

    @Test
    void installsRelativeApkAndChecksInstalledVersion() throws Exception {
        Path artifacts = Files.createDirectories(temp.resolve("artifacts"));
        Files.write(artifacts.resolve("app.apk"), new byte[] {1, 2, 3});
        Path flow = temp.resolve("install.flow");
        Files.writeString(flow, """
            flowscope 1
            package com.example.debug
            before uninstall-app keep-data=false
            step install-apk file="artifacts/app.apk" replace=true grant-permissions=true
            step assert-installed version-code=120 version-name=1.2.0
            """);
        FakeAdb adb = new FakeAdb();
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, FlowTestParser.parse(flow), temp,
            "install", emptyHttp(), Map.of(), new FakeWeb(), apk -> "com.example.debug");

        assertTrue(runner.run());
        List<String> install = adb.commands.stream().filter(c -> c.contains("install")).findFirst().orElseThrow();
        assertTrue(install.contains("-r"));
        assertTrue(install.contains("-g"));
        assertEquals(artifacts.resolve("app.apk").toRealPath().toString(), install.getLast());
    }

    @Test
    void missingApkFailsPreflightBeforeAdbMutation() throws Exception {
        Path flow = temp.resolve("missing.flow");
        Files.writeString(flow, """
            flowscope 1
            package com.example.debug
            step install-apk file="artifacts/missing.apk"
            """);
        FakeAdb adb = new FakeAdb();
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, FlowTestParser.parse(flow), temp,
            "missing", emptyHttp(), Map.of(), new FakeWeb());

        assertThrows(IllegalArgumentException.class, runner::run);
        assertTrue(adb.commands.isEmpty());
    }

    @Test
    void wrongApkPackageFailsPreflightBeforeAdbMutation() throws Exception {
        Files.write(temp.resolve("other.apk"), new byte[] {1});
        Path flow = temp.resolve("wrong.flow");
        Files.writeString(flow, """
            flowscope 1
            package com.example.debug
            step install-apk file="other.apk"
            """);
        FakeAdb adb = new FakeAdb();
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, FlowTestParser.parse(flow), temp,
            "wrong", emptyHttp(), Map.of(), new FakeWeb(), apk -> "com.other.app");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, runner::run);
        assertTrue(failure.getMessage().contains("does not match test package"));
        assertTrue(adb.commands.isEmpty());
    }

    @Test
    void apkSymlinkCannotEscapeTheFlowDirectory() throws Exception {
        Path flowDir = Files.createDirectories(temp.resolve("flow"));
        Path outside = Files.write(temp.resolve("outside.apk"), new byte[] {1});
        try {
            Files.createSymbolicLink(flowDir.resolve("linked.apk"), outside);
        } catch (UnsupportedOperationException unsupported) {
            return;
        }
        Path flow = flowDir.resolve("symlink.flow");
        Files.writeString(flow, """
            flowscope 1
            package com.example.debug
            step install-apk file="linked.apk"
            """);
        FakeAdb adb = new FakeAdb();
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, FlowTestParser.parse(flow), temp,
            "symlink", emptyHttp(), Map.of(), new FakeWeb(), apk -> "com.example.debug");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, runner::run);
        assertTrue(failure.getMessage().contains("symbolic link"));
        assertTrue(adb.commands.isEmpty());
    }

    @Test
    void webActionsUseRestrictedClientAndWriteEvidence() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step web-wait css="form#checkout"
            step web-type css="input[name=email]" value="qa@example.com"
            step web-tap css="button[type=submit]"
            step web-assert-text css="h1" text="Payment complete"
            step web-assert-url path="/checkout/success"
            step web-screenshot name=checkout
            """, "web.flow");
        FakeWeb web = new FakeWeb();
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, test, temp, "web",
            emptyHttp(), Map.of(), web);

        assertTrue(runner.run());
        assertEquals(List.of("wait", "type", "tap", "text", "url", "screenshot"),
            web.calls.stream().filter(call -> !call.equals("inspect")).toList());
        assertArrayEquals(new byte[] {8, 9}, Files.readAllBytes(
            runner.root().resolve("evidence/checkout.png")));
        assertTrue(web.closed);
    }

    @Test
    void webStateActionsAssertWithoutPersistingValues() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            mode instrumented
            step web-assert-cookie name=session_id domain=.example.com path=/ secure=true http-only=true same-site=Lax value="${env.COOKIE}"
            step web-assert-storage area=local key=customerId value="${env.CUSTOMER}"
            step web-assert-interface name=Android method=openCamera
            step web-assert-interface name=Payments methods="startPayment,getStatus" source=agent
            step web-assert-secure-context value=true
            step web-snapshot name=web-state
            after web-clear-cookies domain=.example.com name=session_id path=/
            after web-clear-storage origin="https://example.com" types=local,session,indexeddb,cache
            """, "web-state.flow");
        FakeWeb web = new FakeWeb();
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, test, temp, "web-state",
            emptyHttp(), Map.of("COOKIE", "top-secret-cookie", "CUSTOMER", "private-customer"), web);

        assertTrue(runner.run());
        String report = Files.readString(runner.root().resolve("report.json"));
        String evidence = Files.readString(runner.root().resolve("evidence/web-state.webview.json"));
        assertFalse(report.contains("top-secret-cookie"));
        assertFalse(report.contains("private-customer"));
        assertFalse(evidence.contains("top-secret-cookie"));
        assertFalse(evidence.contains("private-customer"));
        assertTrue(evidence.contains("session_id"));
        assertTrue(evidence.contains("customerId"));
        assertTrue(web.calls.containsAll(List.of("inspect", "interface:Android:openCamera",
            "clear-cookies", "clear-storage")));
    }

    @Test
    void exactAgentBridgeAssertionsRequireInstrumentedModeBeforeMutation() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            mode zero-code
            step web-assert-interface name=Android source=agent
            """, "web-agent-zero.flow");
        FakeAdb adb = new FakeAdb();
        FakeWeb web = new FakeWeb();
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, test, temp, "web-agent-zero",
            emptyHttp(), Map.of(), web);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, runner::run);
        assertTrue(failure.getMessage().contains("requires mode instrumented"));
        assertTrue(adb.commands.isEmpty());
        assertTrue(web.calls.isEmpty());
    }

    @Test
    void cdpJsonAcceptsFractionalCookieExpiryWithoutWeakeningProjectJson() {
        Map<String, Object> parsed = CdpJson.parseObject("{\"expires\":1.725e9,\"size\":42}");
        assertEquals(1.725e9, ((Number) parsed.get("expires")).doubleValue());
        assertThrows(Json.FormatError.class, () -> Json.parse("{\"expires\":1.5}"));
    }

    @Test
    void installReferrerStaysOutOfReportsAndPlaintextCommands() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            mode instrumented
            before set-install-referrer value="${env.REFERRER}"
            step force-stop
            after clear-install-referrer
            """, "referrer.flow");
        FakeAdb adb = new FakeAdb();
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, test, temp, "referrer",
            emptyHttp(), Map.of("REFERRER", "utm_source=secret-campaign"), new FakeWeb());

        assertTrue(runner.run());
        assertTrue(adb.commands.stream().flatMap(List::stream)
            .noneMatch(argument -> argument.contains("secret-campaign")));
        String report = Files.readString(runner.root().resolve("report.json"));
        assertFalse(report.contains("secret-campaign"));
        assertTrue(report.contains("referrerInjected"));
    }

    @Test
    void installReferrerRequiresInstrumentedModeBeforeMutation() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            mode zero-code
            step set-install-referrer value="campaign"
            """, "referrer-zero.flow");
        FakeAdb adb = new FakeAdb();
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, test, temp, "referrer-zero",
            emptyHttp(), Map.of(), new FakeWeb());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, runner::run);
        assertTrue(failure.getMessage().contains("requires mode instrumented"));
        assertTrue(adb.commands.isEmpty());
    }

    @Test
    void parsesPackageAppLinkStateAndPidScopedSocket() {
        assertEquals("verified", FlowTestRunner.appLinkState("  example.com: verified\n", "example.com"));
        assertEquals("missing", FlowTestRunner.appLinkState("  other.com: verified\n", "example.com"));
        assertEquals("webview_devtools_remote_418", CdpWebViewClient.findSocket(
            "000: 00000002 00000000 00010000 0001 01 12 @webview_devtools_remote_418\n", "418"));
        assertNull(CdpWebViewClient.findSocket(
            "000: 00000002 00000000 00010000 0001 01 12 @webview_devtools_remote_419\n", "418"));
        Object targets = Json.parse("""
            [{"id":"target-1","type":"page","title":"Checkout","url":"https://example.com/checkout",
              "webSocketDebuggerUrl":"ws://one"},
             {"id":"target-2","type":"iframe","title":"Bank","url":"https://bank.example/challenge",
              "webSocketDebuggerUrl":"ws://two"}]
            """);
        assertEquals("ws://two", CdpWebViewClient.selectTarget(targets, "bank.example").webSocketUrl());
        assertEquals("ws://two", CdpWebViewClient.selectTarget(targets, "target-2").webSocketUrl());
        assertThrows(IllegalStateException.class, () -> CdpWebViewClient.selectTarget(targets, null));
    }

    @Test
    void parserRejectsIncompleteExtendedActions() {
        assertThrows(FlowTestParser.ParseError.class, () -> FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step open-deep-link
            """, "bad.flow"));
        assertThrows(FlowTestParser.ParseError.class, () -> FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step web-type css="#email"
            """, "bad.flow"));
        assertThrows(FlowTestParser.ParseError.class, () -> FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step install-apk file="app.apk" replace=maybe
            """, "bad.flow"));
        assertThrows(FlowTestParser.ParseError.class, () -> FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step web-clear-cookies
            """, "bad.flow"));
        assertThrows(FlowTestParser.ParseError.class, () -> FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step web-clear-storage origin="https://example.com" types=everything
            """, "bad.flow"));
    }

    private static FlowHttpClient emptyHttp() {
        return (method, uri, headers, body, timeout) -> new FlowHttpClient.Response(200, "{}");
    }

    private static final class FakeAdb extends AdbClient {
        final List<List<String>> commands = new ArrayList<>();
        private final String handlers;
        FakeAdb() { this("com.example.debug/.DeepLinkActivity\n"); }
        FakeAdb(String handlers) { this.handlers = handlers; }
        @Override public CommandResult run(List<String> arguments) {
            commands.add(List.copyOf(arguments));
            if (arguments.contains("query-activities")) return new CommandResult(0,
                handlers, "");
            if (arguments.contains("get-app-links")) return new CommandResult(0,
                "  example.com: verified\n", "");
            if (arguments.contains("install") || arguments.contains("uninstall")) {
                return new CommandResult(0, "Success\n", "");
            }
            if (arguments.contains("path")) return new CommandResult(0,
                "package:/data/app/com.example.debug/base.apk\n", "");
            if (arguments.contains("dumpsys")) return new CommandResult(0,
                "versionCode=120 minSdk=28\nversionName=1.2.0\n", "");
            if (arguments.contains("uiautomator")) return new CommandResult(0, "dumped", "");
            return new CommandResult(0, "Success\n", "");
        }
        @Override public byte[] execOut(String serial, String... command) {
            if (List.of(command).contains("cat")) return "<hierarchy rotation=\"0\"/>"
                .getBytes(StandardCharsets.UTF_8);
            return new byte[0];
        }
    }

    private static final class FakeWeb implements FlowWebViewClient {
        final List<String> calls = new ArrayList<>();
        boolean closed;
        @Override public void waitVisible(Map<String, String> a, long t) { calls.add("wait"); }
        @Override public void tap(Map<String, String> a, long t) { calls.add("tap"); }
        @Override public void type(Map<String, String> a, long t) { calls.add("type"); }
        @Override public String text(Map<String, String> a, long t) { calls.add("text"); return "Payment complete"; }
        @Override public String url(Map<String, String> a, long t) { calls.add("url"); return "https://example.com/checkout/success?token=x"; }
        @Override public byte[] screenshot(Map<String, String> a, long t) { calls.add("screenshot"); return new byte[] {8, 9}; }
        @Override public WebViewState inspect(Map<String, String> a, long t) {
            calls.add("inspect");
            var security = new WebViewSnapshot.Security(true, false, "test-agent",
                true, true, false, true, "never", true);
            return new WebViewState("https://example.com/checkout?token=hidden", List.of(),
                List.of(new WebViewState.Cookie("session_id", "top-secret-cookie", ".example.com",
                    "/", true, true, "Lax", false, 1_900_000_000, false, 32)),
                List.of(new WebViewState.Storage("local", "https://example.com", "customerId",
                    "private-customer", 30)),
                List.of(new WebViewSnapshot.Bridge("Payments", List.of("getStatus", "startPayment"),
                    "agent", "EXACT")), security, List.of(), List.of());
        }
        @Override public boolean interfaceExists(Map<String, String> a, String name, String method, long t) {
            calls.add("interface:" + name + ":" + method);
            return name.equals("Android") && (method == null || method.equals("openCamera"));
        }
        @Override public void clearCookies(Map<String, String> a, long t) { calls.add("clear-cookies"); }
        @Override public void clearStorage(Map<String, String> a, long t) { calls.add("clear-storage"); }
        @Override public Map<String, String> metadata() { return Map.of("webTargetUrl", "https://example.com/checkout"); }
        @Override public void close() { closed = true; }
    }
}
