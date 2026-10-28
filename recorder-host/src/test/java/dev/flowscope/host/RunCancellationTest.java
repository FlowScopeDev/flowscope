package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class RunCancellationTest {
    @TempDir Path temp;
    final Device device = new Device("TEST", "device", Map.of());
    static class Adb extends AdbClient {
        @Override public CommandResult run(List<String> args, long timeout) { return new CommandResult(0, "", ""); }
    }
    FlowTestRunner runner(String steps, FlowHttpClient http, RunCancellation signal) throws Exception {
        var test = FlowTestParser.parseSource("flowscope 1\npackage com.example.debug\n" + steps, "cancel.flow");
        return new FlowTestRunner(new Adb(), device, test, temp, UUID.randomUUID().toString(), http, Map.of()).cancellation(signal);
    }
    static class Http implements FlowHttpClient {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch cleanupEntered = new CountDownLatch(1);
        final CountDownLatch releaseCleanup = new CountDownLatch(1);
        boolean holdCleanup, failCleanup;
        public Response send(String method, java.net.URI uri, Map<String,String> headers, String body, long timeout) throws Exception {
            String path = uri.getPath(); calls.add(path);
            if (path.equals("/wait")) { entered.countDown(); Thread.sleep(60_000); }
            if (path.equals("/cleanup")) {
                assertFalse(Thread.currentThread().isInterrupted());
                cleanupEntered.countDown();
                if (holdCleanup) assertTrue(releaseCleanup.await(5, TimeUnit.SECONDS));
                if (failCleanup) throw new IllegalStateException("cleanup failed");
            }
            return new Response(200, "{}");
        }
    }
    @Test void interruptsActiveRequestSkipsFutureWorkAndProtectsCleanupFromRepeatedStop() throws Exception {
        Http http = new Http(); http.holdCleanup = true;
        RunCancellation signal = new RunCancellation();
        var runner = runner("""
            step api-request url=https://example.test/wait timeout=60000 retry-interval=1000
            step api-request url=https://example.test/future
            after api-request url=https://example.test/cleanup
            after api-request url=https://example.test/cleanup2
            """, http, signal);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> result = executor.submit(runner::run);
            assertTrue(http.entered.await(3, TimeUnit.SECONDS));
            assertTrue(signal.request());
            assertTrue(http.cleanupEntered.await(3, TimeUnit.SECONDS));
            assertFalse(signal.request());
            assertFalse(result.isDone());
            http.releaseCleanup.countDown();
            assertFalse(result.get(3, TimeUnit.SECONDS));
            assertFalse(executor.submit(() -> Thread.currentThread().isInterrupted()).get());
        }
        assertEquals(List.of("/wait", "/cleanup", "/cleanup2"), http.calls);
        assertFalse(signal.request());
        var summary = RunReport.read(runner.root()).orElseThrow();
        assertTrue(summary.isCancelled()); assertEquals(0, summary.failed());
        var steps = RunSteps.read(runner.root(), summary).groups().getFirst().steps();
        assertEquals(List.of("cancelled", "skipped", "passed", "passed"), steps.stream().map(RunSteps.Step::status).toList());
        assertTrue(Files.readString(runner.root().resolve("junit.xml")).contains("<skipped"));
        assertFalse(Files.exists(runner.root().resolve(RunProgress.FILE)));
    }
    @Test void stopBeforeExecutionStillRunsAllCleanupAndKeepsCleanupFailures() throws Exception {
        Http http = new Http(); http.failCleanup = true;
        RunCancellation signal = new RunCancellation(); signal.request();
        var runner = runner("""
            before api-request url=https://example.test/setup
            step api-request url=https://example.test/future
            after api-request url=https://example.test/cleanup
            after api-request url=https://example.test/cleanup2
            """, http, signal);
        assertFalse(runner.run());
        assertEquals(List.of("/cleanup", "/cleanup2"), http.calls);
        assertTrue(runner.isCancelled()); assertEquals(1, runner.teardownFailures().size());
        assertTrue(Files.readString(runner.root().resolve("junit.xml")).contains("<failure"));
        assertTrue(runner.failureMessage().contains("cleanup also failed"));
    }
    @Test void firstStopDuringCleanupDoesNotInterruptIt() throws Exception {
        Http http = new Http(); http.holdCleanup = true;
        RunCancellation signal = new RunCancellation();
        var runner = runner("step api-request url=https://example.test/ok\nafter api-request url=https://example.test/cleanup", http, signal);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(runner::run);
            assertTrue(http.cleanupEntered.await(3, TimeUnit.SECONDS));
            signal.request(); http.releaseCleanup.countDown();
            assertFalse(result.get(3, TimeUnit.SECONDS));
        }
        assertTrue(runner.isCancelled()); assertTrue(runner.teardownFailures().isEmpty());
    }
    @Test void cancellingNativeHierarchyWaitRunsDeviceCleanup() throws Exception {
        CountDownLatch dumping = new CountDownLatch(1);
        List<String> calls = new CopyOnWriteArrayList<>();
        AdbClient adb = new AdbClient() {
            @Override public CommandResult run(List<String> args, long timeout) throws InterruptedException {
                calls.add(String.join(" ", args));
                if (args.contains("uiautomator") && args.contains("dump")) {
                    dumping.countDown(); Thread.sleep(60_000);
                }
                return new CommandResult(0, "", "");
            }
        };
        var flow = FlowTestParser.parseSource("flowscope 1\npackage com.example.debug\nstep wait-visible id=missing timeout=60000\nstep tap id=never\nafter force-stop\n", "native.flow");
        RunCancellation signal = new RunCancellation();
        var runner = new FlowTestRunner(adb, device, flow, temp).cancellation(signal);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(runner::run);
            assertTrue(dumping.await(3, TimeUnit.SECONDS)); signal.request();
            assertFalse(result.get(3, TimeUnit.SECONDS));
        }
        assertTrue(calls.stream().anyMatch(call -> call.contains("am force-stop com.example.debug")));
        assertFalse(calls.stream().anyMatch(call -> call.contains("input tap")));
        assertTrue(runner.isCancelled()); assertTrue(runner.teardownFailures().isEmpty());
    }
    @Test void cancellingSuiteChildOverridesContinueOnFailureAndRunsBothCleanups() throws Exception {
        checkSuite(false);
    }
    @Test void cancellingSuiteSetupSkipsAllChildrenAndRunsSuiteCleanup() throws Exception {
        checkSuite(true);
    }
    private void checkSuite(boolean setup) throws Exception {
        Path flows = temp.resolve("flows"); Files.createDirectories(flows.resolve("tests")); Files.createDirectories(flows.resolve("suites"));
        Files.writeString(flows.resolve("tests/a.flow"), "flowscope 1\npackage com.example.debug\nstep api-request url=https://example.test/wait timeout=60000\nafter api-request url=https://example.test/cleanup\n");
        Files.writeString(flows.resolve("tests/b.flow"), "flowscope 1\npackage com.example.debug\nstep api-request url=https://example.test/future\n");
        Path file = flows.resolve("suites/all.suite");
        Files.writeString(file, "flowscope-suite 1\ncontinue-on-failure true\n" + (setup ? "before api-request url=https://example.test/wait timeout=60000\n" : "") + "test tests/a.flow\ntest tests/b.flow\nafter api-request url=https://example.test/suite-cleanup\n");
        Http http = new Http(); RunCancellation signal = new RunCancellation();
        var runner = new FlowSuiteRunner(new Adb(), device, FlowSuiteParser.parse(file), file, null, temp.resolve("reports"), http, Map.of(), Map.of()).cancellation(signal);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(runner::run);
            assertTrue(http.entered.await(3, TimeUnit.SECONDS)); signal.request();
            assertFalse(result.get(3, TimeUnit.SECONDS));
        }
        assertEquals(setup ? List.of("/wait", "/suite-cleanup") : List.of("/wait", "/cleanup", "/suite-cleanup"), http.calls);
        assertTrue(RunReport.read(runner.root()).orElseThrow().isCancelled());
        String report = Files.readString(runner.root().resolve("suite-report.json"));
        assertTrue(report.contains("\"status\": \"skipped\""));
        assertTrue(Files.readString(runner.root().resolve("junit.xml")).contains("<skipped"));
    }
}
