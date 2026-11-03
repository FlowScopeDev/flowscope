package dev.flowscope.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/** Resolves all projects and runners before invoking the shared device coordinator. */
public final class ExecutionPlanRunner {
    private ExecutionPlanRunner() {}
    public record Outcome(boolean passed, Path report) {}
    public record JobProgress(String project, String role, int ordinal, String reference,
                              String status, Path reportDirectory) {}
    record JobResult(String project, String projectRoot, String role, String serial, String reference, String kind,
                     String status, String failureKind, String report, List<String> modes) {}
    private static final class Prepared {
        final ExecutionPlan.Queue queue;
        final ExecutionPlan.Job job;
        final String serial;
        final Path projectRoot;
        final Callable<Boolean> work;
        final Path report;
        volatile String status = "pending";
        volatile String failureKind;
        Prepared(ExecutionPlan.Queue queue, ExecutionPlan.Job job, String serial,
                 Callable<Boolean> work, Path report, Path projectRoot) {
            this.queue = queue; this.job = job; this.serial = serial;
            this.work = work; this.report = report; this.projectRoot = projectRoot;
        }
        JobResult result() {
            List<FlowTest> tests = job.test() == null ? job.children() : List.of(job.test());
            return new JobResult(queue.project(), projectRoot.toString(), queue.device(), serial, job.reference(),
                job.test() == null ? "suite" : "test", status, failureKind,
                Files.isRegularFile(report) ? report.toString() : null,
                tests.stream().map(test -> test.mode().cliName()).distinct().toList());
        }
    }

