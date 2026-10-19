package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * {@link FlowTestRunner.StepProgress} is the listener added for step-level progress (V2 item #11):
 * one event before a step runs and one when it finishes, never carrying an interpolated argument.
 */
class FlowTestRunnerProgressTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    @Test
    void firesRunningThenAFinalStatusForEveryExecutedStepInOrder() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            before set-value name=x value=hello
            step force-stop
            after force-stop
            """, "progress.flow");
        List<FlowTestRunner.StepProgress> events = new ArrayList<>();
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, test, temp, "progress",
            new RecordingHttp(), Map.of()).observe(events::add);

        assertTrue(runner.run());

        assertEquals(6, events.size());
        assertEquals(new FlowTestRunner.StepProgress(1, 3, "before", "set-value", 3, "running"),
            events.get(0));
        assertEquals(new FlowTestRunner.StepProgress(1, 3, "before", "set-value", 3, "passed"),
            events.get(1));
        assertEquals(new FlowTestRunner.StepProgress(2, 3, "test", "force-stop", 4, "running"),
            events.get(2));
        assertEquals(new FlowTestRunner.StepProgress(2, 3, "test", "force-stop", 4, "passed"),
            events.get(3));
        assertEquals(new FlowTestRunner.StepProgress(3, 3, "after", "force-stop", 5, "running"),
            events.get(4));
        assertEquals(new FlowTestRunner.StepProgress(3, 3, "after", "force-stop", 5, "passed"),
            events.get(5));

        // Never an interpolated argument value — only the literal action, phase and line number.
        for (FlowTestRunner.StepProgress event : events) {
            assertFalse(event.toString().contains("hello"), event.toString());
        }
    }

    @Test
    void skippedStepsReportStatusSkippedWithNoPrecedingRunningEvent() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            before api-request method=GET url="https://service.example/health" expect-status=200
            step launch
            after force-stop
            """, "skip.flow");
        List<FlowTestRunner.StepProgress> events = new ArrayList<>();
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, test, temp, "skip",
            new RecordingHttp(new FlowHttpClient.Response(503, "{}")), Map.of())
            .observe(events::add);

        assertFalse(runner.run());

        assertEquals(new FlowTestRunner.StepProgress(1, 3, "before", "api-request", 3, "running"),
            events.get(0));
        assertEquals("failed", events.get(1).status());
        // The skipped test step gets exactly one event, and it is never "running".
        long skippedTestEvents = events.stream()
            .filter(event -> event.phase().equals("test")).count();
        assertEquals(1, skippedTestEvents);
        assertEquals("skipped", events.stream().filter(event -> event.phase().equals("test"))
            .findFirst().orElseThrow().status());
        assertEquals(new FlowTestRunner.StepProgress(3, 3, "after", "force-stop", 5, "running"),
            events.get(events.size() - 2));
        assertEquals("passed", events.get(events.size() - 1).status());
    }

    private static final class RecordingHttp implements FlowHttpClient {
        private final List<Response> responses;
        private int index;
        RecordingHttp(Response... responses) { this.responses = List.of(responses); }
        @Override public Response send(String method, java.net.URI uri, Map<String, String> headers,
            String body, long timeoutMillis) {
            return responses.get(index++);
        }
    }

    private static final class FakeAdb extends AdbClient {
        @Override public CommandResult run(List<String> arguments, long timeoutMillis) {
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
