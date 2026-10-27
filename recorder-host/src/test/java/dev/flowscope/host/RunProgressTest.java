package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * `progress.jsonl` lets another process — the IDE watching a CLI run — see a run while it is in
 * flight. It must describe the run live, disappear once the report exists, and never leave a dead
 * run looking alive.
 */
class RunProgressTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    @Test
    void aTestRunIsListedAsRunningUntilItsReportReplacesTheProgressFile() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            before set-value name=x value=hello
            step force-stop
            after force-stop
            """, "live.flow");
        List<RunReport> seen = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        FlowTestRunner[] runner = new FlowTestRunner[1];
        runner[0] = new FlowTestRunner(new FakeAdb(), DEVICE, test, temp, "live", new NoHttp(),
            Map.of()).observe(event -> {
                if (event.number() == 2 && event.status().equals("running")) {
                    seen.add(RunReport.read(runner[0].root()).orElseThrow());
                    try {
                        raw.add(Files.readString(runner[0].root().resolve(RunProgress.FILE)));
                    } catch (java.io.IOException unreadable) {
                        throw new AssertionError(unreadable);
                    }
                }
            });

        assertTrue(runner[0].run());

        RunReport live = seen.get(0);
        assertEquals("running", live.status());
        assertTrue(live.isRunning());
        assertEquals("test", live.kind());
        assertEquals("live.flow", live.name());
        assertEquals(3, live.total());
        assertEquals(1, live.passed());
        assertEquals("step 2/3 · force-stop", live.activity());
        // Literal actions only — never an interpolated argument value.
        assertFalse(raw.get(0).contains("hello"), raw.get(0));

        assertFalse(Files.exists(runner[0].root().resolve(RunProgress.FILE)));
        RunReport finished = RunReport.read(runner[0].root()).orElseThrow();
        assertEquals("passed", finished.status());
        assertNull(finished.activity());
    }

    @Test
    void aSuiteWritesOneProgressFileThatNamesTheCurrentTestAndStep() throws Exception {
        Path flows = temp.resolve("flows");
        Files.createDirectories(flows.resolve("tests"));
        Files.createDirectories(flows.resolve("suites"));
        Files.writeString(flows.resolve("tests/a.flow"), """
            flowscope 1
            package com.example.debug
            step force-stop
            """);
        Files.writeString(flows.resolve("tests/b.flow"), """
            flowscope 1
            package com.example.debug
            step force-stop
            """);
        Path suiteFile = flows.resolve("suites/a.suite");
        Files.writeString(suiteFile, """
            flowscope-suite 1
            name Nightly
            test tests/a.flow
            test tests/b.flow
            """);
        FakeAdb adb = new FakeAdb();
        FlowSuiteRunner runner = new FlowSuiteRunner(adb, DEVICE, FlowSuiteParser.parse(suiteFile),
            suiteFile, null, temp.resolve("reports"), new NoHttp(), Map.of(), Map.of());
        adb.onForceStop = () -> {
            RunReport live = RunReport.read(runner.root()).orElseThrow();
            if (live.passed() == 1) {
                assertEquals("suite", live.kind());
                assertEquals("Nightly", live.name());
                assertEquals("running", live.status());
                assertEquals(2, live.total());
                assertEquals("test 2/2 · tests/b.flow · step 1/1 · force-stop", live.activity());
                adb.checked = true;
            }
        };

        assertTrue(runner.run());

        assertTrue(adb.checked);
        assertFalse(Files.exists(runner.root().resolve(RunProgress.FILE)));
        try (var children = Files.walk(runner.root())) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().equals(RunProgress.FILE)),
                "a suite's child tests report through the suite's progress file");
        }
        assertEquals("passed", RunReport.read(runner.root()).orElseThrow().status());
    }

    @Test
    void aRunWhoseProcessIsGoneIsInterruptedNotRunning() throws Exception {
        Process finished = new ProcessBuilder("true").start();
        finished.waitFor();
        Path run = Files.createDirectories(temp.resolve("20261027-101500-Dead"));
        Files.writeString(run.resolve(RunProgress.FILE), """
            {"event":"start","schemaVersion":1,"kind":"test","name":"Dead","total":4,"pid":%d,"processStartedAt":null,"startedAt":"2026-10-27T10:15:00Z"}
            {"event":"step","number":1,"total":4,"phase":"test","action":"launch","line":3,"status":"passed"}
            {"event":"step","number":2,"total":4,"phase":"test","action":"tap","line":4,"status":"running"}
            """.formatted(finished.pid()), StandardCharsets.UTF_8);

        RunReport report = RunReport.read(run).orElseThrow();
        assertEquals("interrupted", report.status());
        assertTrue(report.isInterrupted());
        assertEquals(4, report.total());
        assertEquals(1, report.passed());
        assertNull(report.activity());
    }

    @Test
    void anEndEventMarksTheRunInterruptedEvenWhileItsProcessLives() throws Exception {
        Path run = Files.createDirectories(temp.resolve("ended"));
        RunProgress progress = RunProgress.start(run, "test", "Ended", 2);
        progress.interrupt();
        // A second interrupt, or a finish after it, is a no-op: the file keeps its verdict.
        progress.interrupt();

        assertEquals("interrupted", RunReport.read(run).orElseThrow().status());
    }

    @Test
    void aTornLastLineIsSkippedAndAMissingStartIsNotARun() throws Exception {
        Path run = Files.createDirectories(temp.resolve("torn"));
        RunProgress.start(run, "test", "Torn", 2);
        Files.writeString(run.resolve(RunProgress.FILE), "{\"event\":\"step\",\"num",
            StandardOpenOption.APPEND);
        RunReport report = RunReport.read(run).orElseThrow();
        assertEquals("running", report.status());
        assertEquals("Torn", report.name());

        Path headless = Files.createDirectories(temp.resolve("headless"));
        Files.writeString(headless.resolve(RunProgress.FILE),
            "{\"event\":\"step\",\"number\":1,\"total\":1,\"status\":\"running\"}\n");
        assertTrue(RunReport.read(headless).isEmpty());
    }

    @Test
    void theFingerprintMovesWithProgressAndStaysPutOtherwise() throws Exception {
        assertEquals("", RunReport.fingerprint(temp.resolve("missing"), 10));
        Path reports = Files.createDirectories(temp.resolve("reports"));
        String empty = RunReport.fingerprint(reports, 10);

        Path run = Files.createDirectories(reports.resolve("20261027-101500-Live"));
        RunProgress progress = RunProgress.start(run, "test", "Live", 1);
        String started = RunReport.fingerprint(reports, 10);
        assertNotEquals(empty, started);
        assertEquals(started, RunReport.fingerprint(reports, 10));

        progress.step(new FlowTestRunner.StepProgress(1, 1, "test", "launch", 3, "running"));
        assertNotEquals(started, RunReport.fingerprint(reports, 10));
    }

    private static final class NoHttp implements FlowHttpClient {
        @Override public Response send(String method, java.net.URI uri, Map<String, String> headers,
            String body, long timeoutMillis) {
            throw new AssertionError("no HTTP expected");
        }
    }

    private static final class FakeAdb extends AdbClient {
        Runnable onForceStop = () -> {};
        boolean checked;

        @Override public CommandResult run(List<String> arguments, long timeoutMillis) {
            if (arguments.contains("force-stop")) onForceStop.run();
            if (arguments.contains("resolve-activity")) {
                return new CommandResult(0, "com.example.debug/.MainActivity\n", "");
            }
            return new CommandResult(0, "Success", "");
        }
        @Override public byte[] execOut(String serial, String... command) {
            return new byte[0];
        }
    }
}
