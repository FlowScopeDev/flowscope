package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * `progress.jsonl`: one JSON object per line, appended while a run is in flight so another
 * process — the IDE watching a CLI run — can show it live. Removed once the run's report is
 * written, so a finished run directory looks exactly as it did before this file existed.
 *
 * <p>Lines carry only what {@link FlowTestRunner.StepProgress} and
 * {@link FlowSuiteRunner.Progress} already carry: literal actions, phases, line numbers, and
 * suite references. Never an argument value, so the file is safe to write before any redaction.
 *
 * <p>Writing is best effort. A progress line that cannot be written must never fail the run.
 */
public final class RunProgress {
    public static final String FILE = "progress.jsonl";
    static final int SCHEMA_VERSION = 1;

    private final Path file;
    private boolean closed;

    private RunProgress(Path file) {
        this.file = file;
    }

    /** Starts the file in {@code runDirectory}, recording this process so a reader can tell a crash. */
    static RunProgress start(Path runDirectory, String kind, String name, int total) {
        RunProgress progress = new RunProgress(runDirectory.resolve(FILE));
        ProcessHandle self = ProcessHandle.current();
        String processStarted = self.info().startInstant().map(Instant::toString).orElse(null);
        progress.append("{\"event\":\"start\",\"schemaVersion\":" + SCHEMA_VERSION
            + ",\"kind\":" + Json.quote(kind) + ",\"name\":" + Json.quote(name) + ",\"total\":" + total
            + ",\"pid\":" + self.pid() + ",\"processStartedAt\":" + Json.quote(processStarted)
            + ",\"startedAt\":" + Json.quote(Instant.now().toString()) + "}");
        return progress;
    }

    void step(FlowTestRunner.StepProgress step) {
        append("{\"event\":\"step\",\"number\":" + step.number() + ",\"total\":" + step.total()
            + ",\"phase\":" + Json.quote(step.phase()) + ",\"action\":" + Json.quote(step.action())
            + ",\"line\":" + step.line() + ",\"status\":" + Json.quote(step.status()) + "}");
    }

    void test(FlowSuiteRunner.Progress test) {
        append("{\"event\":\"test\",\"number\":" + test.number() + ",\"total\":" + test.total()
            + ",\"reference\":" + Json.quote(test.reference())
            + ",\"status\":" + Json.quote(test.status()) + "}");
    }

    /** The report is on disk, so the run is described by it and this file goes. */
    void finish() {
        if (closed) return;
        closed = true;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover file is harmless: a reader prefers the report.
        }
    }

    /** The run ended without a report — an exception, not a test failure. A no-op after finish(). */
    void interrupt() {
        if (closed) return;
        append("{\"event\":\"end\",\"status\":\"interrupted\"}");
        closed = true;
    }

    private void append(String line) {
        if (closed) return;
        try {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // Progress is a courtesy to an observer; the run itself must not notice.
        }
    }

    /**
     * What an in-flight run directory says about itself, or empty when it holds no readable
     * progress. {@code status} is `running`, or `interrupted` when the run ended without a report
     * or its process is gone.
     */
    public record Snapshot(String kind, String name, String status, String startedAt,
                           int total, int passed, int failed, String activity) {}

    public static Optional<Snapshot> read(Path runDirectory) {
        Path file = runDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        Map<String, Object> start = null;
        boolean ended = false;
        int total = 0;
        // A test counts its steps; a suite counts its tests. Indexed by number so a step's
        // `running` and final events count once.
        Map<Long, String> outcomes = new HashMap<>();
        String currentTest = null, currentStep = null;
        long testNumber = 0, testTotal = 0;
        for (String line : lines) {
            Map<String, Object> event;
            try {
                // The writer may be mid-line: a torn last line is skipped, not an error.
                event = Json.parseObject(line);
            } catch (IllegalArgumentException torn) {
                continue;
            }
            String kind = event.get("event") instanceof String value ? value : "";
            switch (kind) {
                case "start" -> {
                    start = event;
                    total = (int) number(event, "total");
                }
                case "end" -> ended = true;
                case "test" -> {
                    testNumber = number(event, "number");
                    testTotal = number(event, "total");
                    currentTest = "test " + testNumber + "/" + testTotal + " · " + text(event, "reference");
                    currentStep = null;
                    outcomes.put(testNumber, text(event, "status"));
                    total = (int) testTotal;
                }
                case "step" -> {
                    String status = text(event, "status");
                    currentStep = "running".equals(status)
                        ? "step " + number(event, "number") + "/" + number(event, "total") + " · "
                            + text(event, "action")
                        : null;
                    if (start != null && "test".equals(start.get("kind"))) {
                        outcomes.put(number(event, "number"), status);
                        total = (int) number(event, "total");
                    }
                }
                default -> { }
            }
        }
        if (start == null || !(start.get("name") instanceof String name)
            || !(start.get("kind") instanceof String runKind)) {
            return Optional.empty();
        }
        int passed = (int) outcomes.values().stream().filter("passed"::equals).count();
        int failed = (int) outcomes.values().stream().filter("failed"::equals).count();
        boolean alive = !ended && writerAlive(start);
        String activity = !alive ? null
            : currentTest == null ? currentStep
            : currentStep == null ? currentTest
            : currentTest + " · " + currentStep;
        return Optional.of(new Snapshot(runKind, name, alive ? "running" : "interrupted",
            start.get("startedAt") instanceof String at ? at : null, total, passed, failed,
            activity));
    }

    /** Same pid and, where the platform reports it, the same process start — so a reused pid is not mistaken. */
    private static boolean writerAlive(Map<String, Object> start) {
        if (!(start.get("pid") instanceof Long pid)) return false;
        Optional<ProcessHandle> handle = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        if (handle.isEmpty()) return false;
        if (!(start.get("processStartedAt") instanceof String recorded)) return true;
        return handle.get().info().startInstant()
            .map(actual -> actual.toString().equals(recorded)).orElse(true);
    }

    private static long number(Map<String, Object> event, String key) {
        return event.get(key) instanceof Long value ? value : 0;
    }

    private static String text(Map<String, Object> event, String key) {
        return event.get(key) instanceof String value ? value : "";
    }
}
