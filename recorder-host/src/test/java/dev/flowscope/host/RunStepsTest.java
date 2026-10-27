package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStepsTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    @Test void runnerPublishesAllStepsBeforeExecutionAndKeepsFinalResults() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example
            before set-value name=first value=private-sentinel
            step set-value name=second value=other-sentinel
            after set-value name=third value=cleanup-sentinel
            """, "test.flow");
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, test, temp, "run",
            FlowHttpClient.platform(), Map.of());
        List<RunSteps.Snapshot> observed = new ArrayList<>();
        runner.observe(event -> {
            if (event.status().equals("running")) observed.add(read(runner.root()));
        });
        assertTrue(runner.run());
        assertEquals(List.of("running", "pending", "pending"), statuses(observed.get(0), "test"));
        assertEquals(List.of("passed", "running", "pending"), statuses(observed.get(1), "test"));
        assertEquals(List.of("passed", "passed", "running"), statuses(observed.get(2), "test"));
        assertEquals(List.of("passed", "passed", "passed"), statuses(read(runner.root()), "test"));
        String plan = Files.readString(runner.root().resolve(RunSteps.FILE));
        assertFalse(plan.contains("sentinel"));
        assertFalse(plan.contains("value="));
        assertFalse(Files.exists(runner.root().resolve(RunProgress.FILE)));
        assertEquals(List.of("before", "test", "after"), read(runner.root()).groups().getFirst().steps().stream().map(RunSteps.Step::phase).toList());
    }

    @Test void suiteIncludesFutureTestsHooksAndSkippedStepsAfterFailure() throws Exception {
        Path flows = Files.createDirectories(temp.resolve("flows"));
        Path tests = Files.createDirectories(flows.resolve("tests"));
        Path suites = Files.createDirectories(flows.resolve("suites"));
        Files.writeString(tests.resolve("a.flow"), """
            flowscope 1
            package com.example
            step api-request url=https://example.test/test expect-status=201
            step set-value name=never value=hidden
            """);
        Files.writeString(tests.resolve("b.flow"), """
            flowscope 1
            package com.example
            step set-value name=future value=hidden
            """);
        Path suiteFile = Files.writeString(suites.resolve("test.suite"), """
            flowscope-suite 1
            before api-request url=https://example.test/setup
            test tests/a.flow
            test tests/b.flow
            after api-request url=https://example.test/cleanup
            """);
        List<RunSteps.Snapshot> observed = new ArrayList<>();
        FlowSuiteRunner[] holder = new FlowSuiteRunner[1];
        FlowHttpClient http = (m, u, h, b, t) -> {
            observed.add(read(holder[0].root()));
            return new FlowHttpClient.Response(200, "{}");
        };
        holder[0] = new FlowSuiteRunner(new FakeAdb(), DEVICE, FlowSuiteParser.parse(suiteFile), suiteFile,
            null, temp.resolve("reports"), http, Map.of(), Map.of());
        assertFalse(holder[0].run());
        assertEquals(List.of("running"), statuses(observed.get(0), "before"));
        assertEquals(List.of("pending", "pending"), statuses(observed.get(0), "test:1"));
        assertEquals(List.of("pending"), statuses(observed.get(0), "test:2"));
        assertEquals(List.of("running", "pending"), statuses(observed.get(1), "test:1"));
        assertEquals(List.of("skipped"), statuses(observed.get(2), "test:2"));
        assertEquals(List.of("running"), statuses(observed.get(2), "after"));
        RunSteps.Snapshot finished = read(holder[0].root());
        assertEquals(List.of("before", "test:1", "test:2", "after"), finished.groups().stream().map(RunSteps.Group::id).toList());
        assertEquals(List.of("failed", "skipped"), statuses(finished, "test:1"));
        assertEquals(List.of("skipped"), statuses(finished, "test:2"));
        assertEquals(List.of("passed"), statuses(finished, "after"));
    }

    @Test void interruptionKeepsPassedMarksCurrentInterruptedAndFutureNotRun() throws Exception {
        FlowTest test = FlowTestParser.parseSource("flowscope 1\npackage com.example\nstep launch\nstep tap id=button\nafter force-stop", "test");
        RunSteps.write(temp, List.of(RunSteps.testGroup(0, "", test, "report.json")));
        RunProgress progress = RunProgress.start(temp, "test", "test", 3);
        progress.step(new FlowTestRunner.StepProgress(1, 3, "test", "launch", 3, "passed"));
        progress.step(new FlowTestRunner.StepProgress(2, 3, "test", "tap", 4, "running"));
        progress.interrupt();
        assertEquals(List.of("passed", "interrupted", "not-run"), statuses(read(temp), "test"));
    }

    @Test void repeatedSuiteTestsKeepIndependentStepNumbersAndTornUpdatesAreIgnored() throws Exception {
        FlowTest test = FlowTestParser.parseSource("flowscope 1\npackage com.example\nstep launch\nstep back", "Repeated");
        RunSteps.write(temp, List.of(RunSteps.testGroup(1, "same.flow", test, "01/report.json"),
            RunSteps.testGroup(2, "same.flow", test, "02/report.json")));
        RunProgress progress = RunProgress.start(temp, "suite", "suite", 2);
        progress.test(new FlowSuiteRunner.Progress(1, 2, "same.flow", "running"));
        progress.step(new FlowTestRunner.StepProgress(1, 2, "test", "launch", 3, "passed"));
        progress.test(new FlowSuiteRunner.Progress(2, 2, "same.flow", "running"));
        progress.step(new FlowTestRunner.StepProgress(1, 2, "test", "launch", 3, "running"));
        Files.writeString(temp.resolve(RunProgress.FILE), "{\"event\":", StandardOpenOption.APPEND);
        assertEquals(List.of("passed", "pending"), statuses(read(temp), "test:1"));
        assertEquals(List.of("running", "pending"), statuses(read(temp), "test:2"));
    }

    @Test void olderLiveRunsShowKnownStepsAndUnnamedPendingRows() {
        RunProgress progress = RunProgress.start(temp, "test", "old", 3);
        progress.step(new FlowTestRunner.StepProgress(1, 3, "test", "launch", 3, "passed"));
        progress.step(new FlowTestRunner.StepProgress(2, 3, "test", "tap", 4, "running"));
        RunSteps.Snapshot snapshot = read(temp);
        assertFalse(snapshot.planned());
        assertEquals(List.of("passed", "running", "pending"), statuses(snapshot, "test"));
        assertEquals("", snapshot.groups().getFirst().steps().getLast().action());
    }

    @Test void olderCompletedReportsStillExposeTheirSteps() throws Exception {
        Files.writeString(temp.resolve("report.json"), """
            {"name":"old","status":"failed","steps":[
            {"number":1,"line":3,"phase":"test","action":"launch","status":"passed"},
            {"number":2,"line":4,"phase":"test","action":"tap","status":"failed"},
            {"number":3,"line":5,"phase":"test","action":"back","status":"skipped"}]}
            """);
        assertEquals(List.of("passed", "failed", "skipped"), statuses(read(temp), "test"));
    }

    @Test void childReportReferenceCannotEscapeRunDirectory() throws Exception {
        Path run = Files.createDirectories(temp.resolve("run"));
        Files.writeString(temp.resolve("report.json"), """
            {"steps":[{"number":1,"action":"outside","status":"passed"}]}
            """);
        Files.writeString(run.resolve("suite-report.json"), """
            {"name":"suite","status":"failed","tests":[{"number":1,"name":"bad","status":"failed","report":"../report.json"}]}
            """);
        assertTrue(read(run).groups().isEmpty());
    }

    private static RunSteps.Snapshot read(Path path) {
        return RunSteps.read(path, RunReport.read(path).orElse(null));
    }
    private static List<String> statuses(RunSteps.Snapshot snapshot, String id) {
        return snapshot.groups().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow()
            .steps().stream().map(RunSteps.Step::status).toList();
    }
    private static final class FakeAdb extends AdbClient {
        @Override public CommandResult run(List<String> args, long timeout) { return new CommandResult(0, "", ""); }
        @Override public byte[] execOut(String serial, String... args) { return "<hierarchy/>".getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    }
}
