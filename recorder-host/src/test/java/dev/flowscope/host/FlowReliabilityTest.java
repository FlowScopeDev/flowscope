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

class FlowReliabilityTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    @Test void diskValidationRejectsTraversalMissingFilesAndSymlinkEscapes() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("tests"));
        Files.writeString(temp.resolve("outside.json"), "{}");
        Path flow = folder.resolve("test.flow");
        for (String file : List.of("../outside.json", "missing.json", "/tmp/outside.json")) {
            Files.writeString(flow, source("step api-request url=https://example.test body-file=" + file));
            assertThrows(FlowTestParser.ParseError.class, () -> FlowTestParser.parse(flow));
        }
        Files.createSymbolicLink(folder.resolve("escape.json"), temp.resolve("outside.json"));
        Files.writeString(flow, source("step api-request url=https://example.test body-file=escape.json"));
        assertTrue(assertThrows(FlowTestParser.ParseError.class, () -> FlowTestParser.parse(flow))
            .getMessage().contains("symbolic link"));
        // Runtime uses the same check, including when a previously safe file is replaced.
        Files.writeString(folder.resolve("body.json"), "{}");
        Files.writeString(flow, source("step api-request url=https://example.test body-file=body.json"));
        FlowTest parsed = FlowTestParser.parse(flow);
        Files.delete(folder.resolve("body.json"));
        Files.createSymbolicLink(folder.resolve("body.json"), temp.resolve("outside.json"));
        FakeAdb adb = new FakeAdb();
        assertThrows(IllegalArgumentException.class, () -> runner(adb, parsed).run());
        assertTrue(adb.commands.isEmpty());
    }

    @Test void fixturesWorkStandaloneForNestedTestsAndSuiteHooks() throws Exception {
        Path flows = Files.createDirectories(temp.resolve("flows"));
        Path tests = Files.createDirectories(flows.resolve("tests/stages/one"));
        Path suites = Files.createDirectories(flows.resolve("suites"));
        Path fixtures = Files.createDirectories(flows.resolve("fixtures"));
        Files.writeString(fixtures.resolve("setup.json"), "{\"fixture\":true}");
        Path flow = tests.resolve("a.flow");
        Files.writeString(flow, source("step api-request method=POST url=https://example.test body-file=@fixtures/setup.json"));
        FlowTest parsed = FlowTestParser.parse(flow);
        List<String> bodies = new ArrayList<>();
        FlowHttpClient http = (m, u, h, b, t) -> { bodies.add(b); return new FlowHttpClient.Response(200, "{}"); };
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, parsed, temp, "fixtures", http, Map.of());
        assertTrue(runner.run());
        assertEquals(List.of("{\"fixture\":true}"), bodies);
        Path suite = suites.resolve("a.suite");
        Files.writeString(suite, "flowscope-suite 1\nbefore api-request url=https://example.test body-file=@fixtures/setup.json\ntest tests/stages/one/a.flow\n");
        assertEquals(1, FlowSuiteParser.parse(suite).hooks().size());
        Files.createSymbolicLink(fixtures.resolve("escape.json"), flow);
        assertThrows(IllegalArgumentException.class, () -> FlowFiles.resolve(tests, "@fixtures/escape.json", "body-file"));
        assertThrows(IllegalArgumentException.class, () -> FlowFiles.resolve(tests, "@fixtures/../tests/stages/one/a.flow", "body-file"));
    }

    @Test void fixtureDirectoryCannotBeASymlinkOutsideFlows() throws Exception {
        Path flows = Files.createDirectories(temp.resolve("flows"));
        Path tests = Files.createDirectories(flows.resolve("tests"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("body.json"), "{}");
        Files.createSymbolicLink(flows.resolve("fixtures"), outside);
        assertThrows(IllegalArgumentException.class, () -> FlowFiles.resolve(tests, "@fixtures/body.json", "body-file"));
    }

    @Test void withinIdUsesAncestryBeforeIndexAndStateFilters() throws Exception {
        var nodes = hierarchy("<node resource-id=\"home\" bounds=\"[0,0][100,100]\">"
            + node("card", "enabled=\"false\"") + node("card", "enabled=\"true\"") + "</node>"
            + "<node resource-id=\"sheet\" bounds=\"[0,0][100,100]\">" + node("card", "enabled=\"true\"") + "</node>");
        var found = UiHierarchy.find(nodes, Map.of("id", "card", "within-id", "home", "enabled", "true"));
        assertEquals("home", found.parent().id());
        assertNull(UiHierarchy.find(nodes, Map.of("id", "card", "within-id", "home", "enabled", "true", "index", "1")));
        assertNull(UiHierarchy.find(nodes, Map.of("id", "home", "within-id", "home")));
        assertThrows(FlowTestParser.ParseError.class, () -> parse("step tap within-id=home"));
        assertThrows(FlowTestParser.ParseError.class, () -> parse("step tap id=card within-id=\"\""));
    }

    @Test void coverageComparesBranchesAndIgnoresTargetsOwnChildren() throws Exception {
        var nodes = hierarchy("<node bounds=\"[0,0][100,100]\">"
            + "<node resource-id=\"content\" drawing-order=\"1\" bounds=\"[0,0][100,100]\">"
            + node("target", "drawing-order=\"99\"") + "</node>"
            + node("need_help_btn", "drawing-order=\"2\"") + "</node>");
        var target = UiHierarchy.find(nodes, Map.of("id", "target"));
        assertEquals("need_help_btn", UiHierarchy.covering(nodes, target, 50, 50).id());
        var related = hierarchy("<node resource-id=\"target\" drawing-order=\"1\" bounds=\"[0,0][100,100]\">"
            + node("label", "drawing-order=\"2\"") + "</node>");
        assertNull(UiHierarchy.covering(related, related.getFirst(), 50, 50));
        var windows = hierarchy("<window>" + node("target", "") + "</window><window>" + node("dialog", "") + "</window>");
        assertEquals("dialog", UiHierarchy.covering(windows, windows.getFirst(), 50, 50).id());
    }

    @Test void coveredTapFailsBeforeSendingInput() throws Exception {
        FakeAdb adb = new FakeAdb();
        adb.nodes = node("target", "drawing-order=\"1\"") + node("need_help_btn", "drawing-order=\"2\"");
        FlowTestRunner runner = runner(adb, parse("step tap id=target"));
        assertFalse(runner.run());
        assertTrue(runner.failureMessage().contains("covered by need_help_btn"));
        assertFalse(adb.commands.stream().anyMatch(s -> s.contains("input tap")));
    }

    @Test void nestedOptionalBlocksSkipWithoutResolvingValuesAndCleanupStillRuns() throws Exception {
        FakeAdb adb = new FakeAdb();
        FlowTestRunner runner = runner(adb, parse("""
            step if-visible id=absent
            step if-visible id=target
            step type value=${var.unset}
            step end
            step tap id=target
            step end
            step if-visible id=target
            step screenshot-if-visible id=target name=shown
            step end
            step screenshot-if-visible id=absent name=hidden
            after screenshot name=cleanup
            """));
        assertTrue(runner.run());
        assertFalse(adb.commands.stream().anyMatch(s -> s.contains("input tap")));
        assertTrue(Files.exists(runner.root().resolve("evidence/shown.png")));
        assertFalse(Files.exists(runner.root().resolve("evidence/hidden.png")));
        assertTrue(Files.exists(runner.root().resolve("evidence/cleanup.png")));
        assertTrue(Files.readString(runner.root().resolve("report.json")).contains("\"skipped\""));
    }

    @Test void blockValidationRejectsUnbalancedAndCrossPhaseBlocks() {
        for (String steps : List.of("step end", "step if-visible id=target", "before if-visible id=target\nstep end",
            "step if-visible id=target\nstep end timeout=1")) {
            assertThrows(FlowTestParser.ParseError.class, () -> parse(steps), steps);
        }
    }

    @Test void readinessRejectsLockedOffAndForeignFocusBeforeSetup() throws Exception {
        for (String problem : List.of("locked", "off", "foreign")) {
            FakeAdb adb = new FakeAdb();
            adb.policy = problem.equals("locked") ? "mShowingLockscreen=true" : "";
            adb.power = problem.equals("off") ? "mWakefulness=Asleep" : "";
            adb.focus = problem.equals("foreign") ? "mCurrentFocus=Window{abc u0 com.pritunl/.Password}" : "";
            FlowTestRunner runner = runner(adb, parse("before api-request url=https://example.test\nstep tap id=target"));
            assertFalse(runner.run());
            assertTrue(runner.failureMessage().contains(switch (problem) {
                case "locked" -> "device is locked";
                case "off" -> "device screen is off";
                default -> "foreground is com.pritunl";
            }));
            assertFalse(adb.commands.stream().anyMatch(s -> s.contains("input tap")));
        }
    }

    @Test void launchMayStartFromAnotherAppAndRetainsEachExecutedStepsHierarchy() throws Exception {
        FakeAdb adb = new FakeAdb();
        adb.focus = "mCurrentFocus=Window{abc u0 com.launcher/.Home}";
        FlowTestRunner runner = runner(adb, parse("step launch\nstep tap id=target")).keepHierarchy(true);
        assertTrue(runner.run());
        for (int i = 1; i <= 2; i++) {
            Path xml = runner.root().resolve("evidence/step-0" + i + ".xml");
            assertFalse(UiHierarchy.parse(Files.readAllBytes(xml)).isEmpty());
        }
        String report = Files.readString(runner.root().resolve("report.json"));
        assertTrue(report.contains("\"hierarchy\": \"evidence/step-01.xml\""));
    }

    @Test void containerEndIgnoresChangingClockAndUsesSlowSwipe() throws Exception {
        FakeAdb adb = new FakeAdb();
        adb.nodes = "<node resource-id=\"list\" scrollable=\"true\" bounds=\"[0,0][100,100]\">"
            + node("row", "") + "</node>";
        adb.clock = true;
        FlowTestRunner runner = runner(adb, parse("step scroll-to id=missing max-swipes=4"));
        assertFalse(runner.run());
        assertTrue(runner.failureMessage().contains("Reached the end of the content"));
        assertEquals(1, adb.commands.stream().filter(c -> c.contains("input swipe")).count());
        assertTrue(adb.commands.stream().anyMatch(c -> c.contains("input swipe") && c.endsWith("1000")));
        assertTrue(adb.commands.stream().anyMatch(c -> c.contains("rm -f /data/local/tmp/flowscope-test-window.xml")));
    }

    @Test void aSingleStalePostSwipeDumpDoesNotFakeTheEnd() throws Exception {
        FakeAdb adb = new FakeAdb() {
            int reads;
            @Override public byte[] execOut(String serial, String... command) {
                if (List.of(command).contains("cat")) {
                    nodes = "<node scrollable=\"true\" bounds=\"[0,0][100,100]\">"
                        + node(++reads >= 5 ? "target" : "row", "") + "</node>";
                }
                return super.execOut(serial, command);
            }
        };
        FlowTestRunner runner = runner(adb, parse("step scroll-to id=target max-swipes=3"));
        assertTrue(runner.run());
        assertEquals(1, adb.commands.stream().filter(c -> c.contains("input swipe")).count());
    }

    @Test void fixturesRespectCustomProjectFlowsDirectory() throws Exception {
        Files.writeString(temp.resolve("flowscope.json"),
            "{\"schemaVersion\":1,\"name\":\"test\",\"flowsDir\":\"automation\"}");
        Path tests = Files.createDirectories(temp.resolve("automation/stages"));
        Path fixtures = Files.createDirectories(temp.resolve("automation/fixtures"));
        Path payload = Files.writeString(fixtures.resolve("body.json"), "{}");
        assertEquals(payload.toRealPath(), FlowFiles.resolve(tests, "@fixtures/body.json", "body-file"));
    }

    @Test void hierarchyRedactionPreservesXmlAndDecodesEscapedSecrets() throws Exception {
        byte[] raw = ("<hierarchy>" + node("target", "text=\"a&amp;b\"") + "</hierarchy>").getBytes(StandardCharsets.UTF_8);
        byte[] safe = UiHierarchy.redact(raw, value -> value.replace("a&b", "{secret}"));
        assertEquals("{secret}", UiHierarchy.parse(safe).getFirst().text());
    }

    @Test void failedOptionalBodyStillRunsCleanup() throws Exception {
        FakeAdb adb = new FakeAdb();
        FlowTestRunner runner = runner(adb, parse("step if-visible id=target\nstep assert-visible id=missing\nstep end\nafter screenshot name=cleanup"));
        assertFalse(runner.run());
        assertTrue(Files.exists(runner.root().resolve("evidence/cleanup.png")));
    }

    @Test void suitePropagatesHierarchyFlagAndChecksDeviceBeforeHooks() throws Exception {
        Path flows = Files.createDirectories(temp.resolve("flows"));
        Path tests = Files.createDirectories(flows.resolve("tests"));
        Path suites = Files.createDirectories(flows.resolve("suites"));
        Files.writeString(tests.resolve("a.flow"), source("step assert-visible id=target"));
        Path suiteFile = Files.writeString(suites.resolve("a.suite"), "flowscope-suite 1\ntest tests/a.flow\n");
        FakeAdb adb = new FakeAdb();
        FlowHttpClient http = (m, u, h, b, t) -> { throw new AssertionError("Must fail before HTTP setup"); };
        FlowSuiteRunner runner = new FlowSuiteRunner(adb, DEVICE, FlowSuiteParser.parse(suiteFile), suiteFile,
            null, temp.resolve("reports"), http, Map.of(), Map.of()).keepHierarchy(true);
        assertTrue(runner.run());
        try (var files = Files.walk(runner.root())) {
            assertEquals(1, files.filter(f -> f.getFileName().toString().equals("step-01.xml")).count());
        }
        Files.writeString(suiteFile, "flowscope-suite 1\nbefore api-request url=https://example.test\ntest tests/a.flow\n");
        adb.policy = "mShowingLockscreen=true";
        FlowSuiteRunner locked = new FlowSuiteRunner(adb, DEVICE, FlowSuiteParser.parse(suiteFile), suiteFile,
            null, temp.resolve("locked"), http, Map.of(), Map.of());
        assertFalse(locked.run());
        assertTrue(Files.readString(locked.root().resolve("suite-report.json")).contains("device is locked"));
    }

    @Test void focusedDisplayDialogTakesPrecedenceOverResumedApp() {
        FakeAdb adb = new FakeAdb() {
            @Override public CommandResult run(List<String> args, long timeout) {
                String command = String.join(" ", args);
                if (command.endsWith("window displays")) {
                    return new CommandResult(0, "mCurrentFocus=Window{abc u0 com.pritunl/.Password}", "");
                }
                if (command.endsWith("activity activities")) {
                    return new CommandResult(0, "mResumedActivity=ActivityRecord{abc u0 com.example/.Main}", "");
                }
                return super.run(args, timeout);
            }
        };
        Exception failure = assertThrows(IllegalStateException.class,
            () -> DeviceReadiness.check(adb, DEVICE.serial(), "com.example", true));
        assertTrue(failure.getMessage().contains("foreground is com.pritunl"));
    }

    private FlowTestRunner runner(FakeAdb adb, FlowTest test) throws Exception {
        return new FlowTestRunner(adb, DEVICE, test, temp, "run-" + System.nanoTime(),
            (m, u, h, b, t) -> { throw new AssertionError("Unexpected HTTP request"); }, Map.of());
    }
    private static String source(String steps) { return "flowscope 1\npackage com.example\ntimeout 1\n" + steps; }
    private static FlowTest parse(String steps) { return FlowTestParser.parseSource(source(steps), "test.flow"); }
    private static String node(String id, String attrs) {
        return "<node resource-id=\"" + id + "\" bounds=\"[0,0][100,100]\" " + attrs + "/>";
    }
    private static List<UiHierarchy.UiNode> hierarchy(String nodes) throws Exception {
        return UiHierarchy.parse(("<hierarchy>" + nodes + "</hierarchy>").getBytes(StandardCharsets.UTF_8));
    }
    private static class FakeAdb extends AdbClient {
        List<String> commands = new ArrayList<>();
        String nodes = node("target", "");
        String policy = "", power = "", focus = "";
        boolean clock;
        int dumps;
        FakeAdb() { super("unused"); }
        @Override public CommandResult run(List<String> args, long timeout) {
            String cmd = String.join(" ", args); commands.add(cmd);
            String out = "";
            if (cmd.endsWith("window policy")) out = policy;
            if (cmd.endsWith("dumpsys power")) out = power;
            if (cmd.endsWith("window windows")) out = focus;
            if (cmd.contains("resolve-activity")) out = "com.example/.Main";
            if (cmd.contains("am start")) focus = "mCurrentFocus=Window{abc u0 com.example/.Main}";
            if (cmd.endsWith("wm size")) out = "Physical size: 100x100";
            return new CommandResult(0, out, "");
        }
        @Override public byte[] execOut(String serial, String... command) {
            if (!List.of(command).contains("cat")) return new byte[0];
            return ("<hierarchy>" + nodes + (clock ? node("clock", "text=\"" + (++dumps) + "\"") : "")
                + "</hierarchy>").getBytes(StandardCharsets.UTF_8);
        }
    }
}
