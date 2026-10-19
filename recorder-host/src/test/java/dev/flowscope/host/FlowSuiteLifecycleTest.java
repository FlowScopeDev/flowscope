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

class FlowSuiteLifecycleTest {
    @TempDir Path temp;

    @Test
    void sharesSetupVariablesWithTestsAndAlwaysRunsCleanup() throws Exception {
        Path flows = temp.resolve("flows");
        Files.createDirectories(flows.resolve("tests"));
        Files.createDirectories(flows.resolve("suites"));
        Files.writeString(flows.resolve("tests/a.flow"), """
            flowscope 1
            package com.example.debug
            step api-request method=GET url="https://example.com/${suite.var.customerId}" expect-status=200
            """);
        Path suiteFile = flows.resolve("suites/a.suite");
        Files.writeString(suiteFile, """
            flowscope-suite 1
            before api-request method=POST url="https://example.com/fixtures" capture.customerId=data.id
            test tests/a.flow
            after api-request method=DELETE url="https://example.com/${var.customerId}" expect-status=204
            """);
        RecordingHttp http = new RecordingHttp(
            new FlowHttpClient.Response(201, "{\"data\":{\"id\":\"private-id\"}}"),
            new FlowHttpClient.Response(200, "{}"), new FlowHttpClient.Response(204, ""));
        FlowSuiteRunner runner = new FlowSuiteRunner(new FakeAdb(),
            new Device("TEST", "device", Map.of()), FlowSuiteParser.parse(suiteFile), suiteFile,
            null, temp.resolve("reports"), http, Map.of(), Map.of());

        assertTrue(runner.run());
        assertEquals(List.of("https://example.com/fixtures", "https://example.com/private-id",
            "https://example.com/private-id"), http.urls);
        String report = Files.readString(runner.root().resolve("suite-report.json"));
        assertTrue(report.contains("\"hooks\""));
        assertFalse(report.contains("private-id"));
    }

    @Test
    void setupFailureSkipsTestsButCleanupContinues() throws Exception {
        Path flows = temp.resolve("other/flows");
        Files.createDirectories(flows.resolve("tests"));
        Files.createDirectories(flows.resolve("suites"));
        Files.writeString(flows.resolve("tests/a.flow"), """
            flowscope 1
            package com.example.debug
            step force-stop
            """);
        Path suiteFile = flows.resolve("suites/a.suite");
        Files.writeString(suiteFile, """
            flowscope-suite 1
            before api-request method=GET url="https://example.com/setup" expect-status=200
            test tests/a.flow
            after api-request method=DELETE url="https://example.com/cleanup" expect-status=204
            """);
        RecordingHttp http = new RecordingHttp(new FlowHttpClient.Response(500, "{}"),
            new FlowHttpClient.Response(204, ""));
        FlowSuiteRunner runner = new FlowSuiteRunner(new FakeAdb(),
            new Device("TEST", "device", Map.of()), FlowSuiteParser.parse(suiteFile), suiteFile,
            null, temp.resolve("reports2"), http, Map.of(), Map.of());

        assertFalse(runner.run());
        assertEquals(2, http.urls.size());
        String report = Files.readString(runner.root().resolve("suite-report.json"));
        assertTrue(report.contains("\"status\": \"skipped\""));
    }

    private static final class RecordingHttp implements FlowHttpClient {
        private final List<Response> responses; private int index;
        final List<String> urls = new ArrayList<>();
        RecordingHttp(Response... responses) { this.responses = List.of(responses); }
        @Override public Response send(String method, java.net.URI uri, Map<String, String> headers,
            String body, long timeoutMillis) {
            urls.add(uri.toString()); return responses.get(index++);
        }
    }

    private static final class FakeAdb extends AdbClient {
        @Override public CommandResult run(List<String> arguments, long timeoutMillis) {
            return new CommandResult(0, "Success", "");
        }
        @Override public byte[] execOut(String serial, String... command) {
            return "<?xml version=\"1.0\"?><hierarchy rotation=\"0\"/>"
                .getBytes(StandardCharsets.UTF_8);
        }
    }
}
