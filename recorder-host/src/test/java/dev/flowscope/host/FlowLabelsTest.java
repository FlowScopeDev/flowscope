package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowLabelsTest {
    @TempDir Path root;
    static class Adb extends AdbClient {
        @Override public CommandResult run(List<String> args, long timeout) { return new CommandResult(0, "", ""); }
        @Override public byte[] execOut(String serial, String... args) { return "<hierarchy/>".getBytes(); }
    }
    final Device device = new Device("test", "device", Map.of());
    final FlowHttpClient http = (method, uri, headers, body, timeout) -> new FlowHttpClient.Response(200, "{}");
    FlowTest parse(String steps) { return FlowTestParser.parseSource("flowscope 1\npackage com.example\n" + steps, "labels.flow"); }

    @Test void labelsDoNotChangeExistingNameArgumentsOrUnlabelledSteps() {
        var test = parse("""
            before set-value name=account value=demo label="Prepare account"
            step screenshot name=home label="Screenshot of Home Taken"
            step tap id=view_more label="Tapped View More"
            after force-stop
            """);
        assertEquals("account", test.steps().get(0).arguments().get("name"));
        assertEquals("home", test.steps().get(1).arguments().get("name"));
        assertEquals("Screenshot of Home Taken", test.steps().get(1).displayName());
        assertEquals("force-stop", test.steps().get(3).displayName());
        assertTrue(test.steps().stream().noneMatch(s -> s.arguments().containsKey("label")));
    }
    @Test void labelsSupportTokenizerEscapingAndConditionBoundaries() {
        var test = parse("""
            step if-visible id=optional label="Optional panel"
            step tap id=more label="Tap \\"More\\""
            step end label="Panel finished"
            """);
        assertEquals("Tap \"More\"", test.steps().get(1).label());
        assertEquals("Panel finished", test.steps().get(2).label());
    }
    @Test void rejectsDuplicateBlankOrDynamicLabelsWithoutEchoingValues() {
        for (String label : List.of("label=\"\"", "label=\"  \"", "label=\"${secret.PRIVATE}\"", "label=" + "x".repeat(201), "label=a label=b")) {
            assertThrows(FlowTestParser.ParseError.class, () -> parse("step launch " + label));
        }
        var failure = assertThrows(FlowTestParser.ParseError.class, () -> parse("step launch label=\"${env.PRIVATE}\""));
        assertFalse(failure.getMessage().contains("PRIVATE"));
    }
    @Test void runnerPersistsLabelsThroughLiveProgressAndCompletedReports() throws Exception {
        var test = parse("step screenshot name=home label=\"Screenshot of Home Taken\"\nafter force-stop label=\"Close app\"\n");
        var runner = new FlowTestRunner(new Adb(), device, test, root, "label-run", http, Map.of());
        List<String> live = new ArrayList<>();
        runner.observe(event -> {
            live.add(event.displayName());
            var snapshot = RunSteps.read(runner.root(), RunReport.read(runner.root()).orElseThrow());
            assertEquals("Screenshot of Home Taken", snapshot.groups().getFirst().steps().getFirst().displayName());
        });
        assertTrue(runner.run());
        assertTrue(Files.exists(runner.root().resolve("evidence/home.png")));
        assertTrue(live.contains("Close app"));
        assertTrue(Files.readString(runner.root().resolve("report.html")).contains("Screenshot of Home Taken"));
        var report = Json.parseObject(Files.readString(runner.root().resolve("report.json")));
        var first = (Map<?, ?>) ((List<?>) report.get("steps")).getFirst();
        assertEquals("screenshot", first.get("action"));
        assertEquals("Screenshot of Home Taken", first.get("label"));
        Files.delete(runner.root().resolve(RunSteps.FILE));
        assertEquals("Close app", RunSteps.read(runner.root(), RunReport.read(runner.root()).orElseThrow()).groups().getFirst().steps().get(1).displayName());
    }
    @Test void suiteEntryAndHookLabelsSurvivePlanRemovalAndKeepOriginalNames() throws Exception {
        Path tests = Files.createDirectories(root.resolve("flows/tests"));
        Path suites = Files.createDirectories(root.resolve("flows/suites"));
        Files.writeString(tests.resolve("one.flow"), "flowscope 1\nname Original\npackage com.example\nstep set-value name=x value=1 label=\"Prepare value\"\n");
        Path file = Files.writeString(suites.resolve("named.suite"), """
            flowscope-suite 1
            name "Label suite"
            before set-value name=x value=1 label="Prepare suite"
            test tests/one.flow label="Returning customer"
            test tests/one.flow label="New customer"
            after set-value name=done value=true label="Finish suite"
            """);
        var suite = FlowSuiteParser.parse(file);
        assertEquals("Prepare suite", suite.hooks().getFirst().label());
        var runner = new FlowSuiteRunner(new Adb(), device, suite, file, null, root.resolve("reports"), http, Map.of(), Map.of());
        List<String> live = new ArrayList<>(); runner.observe(p -> live.add(p.displayName()));
        assertTrue(runner.run());
        assertTrue(live.contains("Returning customer"));
        Files.delete(runner.root().resolve(RunSteps.FILE));
        var groups = RunSteps.read(runner.root(), RunReport.read(runner.root()).orElseThrow()).groups();
        assertEquals("Prepare suite", groups.getFirst().steps().getFirst().displayName());
        assertEquals("Returning customer", groups.get(1).name());
        assertEquals("New customer", groups.get(2).name());
        assertEquals("Finish suite", groups.getLast().steps().getFirst().displayName());
        var report = Files.readString(runner.root().resolve("suite-report.json"));
        assertTrue(report.contains("\"name\": \"Original\""));
        assertTrue(Files.readString(runner.root().resolve("junit.xml")).contains("Returning customer"));
    }
    @Test void cancellationRetainsLabelsForCancelledSkippedAndCleanupSteps() throws Exception {
        var signal = new RunCancellation();
        var test = parse("step api-request url=https://example.test label=\"Fetch profile\"\nstep launch label=\"Open app\"\nafter force-stop label=\"Cleanup\"");
        FlowHttpClient cancelling = (m,u,h,b,t) -> { signal.request(); return new FlowHttpClient.Response(200,"{}"); };
        var runner = new FlowTestRunner(new Adb(), device, test, root, "cancel-labels", cancelling, Map.of()).cancellation(signal);
        assertFalse(runner.run());
        var steps = RunSteps.read(runner.root(), RunReport.read(runner.root()).orElseThrow()).groups().getFirst().steps();
        assertEquals(List.of("Fetch profile", "Open app", "Cleanup"), steps.stream().map(RunSteps.Step::displayName).toList());
        assertEquals(List.of("cancelled", "skipped", "passed"), steps.stream().map(RunSteps.Step::status).toList());
    }
    @Test void escapedLabelIsSafeInHtmlAndFailureMessages() throws Exception {
        var test = parse("step api-request url=https://example.test expect-status=404 label=\"Check <profile>\"");
        var runner = new FlowTestRunner(new Adb(), device, test, root, "failed-label", http, Map.of());
        assertFalse(runner.run());
        assertTrue(runner.failureMessage().contains("Check <profile>"));
        assertTrue(Files.readString(runner.root().resolve("report.html")).contains("Check &lt;profile&gt;"));
        assertTrue(Files.readString(runner.root().resolve("junit.xml")).contains("Check &lt;profile&gt;"));
    }
}
