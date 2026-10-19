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

class FlowLifecycleRunnerTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    @Test
    void capturesApiValueRunsCleanupAndWritesAllReportFormats() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            name "API lifecycle"
            package com.example.debug
            before api-request method=POST url="${env.BASE_URL}/fixtures" \
              body='{"kind":"smoke"}' expect-status=201 capture.id=data.id
            step force-stop
            after api-request method=DELETE url="${env.BASE_URL}/fixtures/${var.id}" \
              expect-status=204 if-var=id
            """, "api.flow");
        RecordingHttp http = new RecordingHttp(
            new FlowHttpClient.Response(201, "{\"data\":{\"id\":\"customer-7\"}}"),
            new FlowHttpClient.Response(204, ""));
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, test, temp, "run",
            http, Map.of("BASE_URL", "https://service.example"));

        assertTrue(runner.run());
        assertEquals("https://service.example/fixtures/customer-7", http.urls.get(1));
        String report = Files.readString(runner.root().resolve("report.json"));
        assertTrue(report.contains("\"schemaVersion\": 2"));
        assertTrue(report.contains("\"phase\": \"before\""));
        assertTrue(report.contains("\"captured\": \"id\""));
        assertFalse(report.contains("customer-7"));
        assertTrue(Files.isRegularFile(runner.root().resolve("report.html")));
        assertTrue(Files.isRegularFile(runner.root().resolve("junit.xml")));
    }

    @Test
    void beforeFailureSkipsMainButAlwaysRunsAllAfterSteps() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            before api-request method=GET url="https://service.example/health" expect-status=200
            step launch
            after force-stop
            after revoke-permission permission=android.permission.CAMERA
            """, "failure.flow");
        FakeAdb adb = new FakeAdb();
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, test, temp, "failed",
            new RecordingHttp(new FlowHttpClient.Response(503, "{}")), Map.of());

        assertFalse(runner.run());
        String report = Files.readString(runner.root().resolve("report.json"));
        assertTrue(report.contains("\"status\": \"skipped\""));
        assertTrue(adb.commands.stream().anyMatch(command -> command.contains("force-stop")));
        assertTrue(adb.commands.stream().anyMatch(command -> command.contains("revoke")));
        // The verdict names the phase: setup broke, the body never ran, cleanup was clean.
        assertTrue(runner.failureMessage().startsWith("setup failed · "), runner.failureMessage());
        assertTrue(runner.teardownFailures().isEmpty());
    }

    @Test
    void cleanupOnlyFailureIsNamedAsCleanupAndStillFailsTheRun() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step force-stop
            after api-request method=DELETE url="https://service.example/fixtures/7"               expect-status=204
            """, "cleanup.flow");
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, test, temp, "cleanup",
            new RecordingHttp(new FlowHttpClient.Response(500, "{}")), Map.of());

        // A dirty teardown leaves a leaked fixture behind, so the run still fails.
        assertFalse(runner.run());
        assertNull(runner.primaryFailure());
        assertEquals(1, runner.teardownFailures().size());
        assertTrue(runner.failureMessage().startsWith("cleanup failed · "), runner.failureMessage());

        String report = Files.readString(runner.root().resolve("report.json"));
        assertTrue(report.contains("\"primaryFailure\": null"), report);
        assertTrue(report.contains("\"teardownFailures\": [\"api-request:"), report);

        String junit = Files.readString(runner.root().resolve("junit.xml"));
        assertTrue(junit.contains("message=\"cleanup failed"), junit);
        assertTrue(junit.contains("cleanup: api-request:"), junit);
        // Nothing may claim the test body failed: it passed.
        assertFalse(junit.contains("test: "), junit);

        String html = Files.readString(runner.root().resolve("report.html"));
        assertTrue(html.contains("Cleanup failures"), html);
    }

    @Test
    void aTestFailureKeepsItsCleanupFailuresInJunitRatherThanDroppingThem() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step api-request method=GET url="https://service.example/thing" expect-status=200
            after api-request method=DELETE url="https://service.example/fixtures/7"               expect-status=204
            """, "both.flow");
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, test, temp, "both",
            new RecordingHttp(new FlowHttpClient.Response(418, "{}"),
                new FlowHttpClient.Response(500, "{}")), Map.of());

        assertFalse(runner.run());
        assertNotNull(runner.primaryFailure());
        assertEquals(1, runner.teardownFailures().size());

        // The regression this change fixes: the single message attribute used to be the only place
        // a failure could land, so the cleanup error vanished from the one artifact CI reads.
        String junit = Files.readString(runner.root().resolve("junit.xml"));
        assertTrue(junit.contains("cleanup also failed (1)"), junit);
        assertTrue(junit.contains("test: api-request:"), junit);
        assertTrue(junit.contains("cleanup: api-request:"), junit);
    }

    @Test
    void aFailedCleanupNeverLeaksACapturedValueIntoAnyReport() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            before api-request method=POST url="https://service.example/fixtures"               expect-status=201 capture.id=data.id
            step force-stop
            after api-request method=DELETE url="https://service.example/fixtures/${var.id}"               expect-status=204 if-var=id
            """, "redact.flow");
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, test, temp, "redact",
            new RecordingHttp(new FlowHttpClient.Response(201, "{\"data\":{\"id\":\"customer-7\"}}"),
                new FlowHttpClient.Response(500, "{}")), Map.of());

        assertFalse(runner.run());
        assertEquals(1, runner.teardownFailures().size());
        assertFalse(runner.failureMessage().contains("customer-7"), runner.failureMessage());
        for (String name : List.of("report.json", "report.html", "junit.xml")) {
            String written = Files.readString(runner.root().resolve(name));
            assertFalse(written.contains("customer-7"), name + " leaked a captured value");
        }
    }

    @Test
    void missingEnvironmentFailsBeforeAdbOrHttpMutation() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            before api-request method=GET url="${env.MISSING}/health"
            step force-stop
            """, "missing.flow");
        FakeAdb adb = new FakeAdb(); RecordingHttp http = new RecordingHttp();
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, test, temp, "missing", http, Map.of());

        assertThrows(IllegalArgumentException.class, runner::run);
        assertTrue(adb.commands.isEmpty());
        assertTrue(http.urls.isEmpty());
    }

    @Test
    void deviceLifecycleActionsNeverTargetAnotherPackageOrGlobalPermissionReset() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            before clear-app-data
            before grant-permission permission=android.permission.CAMERA
            before reset-app-permissions
            step force-stop
            after revoke-permission permission=android.permission.CAMERA
            """, "device.flow");
        FakeAdb adb = new FakeAdb("""
              runtime permissions:
                android.permission.CAMERA: granted=true, flags=[]
                android.permission.POST_NOTIFICATIONS: granted=false, flags=[]
            """);
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, test, temp, "device",
            new RecordingHttp(), Map.of());

        assertTrue(runner.run());
        assertTrue(adb.commands.stream().flatMap(List::stream)
            .noneMatch(argument -> argument.equals("reset-permissions")));
        assertTrue(adb.commands.stream().filter(command -> command.contains("pm"))
            .allMatch(command -> command.contains("com.example.debug")));
        assertEquals(2, adb.commands.stream().filter(command -> command.contains("revoke")).count());
    }

    private static final class RecordingHttp implements FlowHttpClient {
        private final List<Response> responses;
        private int index;
        final List<String> urls = new ArrayList<>();
        RecordingHttp(Response... responses) { this.responses = List.of(responses); }
        @Override public Response send(String method, java.net.URI uri, Map<String, String> headers,
            String body, long timeoutMillis) {
            urls.add(uri.toString());
            return responses.get(index++);
        }
    }

    private static final class FakeAdb extends AdbClient {
        final List<List<String>> commands = new ArrayList<>();
        private final String dumpsys;
        FakeAdb() { this(""); }
        FakeAdb(String dumpsys) { this.dumpsys = dumpsys; }
        @Override public CommandResult run(List<String> arguments, long timeoutMillis) {
            commands.add(List.copyOf(arguments));
            if (arguments.contains("dumpsys")) return new CommandResult(0, dumpsys, "");
            if (arguments.contains("resolve-activity")) return new CommandResult(0,
                "com.example.debug/.MainActivity\n", "");
            if (arguments.contains("uiautomator")) return new CommandResult(0, "UI hierchary dumped", "");
            return new CommandResult(0, "Success", "");
        }
        @Override public byte[] execOut(String serial, String... command) {
            if (List.of(command).contains("cat")) {
                return "<?xml version=\"1.0\"?><hierarchy rotation=\"0\"/>"
                    .getBytes(StandardCharsets.UTF_8);
            }
            return new byte[0];
        }
    }
}