    private static boolean hasFailure(Path report) {
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(report));
            if (root.get("primaryFailure") != null) return true;
            if (root.get("teardownFailures") instanceof List<?> cleanup && !cleanup.isEmpty()) return true;
            for (String field : List.of("steps", "hooks", "tests")) {
                if (root.get(field) instanceof List<?> rows) for (Object row : rows) {
                    if (row instanceof Map<?, ?> entry && "failed".equals(entry.get("status"))) return true;
                }
            }
            return false;
        } catch (Exception unavailable) { return false; }
    }

    private static void publish(java.util.function.Consumer<JobProgress> observer, JobProgress progress) {
        try { observer.accept(progress); } catch (RuntimeException ignored) { /* Presentation cannot change a verdict. */ }
    }

    private static boolean connected(AdbClient adb, String serial) {
        try { return adb.devices().stream().anyMatch(device -> device.serial().equals(serial) && device.state().equals("device")); }
        catch (Exception unavailable) { return false; }
    }

    private static void checkSources(List<FlowTest.Requirement> requirements, List<FlowTest.Step> steps,
                                     Map<String, String> sources, Path sourceDirectory) throws java.io.IOException {
        var values = new RuntimeValues(System.getenv(), sources, Map.of(), "preflight", "preflight",
            Instant.now(), UUID.randomUUID(), Map.of());
        for (var requirement : requirements) values.require(requirement);
        for (var step : steps) if (step.action().equals("secret"))
            values.bindSecret(step.arguments().get("name"), step.arguments().get("source"));
        for (var step : steps) {
            for (String argument : step.arguments().values())
                values.preflightReferences(argument.replaceAll("\\$\\{suite\\.var\\.[^}]+}", "deferred"));
            String body = step.arguments().get("body-file");
            if (body != null && !body.contains("${")) {
                Path file = FlowFiles.resolve(sourceDirectory, body, "body-file");
                values.preflightReferences(Files.readString(file).replaceAll("\\$\\{suite\\.var\\.[^}]+}", "deferred"));
            }
        }
    }

    public static Outcome run(ExecutionPlan plan, Map<String, String> bindings,
                              Map<String, Path> secretsOverrides, Path output,
                              int parallel, boolean keepHierarchy, AdbClient adb) throws Exception {
        return run(plan, bindings, secretsOverrides, output, parallel, keepHierarchy, adb,
            new ExecutionCancellation(), progress -> {});
    }

    public static Outcome run(ExecutionPlan plan, Map<String, String> bindings,
                              Map<String, Path> secretsOverrides, Path output,
                              int parallel, boolean keepHierarchy, AdbClient adb,
                              ExecutionCancellation cancellation,
                              java.util.function.Consumer<JobProgress> observer) throws Exception {
        try {
            return runOwned(plan, bindings, secretsOverrides, output, parallel, keepHierarchy, adb, cancellation, observer);
        } finally { cancellation.finish(); }
    }

    private static Outcome runOwned(ExecutionPlan plan, Map<String, String> bindings,
                              Map<String, Path> secretsOverrides, Path output,
                              int parallel, boolean keepHierarchy, AdbClient adb,
                              ExecutionCancellation cancellation,
                              java.util.function.Consumer<JobProgress> observer) throws Exception {
        // Public callers get the same binding validation as the CLI.
        bindings = plan.bindings(bindings.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        for (String project : secretsOverrides.keySet()) if (!plan.projects().containsKey(project))
            throw new IllegalArgumentException("Unknown secrets project: " + project);
        if (parallel < 1) throw new IllegalArgumentException("Parallelism must be positive");
        Map<String, Device> devices = new LinkedHashMap<>();
        for (Device device : adb.devices()) if (device.state().equals("device")) devices.put(device.serial(), device);
        for (String serial : bindings.values()) if (!devices.containsKey(serial))
            throw new IllegalArgumentException("Connected authorized device not found: " + serial);
        Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
        for (var project : plan.projects().entrySet()) {
            Path source = ProjectSecrets.resolve(project.getValue(), secretsOverrides.get(project.getKey()));
            secrets.put(project.getKey(), SecretFile.load(source, System.getenv()));
        }
        // Required sources in every queue are checked before any device mutation. Suite variable
        // values are produced by setup; only that namespace is deferred until the suite runs.
        for (ExecutionPlan.Queue queue : plan.queues()) for (ExecutionPlan.Job job : queue.jobs()) {
            if (job.test() != null) checkSources(job.test().requirements(), job.test().steps(), secrets.get(queue.project()), job.test().sourceDirectory());
            else {
                checkSources(job.suite().requirements(), job.suite().hooks(), secrets.get(queue.project()), job.file().getParent());
                for (FlowTest child : job.children()) checkSources(child.requirements(), child.steps(), secrets.get(queue.project()), child.sourceDirectory());
            }
        }
        String id = RunDirectories.stamp() + "-" + UUID.randomUUID();
        Path root = (output == null ? Path.of(System.getProperty("user.home"), ".flowscope", "executions") : output)
            .toAbsolutePath().normalize().resolve(id);
        var prepared = new ArrayList<Prepared>();
        var queues = new ArrayList<ExecutionCoordinator.Queue>();
        for (ExecutionPlan.Queue queue : plan.queues()) {
            FlowProject project = plan.projects().get(queue.project());
            String serial = bindings.get(queue.device());
            Device device = devices.get(serial);
            var jobs = new ArrayList<Callable<Boolean>>();
            boolean[] disconnected = {false};
            for (int i = 0; i < queue.jobs().size(); i++) {
                ExecutionPlan.Job job = queue.jobs().get(i);
                Path jobOutput = project.reportsDir().resolve(id).resolve(queue.device()).resolve(String.format("%03d", i + 1));
                Map<String, String> sources = secrets.get(queue.project());
                final int ordinal = i + 1;
                RunCancellation signal = new RunCancellation();
                cancellation.register(queue.device(), signal);
                Callable<Boolean> work;
                Path report;
                if (job.test() != null) {
                    var runner = new FlowTestRunner(adb, device, job.test(), jobOutput, "test",
                        FlowHttpClient.platform(), System.getenv(), sources, Map.of(), Map.of()).cancellation(signal).keepHierarchy(keepHierarchy);
                    work = runner::run;
                    report = runner.root().resolve("report.json");
                } else {
                    var runner = new FlowSuiteRunner(adb, device, job.suite(), job.file(), project, jobOutput,
                        FlowHttpClient.platform(), System.getenv(), sources, job.children()).cancellation(signal).keepHierarchy(keepHierarchy);
                    work = runner::run;
                    report = runner.root().resolve("suite-report.json");
                }
                var item = new Prepared(queue, job, serial, work, report, project.root());
                prepared.add(item);
                jobs.add(() -> RunOutput.attributed("[" + queue.project() + "/" + queue.device() + "]", () -> {
                    if (disconnected[0] || cancellation.isRequested(queue.device())) {
                        item.status = "skipped";
                        publish(observer, new JobProgress(queue.project(), queue.device(), ordinal, job.reference(), item.status, report.getParent()));
                        return false;
                    }
                    item.status = "running";
                    publish(observer, new JobProgress(queue.project(), queue.device(), ordinal, job.reference(), item.status, report.getParent()));
                    RunOutput.out("RUN %s%n", job.reference());
                    try {
                        if (!connected(adb, serial)) {
                            disconnected[0] = true;
                            item.status = "failed";
                            item.failureKind = "device-disconnected";
                            return false;
                        }
                        boolean passed = item.work.call();
                        item.status = signal.isRequested() && !hasFailure(report) ? "cancelled" : passed ? "passed" : "failed";
                        if (!passed) {
                            disconnected[0] = !connected(adb, serial);
                            item.failureKind = disconnected[0] ? "device-disconnected" : "test-or-cleanup";
                        }
                        if (item.status.equals("cancelled")) item.failureKind = null;
                        return passed;
                    } catch (Exception failure) {
                        // Arbitrary preflight/I/O exceptions may include interpolated values. Do not
                        // send them to aggregate reports or the process-level CLI error handler.
                        item.status = "failed";
                        disconnected[0] = !connected(adb, serial);
                        item.failureKind = disconnected[0] ? "device-disconnected" : "infrastructure";
                        RunOutput.err("FAIL %s: runner could not complete; inspect child artifacts%n", job.reference());
                        return false;
                    } finally {
                        publish(observer, new JobProgress(queue.project(), queue.device(), ordinal, job.reference(), item.status, report.getParent()));
                        RunOutput.out("DONE %s %s%n", job.reference(), item.status);
                    }
                }));
            }
            queues.add(new ExecutionCoordinator.Queue(queue.project(), serial, jobs, queue.continueOnFailure()));
        }
        Instant started = Instant.now();
        try {
            var results = ExecutionCoordinator.run(queues, parallel);
            int at = 0;
            for (var queue : results) {
              int ordinal = 0;
              for (var status : queue.jobs()) {
                ordinal++;
                Prepared item = prepared.get(at++);
                if (status == ExecutionCoordinator.Status.SKIPPED) {
                    item.status = "skipped";
                    publish(observer, new JobProgress(item.queue.project(), item.queue.device(),
                        ordinal, item.job.reference(), item.status, item.report.getParent()));
                }
              }
            }
        } catch (IllegalStateException busy) {
            throw new IllegalArgumentException("One or more assigned devices are busy in another FlowScope operation");
        }
        Files.createDirectories(root);
        List<JobResult> results = prepared.stream().map(Prepared::result).toList();
        boolean passed = results.stream().allMatch(job -> job.status().equals("passed"));
        ExecutionPlanReport.write(root, plan.name(), id, started, Instant.now(), results, passed);
        return new Outcome(passed, root.resolve("execution-report.json"));
    }
}
