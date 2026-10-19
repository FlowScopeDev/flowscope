package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeRedactionRunnerTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());
    private static final Map<String, String> SHELL_ENV = Map.of(
        "MallocNanoZone", "0", "XPC_SERVICE_NAME", "0", "SHLVL", "1");

    @Test
    void unrelatedShellAndFileValuesLeaveSelectorsAndStatusCodesReadable() throws Exception {
        FlowTest flow = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step assert-visible id=item_1 timeout=1
            after api-request url=https://example.test/health expect-status=200
            """, "readable.flow");
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, flow, temp, "readable",
            (m, u, h, b, t) -> new FlowHttpClient.Response(501, "{}"), SHELL_ENV,
            Map.of("UNUSED", "item"), Map.of(), Map.of());

        String console = failureConsole(runner::run);
        assertTrue(console.contains("id=item_1"), console);
        assertTrue(console.contains("501"), console);
        assertReadableReports(runner.root(), List.of("report.json", "report.html", "junit.xml"));
    }

    @Test
    void bodyFileReferencesAndOverriddenSecretsStayHiddenThroughCleanup() throws Exception {
        Path flowFile = temp.resolve("secrets.flow");
        Files.writeString(temp.resolve("body.json"), "{\"token\":\"${env.BODY_TOKEN}\"}");
        Files.writeString(flowFile, """
            flowscope 1
            package com.example.debug
            before secret name=jwt source=TOKEN
            before transform name=encoded from=${secret.jwt} operation=base64-encode
            step api-request method=POST url=https://example.test/fixtures body-file=body.json \
              header.Authorization=${var.encoded}
            after api-request method=DELETE url=https://example.test/cleanup header.Authorization=${secret.jwt}
            """);
        Map<String, String> environment = Map.of("TOKEN", "env-sentinel", "BODY_TOKEN", "body-sentinel",
            "SHLVL", "1");
        Path file = secretFile("TOKEN=file-sentinel\nUNUSED=0\n");
        String encoded = java.util.Base64.getEncoder().encodeToString(
            "env-sentinel".getBytes(StandardCharsets.UTF_8));
        FlowHttpClient http = (m, u, h, b, t) -> {
            assertTrue(h.get("Authorization").equals(encoded)
                || h.get("Authorization").equals("env-sentinel"));
            throw new IOException("failed env-sentinel body-sentinel " + encoded + " item_10");
        };
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE, FlowTestParser.parse(flowFile),
            temp, "secrets", http, environment, SecretFile.load(file, environment), Map.of(), Map.of());

        String console = failureConsole(runner::run);
        assertHidden(console, "env-sentinel", "body-sentinel", encoded);
        assertTrue(console.contains("item_10"), console);
        assertHidden(runner.failureMessage(), "env-sentinel", "body-sentinel", encoded);
        for (String name : List.of("report.json", "report.html", "junit.xml")) {
            assertHidden(Files.readString(runner.root().resolve(name)),
                "env-sentinel", "body-sentinel", encoded);
        }
    }

    @Test
    void suiteHooksAndChildrenShareProtectionWhileKeepingDiagnosticsReadable() throws Exception {
        Path flows = temp.resolve("flows");
        Files.createDirectories(flows.resolve("tests"));
        Files.createDirectories(flows.resolve("suites"));
        Files.writeString(flows.resolve("tests/a.flow"), """
            flowscope 1
            package com.example.debug
            before secret name=child source=CHILD_TOKEN
            step assert-visible id=item_1 timeout=1
            after api-request url=https://example.test/cleanup
            """);
        Path suiteFile = flows.resolve("suites/a.suite");
        Files.writeString(suiteFile, """
            flowscope-suite 1
            before secret name=hook source=HOOK_TOKEN
            test tests/a.flow
            after api-request url=https://example.test/cleanup
            """);
        FlowSuiteRunner runner = new FlowSuiteRunner(new FakeAdb(), DEVICE,
            FlowSuiteParser.parse(suiteFile), suiteFile, null, temp.resolve("reports"),
            (m, u, h, b, t) -> { throw new IOException("501 hook-sentinel child-sentinel"); },
            SHELL_ENV, Map.of("HOOK_TOKEN", "hook-sentinel", "CHILD_TOKEN", "child-sentinel",
                "UNUSED", "id"));

        assertHidden(failureConsole(runner::run), "hook-sentinel", "child-sentinel");
        for (String name : List.of("suite-report.json", "suite-report.html", "junit.xml")) {
            String written = Files.readString(runner.root().resolve(name));
            assertHidden(written, "hook-sentinel", "child-sentinel");
            assertTrue(written.contains("id=item_1"), name);
            assertTrue(written.contains("501"), name);
        }
        Path child;
        try (var entries = Files.list(runner.root())) {
            child = entries.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        assertReadableReports(child, List.of("report.json", "report.html", "junit.xml"));
        for (String name : List.of("report.json", "report.html", "junit.xml")) {
            assertHidden(Files.readString(child.resolve(name)), "hook-sentinel", "child-sentinel");
        }
    }

    @Test
    void suiteSanitizesExceptionsThatEscapeChildPreflight() throws Exception {
        Path flows = temp.resolve("invalid/flows");
        Files.createDirectories(flows.resolve("tests"));
        Files.createDirectories(flows.resolve("suites"));
        Files.writeString(flows.resolve("tests/a.flow"), """
            flowscope 1
            package com.example.debug
            step api-request method=POST url=https://example.test/fixtures body-file=${env.BODY_PATH}
            """);
        Path suiteFile = flows.resolve("suites/a.suite");
        Files.writeString(suiteFile, "flowscope-suite 1\ntest tests/a.flow\n");
        FlowSuiteRunner runner = new FlowSuiteRunner(new FakeAdb(), DEVICE,
            FlowSuiteParser.parse(suiteFile), suiteFile, null, temp.resolve("invalid-reports"),
            (m, u, h, b, t) -> { throw new AssertionError("Preflight must reject the path"); },
            Map.of("BODY_PATH", "invalid\u0000sentinel"), Map.of());

        assertFalse(runner.run());
        for (String name : List.of("suite-report.json", "suite-report.html", "junit.xml")) {
            String written = Files.readString(runner.root().resolve(name));
            assertHidden(written, "sentinel");
            assertTrue(written.contains("{secret}"), name);
        }
    }

    private Path secretFile(String content) throws Exception {
        Path file = temp.resolve("local.env");
        Files.writeString(file, content);
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {}
        return file;
    }

    private static void assertReadableReports(Path root, List<String> names) throws Exception {
        for (String name : names) {
            String written = Files.readString(root.resolve(name));
            assertTrue(written.contains("id=item_1"), name);
            assertTrue(written.contains("501"), name);
        }
    }

    private static void assertHidden(String text, String... protectedValues) {
        for (String value : protectedValues) assertFalse(text.contains(value), "Leaked " + value);
    }

    private interface Run { boolean run() throws Exception; }

    private static String failureConsole(Run run) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            assertFalse(run.run());
        } finally {
            System.setErr(previous);
        }
        return bytes.toString(StandardCharsets.UTF_8);
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
