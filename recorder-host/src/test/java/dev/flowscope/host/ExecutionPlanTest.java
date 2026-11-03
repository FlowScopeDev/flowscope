package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionPlanTest {
    @TempDir Path temp;

    private Path project(String name) throws Exception {
        Path root = temp.resolve(name);
        Files.createDirectories(root.resolve("flows/tests"));
        Files.createDirectories(root.resolve("flows/suites"));
        Files.writeString(root.resolve("flowscope.json"), "{\"schemaVersion\":1,\"name\":\"Test\"}");
        Files.writeString(root.resolve("flows/tests/one.flow"), """
            flowscope 1
            package com.example.debug
            step screenshot name=one
            """);
        Files.writeString(root.resolve("flows/suites/all.suite"), """
            flowscope-suite 1
            test tests/one.flow
            """);
        return root;
    }
    private Path plan(String projects, String queues) throws Exception {
        Path path = temp.resolve("test.runplan.json");
        Files.writeString(path, "{\"version\":1,\"name\":\"Parallel regression\",\"projects\":" + projects + ",\"queues\":" + queues + "}");
        return path;
    }
    private String queue(String project, String role, String jobs) {
        return "{\"project\":\"" + project + "\",\"device\":\"" + role + "\",\"jobs\":" + jobs + "}";
    }
    private ExecutionPlan single() throws Exception {
        project("app");
        return ExecutionPlan.read(plan("{\"app\":\"app\"}", "[" + queue("app", "phone", "[\"one.flow\"]") + "]"));
    }

    @Test void loadsPortableProjectsAndFreezesSuiteChildrenWithoutDevices() throws Exception {
        Path root = project("app");
        Path file = plan("{\"app\":\"app\"}", "[" + queue("app", "phone", "[\"all.suite\"]") + "]");
        ExecutionPlan plan = ExecutionPlan.read(file);
        assertEquals(root, plan.projects().get("app").root());
        assertEquals(1, plan.queues().getFirst().jobs().getFirst().children().size());
        Files.writeString(root.resolve("flows/tests/one.flow"), "invalid after snapshot");
        assertEquals("screenshot", plan.queues().getFirst().jobs().getFirst().children().getFirst().steps().getFirst().action());
    }

    @Test void validateAndShowNeverAccessAdbOrCreateReports() throws Exception {
        project("app");
        Path file = plan("{\"app\":\"app\"}", "[" + queue("app", "phone", "[\"one.flow\"]") + "]");
        AdbClient noAdb = new AdbClient() { @Override public List<Device> devices() { fail("validation touched ADB"); return List.of(); } };
        assertEquals(0, PlanCommand.run(List.of("validate", file.toString()), noAdb));
        assertEquals(0, PlanCommand.run(List.of("show", file.toString()), noAdb));
        assertFalse(Files.exists(temp.resolve("app/reports")));
    }

    @Test void rejectsTyposVersionsNullDuplicatesAndMalformedQueues() throws Exception {
        single();
        Path file = temp.resolve("test.runplan.json");
        String valid = Files.readString(file);
        for (String bad : List.of(valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":null,\"version\":1"),
            valid.replace("\"name\":", "\"typo\":"),
            valid.replace("\"queues\":[", "\"queues\":[null,"),
            valid.replace("[\"one.flow\"]", "[]"), valid.replace("\"phone\"", "\"bad role\""))) {
            Files.writeString(file, bad);
            assertThrows(IllegalArgumentException.class, () -> ExecutionPlan.read(file), bad);
        }
    }

    @Test void rejectsUnknownProjectsDuplicateRolesAndEscapingJobs() throws Exception {
        project("app");
        for (String queues : List.of("[" + queue("unknown", "phone", "[\"one.flow\"]") + "]",
            "[" + queue("app", "phone", "[\"one.flow\"]") + "," + queue("app", "phone", "[\"one.flow\"]") + "]",
            "[" + queue("app", "phone", "[\"../one.flow\"]") + "]")) {
            Path file = plan("{\"app\":\"app\"}", queues);
            assertThrows(IllegalArgumentException.class, () -> ExecutionPlan.read(file));
        }
    }

    @Test void rejectsSymlinkedJobOutsideProject() throws Exception {
        Path root = project("app");
        Path external = temp.resolve("outside.flow");
        Files.writeString(external, Files.readString(root.resolve("flows/tests/one.flow")));
        Files.delete(root.resolve("flows/tests/one.flow"));
        try { Files.createSymbolicLink(root.resolve("flows/tests/one.flow"), external); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symlinks unavailable");
        }
        Path file = plan("{\"app\":\"app\"}", "[" + queue("app", "phone", "[\"one.flow\"]") + "]");
        assertThrows(IllegalArgumentException.class, () -> ExecutionPlan.read(file));
    }

    @Test void requiresExactBindingsAndRejectsDuplicateOrEmptyBindings() throws Exception {
        ExecutionPlan plan = single();
        assertThrows(IllegalArgumentException.class, () -> plan.bindings(List.of()));
        assertThrows(IllegalArgumentException.class, () -> plan.bindings(List.of("phone=a", "extra=b")));
        for (var bindings : List.of(List.of("phone=a", "phone=b"), List.of("phone="), List.of("phone")))
            assertThrows(IllegalArgumentException.class, () -> plan.bindings(bindings));
        assertEquals(Map.of("phone", "emulator-5554"), plan.bindings(List.of("phone=emulator-5554")));
    }

    @Test void repeatedOptionsAreOptInAndPreserveOrder() {
        CliOptions parsed = CliOptions.parse(List.of("file", "--device", "a=x", "--device", "b=y"),
            Set.of("--output"), Set.of(), Set.of("--device"));
        assertEquals(List.of("a=x", "b=y"), parsed.values("--device"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(List.of("--serial", "a", "--serial", "b"), Set.of("--serial"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(List.of("--device"), Set.of(), Set.of(), Set.of("--device")));
    }

    @Test void executesTwoProjectsOnDifferentDevicesWithReportsAndParallelAttribution() throws Exception {
        project("first"); project("second");
        var plan = ExecutionPlan.read(plan("{\"first\":\"first\",\"second\":\"second\"}", "["
            + queue("first", "left", "[\"one.flow\",\"one.flow\"]") + ","
            + queue("second", "right", "[\"all.suite\"]") + "]"));
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        FakeAdb adb = new FakeAdb(a, b);
        adb.barrier = new CountDownLatch(2);
        var result = ExecutionPlanRunner.run(plan, Map.of("left", a, "right", b), Map.of(), temp.resolve("output"), 2, false, adb);
        assertTrue(result.passed());
        Map<String, Object> report = Json.parseObject(Files.readString(result.report()));
        assertEquals(3L, report.get("passed"));
        assertEquals(0L, report.get("failed"));
        assertTrue(Files.readString(result.report()).contains(a));
        assertTrue(Files.readString(result.report()).contains(b));
        assertTrue(Files.isRegularFile(result.report().resolveSibling("execution-report.html")));
        assertTrue(Files.isRegularFile(result.report().resolveSibling("execution-report.pdf")));
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(result.report().resolveSibling("junit.xml").toFile());
        try (var paths = Files.walk(temp.resolve("first/reports"))) {
            assertEquals(2, paths.filter(p -> p.getFileName().toString().equals("report.json")).count());
        }
    }

    @Test void runsDifferentTestsInOneProjectOnTwoDevices() throws Exception {
        Path app = project("app");
        Files.copy(app.resolve("flows/tests/one.flow"), app.resolve("flows/tests/two.flow"));
        var plan = ExecutionPlan.read(plan("{\"app\":\"app\"}", "[" + queue("app", "left", "[\"one.flow\"]") + ","
            + queue("app", "right", "[\"two.flow\"]") + "]"));
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var result = ExecutionPlanRunner.run(plan, Map.of("left", a, "right", b), Map.of(), temp.resolve("output"), 2, false, new FakeAdb(a, b));
        assertTrue(result.passed());
        assertThrows(IllegalArgumentException.class, () -> plan.bindings(List.of("left=" + a, "right=" + a)));
    }

    @Test void missingOrBusyDevicePreventsEveryQueueFromStarting() throws Exception {
        ExecutionPlan plan = single();
        String serial = UUID.randomUUID().toString();
        FakeAdb adb = new FakeAdb(serial);
        assertThrows(IllegalArgumentException.class, () -> ExecutionPlanRunner.run(plan, Map.of("phone", "missing"), Map.of(), temp.resolve("output"), 1, false, adb));
        try (var lease = DeviceLease.acquire(serial)) {
            assertThrows(IllegalArgumentException.class, () -> ExecutionPlanRunner.run(plan, Map.of("phone", serial), Map.of(), temp.resolve("output"), 1, false, adb));
        }
        assertEquals(0, adb.captures.get());
    }

    @Test void cliRejectsRunOnlyFlagsDuringValidateAndInvalidParallelism() throws Exception {
        single();
        Path file = temp.resolve("test.runplan.json");
        FakeAdb adb = new FakeAdb("unused");
        assertThrows(IllegalArgumentException.class, () -> PlanCommand.run(List.of("validate", file.toString(), "--device", "phone=x"), adb));
        assertThrows(IllegalArgumentException.class, () -> PlanCommand.run(List.of("run", file.toString(), "--device", "phone=x", "--max-parallel", "0"), adb));
        assertThrows(IllegalArgumentException.class, () -> PlanCommand.run(List.of("show", file.toString(), "extra"), adb));
    }

    @Test void failureStopsOnlyItsOwnQueueAndContinuePolicyCanRunLaterJobs() throws Exception {
        Path root = project("app");
        Files.writeString(root.resolve("flows/tests/bad.flow"), "flowscope 1\npackage com.example.debug\nstep clear-app-data\n");
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        String left = queue("app", "left", "[\"bad.flow\",\"one.flow\"]");
        String right = queue("app", "right", "[\"one.flow\"]");
        var plan = ExecutionPlan.read(plan("{\"app\":\"app\"}", "[" + left + "," + right + "]"));
        FakeAdb adb = new FakeAdb(a, b);
        adb.failClear = true;
        var outcome = ExecutionPlanRunner.run(plan, Map.of("left", a, "right", b), Map.of(), temp.resolve("output"), 2, false, adb);
        assertFalse(outcome.passed());
        var report = Json.parseObject(Files.readString(outcome.report()));
        assertEquals(1L, report.get("failed"));
        assertEquals(1L, report.get("skipped"));
        assertEquals(1L, report.get("passed"));
        plan = ExecutionPlan.read(plan("{\"app\":\"app\"}", "[" + left.replace("\"jobs\":", "\"continueOnFailure\":true,\"jobs\":") + "," + right + "]"));
        outcome = ExecutionPlanRunner.run(plan, Map.of("left", a, "right", b), Map.of(), temp.resolve("output"), 2, false, adb);
        report = Json.parseObject(Files.readString(outcome.report()));
        assertEquals(0L, report.get("skipped"));
        assertEquals(2L, report.get("passed"));
    }

    @Test void projectSecretsAndOverridesAreIsolatedAndNeverPersisted() throws Exception {
        Path first = project("first"), second = project("second");
        Files.writeString(first.resolve("flows/tests/one.flow"), "flowscope 1\npackage com.example.debug\nrequire secret.ONLY_FIRST\nstep set-value name=value value=\"${secret.ONLY_FIRST}\"\n");
        Files.writeString(second.resolve("flows/tests/one.flow"), "flowscope 1\npackage com.example.debug\nrequire secret.ONLY_SECOND\nstep set-value name=value value=\"${secret.ONLY_SECOND}\"\n");
        Path override = temp.resolve("override.env");
        secretFile(override, "ONLY_FIRST=private-first-12345\n");
        secretFile(first.resolve("flows/.secrets"), "WRONG_DEFAULT=private-wrong-12345\n");
        secretFile(second.resolve("flows/.secrets"), "ONLY_SECOND=private-second-12345\n");
        var plan = ExecutionPlan.read(plan("{\"first\":\"first\",\"second\":\"second\"}", "["
            + queue("first", "left", "[\"one.flow\"]") + "," + queue("second", "right", "[\"one.flow\"]") + "]"));
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        FakeAdb adb = new FakeAdb(a, b);
        assertThrows(IllegalArgumentException.class, () -> ExecutionPlanRunner.run(plan, Map.of("left", a, "right", b), Map.of(), temp.resolve("output"), 2, false, adb));
        assertEquals(0, adb.captures.get());
        assertFalse(Files.exists(first.resolve("reports")));
        var result = ExecutionPlanRunner.run(plan, Map.of("left", a, "right", b), Map.of("first", override), temp.resolve("output"), 2, false, adb);
        assertTrue(result.passed());
        for (Path reportRoot : List.of(first.resolve("reports"), second.resolve("reports"), temp.resolve("output"))) {
            try (var paths = Files.walk(reportRoot)) {
                for (Path file : paths.filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".html") || p.toString().endsWith(".xml")).toList()) {
                    String content = Files.readString(file);
                    assertFalse(content.contains("private-first-12345"));
                    assertFalse(content.contains("private-second-12345"));
                }
            }
        }
    }

    @Test void executesFrozenSuiteChildrenAfterAuthoredFileChanges() throws Exception {
        Path root = project("app");
        var plan = ExecutionPlan.read(plan("{\"app\":\"app\"}", "[" + queue("app", "phone", "[\"all.suite\"]") + "]"));
        Files.writeString(root.resolve("flows/tests/one.flow"), "invalid edited source");
        String serial = UUID.randomUUID().toString();
        assertTrue(ExecutionPlanRunner.run(plan, Map.of("phone", serial), Map.of(), temp.resolve("output"), 1, false, new FakeAdb(serial)).passed());
    }

    @Test void disconnectedQueueStopsEvenWhenContinueOnFailureIsEnabled() throws Exception {
        project("app");
        String queue = queue("app", "phone", "[\"one.flow\",\"one.flow\"]")
            .replace("\"jobs\":", "\"continueOnFailure\":true,\"jobs\":");
        var plan = ExecutionPlan.read(plan("{\"app\":\"app\"}", "[" + queue + "]"));
        String serial = UUID.randomUUID().toString();
        FakeAdb adb = new FakeAdb(serial);
        adb.disconnectAfterPreflight = true;
        var result = ExecutionPlanRunner.run(plan, Map.of("phone", serial), Map.of(), temp.resolve("output"), 1, false, adb);
        var report = Json.parseObject(Files.readString(result.report()));
        assertFalse(result.passed());
        assertEquals(1L, report.get("failed"));
        assertEquals(1L, report.get("skipped"));
        assertTrue(Files.readString(result.report()).contains("device-disconnected"));
        assertEquals(0, adb.captures.get());
    }

    @Test void cliRunReturnsExistingSuccessAndFailureCodes() throws Exception {
        Path app = project("app");
        Path file = plan("{\"app\":\"app\"}", "[" + queue("app", "phone", "[\"one.flow\"]") + "]");
        String serial = UUID.randomUUID().toString();
        FakeAdb adb = new FakeAdb(serial);
        List<String> args = List.of("run", file.toString(), "--device", "phone=" + serial,
            "--output", temp.resolve("output").toString(), "--keep-hierarchy");
        assertEquals(0, PlanCommand.run(args, adb));
        Files.writeString(app.resolve("flows/tests/one.flow"), "flowscope 1\npackage com.example.debug\nstep clear-app-data\n");
        adb.failClear = true;
        assertEquals(1, PlanCommand.run(args, adb));
    }

    @Test void sourcesInBodyFixturesAreCheckedBeforeAnyQueueRuns() throws Exception {
        Path root = project("app");
        Files.writeString(root.resolve("flows/tests/payload.json"), "{\"token\":\"${secret.MISSING_FOR_PLAN_BODY}\"}");
        Files.writeString(root.resolve("flows/tests/request.flow"), "flowscope 1\npackage com.example.debug\nstep api-request method=POST url=https://example.com body-file=payload.json expect-status=200\n");
        var plan = ExecutionPlan.read(plan("{\"app\":\"app\"}", "[" + queue("app", "left", "[\"one.flow\"]") + ","
            + queue("app", "right", "[\"request.flow\"]") + "]"));
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        FakeAdb adb = new FakeAdb(a, b);
        assertThrows(IllegalArgumentException.class, () -> ExecutionPlanRunner.run(plan, Map.of("left", a, "right", b), Map.of(), temp.resolve("output"), 2, false, adb));
        assertEquals(0, adb.captures.get());
    }

    @Test void savedPlanRoundTripsAndInvalidSavePreservesExistingFile() throws Exception {
        Path root = project("app");
        Path file = temp.resolve("saved.runplan.json");
        ExecutionPlan.save(file, "Saved", Map.of("app", root), List.of(
            new ExecutionPlan.Assignment("app", "phone", List.of("one.flow"), false)), 1);
        String original = Files.readString(file);
        assertEquals("one.flow", ExecutionPlan.read(file).queues().getFirst().jobs().getFirst().reference());
        assertThrows(IllegalArgumentException.class, () -> ExecutionPlan.save(file, "Invalid", Map.of("app", root), List.of(
            new ExecutionPlan.Assignment("app", "phone", List.of("missing.flow"), false)), 1));
        assertEquals(original, Files.readString(file));
    }

    @Test void stoppingQueueKeepsOtherQueuesRunningAndPreservesDuplicateJobOrdinals() throws Exception {
        project("app");
        var plan = ExecutionPlan.read(plan("{\"app\":\"app\"}", "[" + queue("app", "left", "[\"one.flow\",\"one.flow\"]") + ","
            + queue("app", "right", "[\"one.flow\"]") + "]"));
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var cancellation = new ExecutionCancellation();
        FakeAdb adb = new FakeAdb(a, b);
        adb.onCapture = serial -> { if (serial.equals(a)) cancellation.request("left"); };
        var events = new java.util.concurrent.CopyOnWriteArrayList<ExecutionPlanRunner.JobProgress>();
        var result = ExecutionPlanRunner.run(plan, Map.of("left", a, "right", b), Map.of(), temp.resolve("output"), 2, false, adb, cancellation, events::add);
        assertFalse(result.passed());
        assertTrue(events.stream().anyMatch(e -> e.role().equals("left") && e.ordinal() == 2 && e.status().equals("skipped")));
        assertTrue(events.stream().anyMatch(e -> e.role().equals("right") && e.status().equals("passed")));
        assertFalse(cancellation.requestAll(), "Completed execution cannot be cancelled again");
        var report = Json.parseObject(Files.readString(result.report()));
        assertEquals(1L, report.get("cancelled"));
        assertEquals(1L, report.get("skipped"));
        assertEquals(1L, report.get("passed"));
    }

    @Test void cancellationWithCleanupFailureIsStillReportedAsFailure() throws Exception {
        Path root = project("app");
        Files.writeString(root.resolve("flows/tests/one.flow"), "flowscope 1\npackage com.example.debug\nstep screenshot name=one\nafter clear-app-data\n");
        var plan = ExecutionPlan.read(plan("{\"app\":\"app\"}", "[" + queue("app", "phone", "[\"one.flow\"]") + "]"));
        String serial = UUID.randomUUID().toString();
        var cancellation = new ExecutionCancellation();
        FakeAdb adb = new FakeAdb(serial);
        adb.failClear = true;
        adb.onCapture = ignored -> cancellation.requestAll();
        var result = ExecutionPlanRunner.run(plan, Map.of("phone", serial), Map.of(), temp.resolve("output"), 1, false, adb, cancellation, progress -> {});
        var report = Json.parseObject(Files.readString(result.report()));
        assertEquals(1L, report.get("failed"));
        assertTrue(Files.readString(result.report().resolveSibling("junit.xml")).contains("<failure"));
    }

    @Test void simultaneousRecordingRootsAreDistinctForTheSamePackage() throws Exception {
        FakeAdb adb = new FakeAdb("one", "two");
        try (var first = new SessionRecorder(adb, new Device("one", "device", Map.of()), "com.example", temp);
             var second = new SessionRecorder(adb, new Device("two", "device", Map.of()), "com.example", temp)) {
            assertNotEquals(first.root(), second.root());
        }
    }

    private static void secretFile(Path path, String content) throws Exception {
        Files.writeString(path, content);
        try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException windows) { /* POSIX mode does not apply. */ }
    }

    static final class FakeAdb extends AdbClient {
        final List<Device> connected;
        final AtomicInteger captures = new AtomicInteger();
        CountDownLatch barrier;
        java.util.function.Consumer<String> onCapture;
        boolean failClear;
        boolean disconnectAfterPreflight;
        final AtomicInteger deviceQueries = new AtomicInteger();
        FakeAdb(String... serials) { connected = java.util.Arrays.stream(serials).map(s -> new Device(s, "device", Map.<String,String>of())).toList(); }
        @Override public List<Device> devices() {
            if (deviceQueries.incrementAndGet() > 1 && disconnectAfterPreflight) return List.of();
            return connected;
        }
        @Override public CommandResult run(List<String> arguments, long timeout) {
            if (failClear && arguments.contains("clear")) return new CommandResult(1, "", "fixture clear failed");
            return new CommandResult(0, "Success", "");
        }
        @Override public byte[] execOut(String serial, String... command) throws IOException, InterruptedException {
            if (List.of(command).contains("screencap")) {
                int capture = captures.incrementAndGet();
                if (onCapture != null) onCapture.accept(serial);
                if (barrier != null && capture <= 2) {
                    barrier.countDown();
                    if (!barrier.await(5, TimeUnit.SECONDS)) throw new IOException("device queues did not run concurrently");
                }
            }
            return "<?xml version=\"1.0\"?><hierarchy rotation=\"0\"/>".getBytes(StandardCharsets.UTF_8);
        }
    }
}
