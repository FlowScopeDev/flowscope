package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A run, read back from its directory — a single test's `report.json` or a suite's
 * `suite-report.json`, or, while no report exists yet, its {@link RunProgress} file. The IDE lists
 * these, so it reads them through this typed accessor rather than growing more ad-hoc string
 * extraction.
 *
 * <p>{@code status} is `passed` or `failed` for a finished run, `running` for one still in flight
 * (from the CLI or the IDE), and `interrupted` for one whose process ended without a report.
 * {@code activity} describes what a running run is doing now, and is null otherwise.
 */
public record RunReport(Path directory, String kind, String name, String status,
                        String startedAt, int total, int passed, int failed, String activity) {
    public static final String TEST_REPORT = "report.json";
    public static final String SUITE_REPORT = "suite-report.json";

    public boolean isSuite() {
        return "suite".equals(kind);
    }

    public boolean isPassed() {
        return "passed".equals(status);
    }

    public boolean isRunning() {
        return "running".equals(status);
    }

    public boolean isInterrupted() {
        return "interrupted".equals(status);
    }

    /** Empty when the directory holds no report or progress, or holds one that cannot be read. */
    public static Optional<RunReport> read(Path runDirectory) {
        Path suiteReport = runDirectory.resolve(SUITE_REPORT);
        if (Files.isRegularFile(suiteReport)) return readSuite(runDirectory, suiteReport);
        Path testReport = runDirectory.resolve(TEST_REPORT);
        if (Files.isRegularFile(testReport)) return readTest(runDirectory, testReport);
        return RunProgress.read(runDirectory).map(live -> new RunReport(runDirectory, live.kind(),
            live.name(), live.status(), live.startedAt(), live.total(), live.passed(), live.failed(),
            live.activity()));
    }

    private static Optional<RunReport> readSuite(Path directory, Path file) {
        try {
            Map<String, Object> root = Json.parseObject(
                Files.readString(file, StandardCharsets.UTF_8));
            Map<String, Object> totals = Json.optionalObject(root, "totals", SUITE_REPORT);
            return Optional.of(new RunReport(directory, "suite",
                Json.requireString(root, "name", SUITE_REPORT),
                Json.requireString(root, "status", SUITE_REPORT),
                Json.optionalString(root, "startedAt", SUITE_REPORT),
                count(totals, "total"), count(totals, "passed"), count(totals, "failed"), null));
        } catch (IOException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    private static Optional<RunReport> readTest(Path directory, Path file) {
        try {
            Map<String, Object> root = Json.parseObject(
                Files.readString(file, StandardCharsets.UTF_8));
            Object steps = root.get("steps");
            int total = steps instanceof List<?> list ? list.size() : 0;
            int passed = 0;
            if (steps instanceof List<?> list) {
                for (Object step : list) {
                    if (step instanceof Map<?, ?> entry && "passed".equals(entry.get("status"))) {
                        passed++;
                    }
                }
            }
            return Optional.of(new RunReport(directory, "test",
                Json.requireString(root, "name", TEST_REPORT),
                Json.requireString(root, "status", TEST_REPORT),
                Json.optionalString(root, "startedAt", TEST_REPORT),
                total, passed, total - passed, null));
        } catch (IOException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    private static int count(Map<String, Object> totals, String key) {
        if (totals == null) return 0;
        Long value = Json.optionalInteger(totals, key, SUITE_REPORT);
        return value == null ? 0 : value.intValue();
    }

    /**
     * The newest runs in {@code reportsDir}, newest first. Run directories are timestamp-prefixed,
     * so reverse directory-name order is chronological without reading every file.
     */
    public static List<RunReport> list(Path reportsDir, int limit) throws IOException {
        if (!Files.isDirectory(reportsDir)) return List.of();
        List<Path> candidates;
        try (Stream<Path> children = Files.list(reportsDir)) {
            candidates = children
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString())
                    .reversed())
                .toList();
        }
        List<RunReport> reports = new ArrayList<>();
        for (Path candidate : candidates) {
            if (reports.size() >= limit) break;
            read(candidate).ifPresent(reports::add);
        }
        return List.copyOf(reports);
    }

    /**
     * A cheap stamp of what {@link #list} would return: the newest run directories' names and the
     * size and modification time of each one's report or progress file. Only file metadata is
     * read, so a watcher can call this every second and re-list only when it changes.
     */
    public static String fingerprint(Path reportsDir, int limit) throws IOException {
        if (!Files.isDirectory(reportsDir)) return "";
        List<Path> newest;
        try (Stream<Path> children = Files.list(reportsDir)) {
            // Headroom over the limit: a few of the newest directories may not be runs at all.
            newest = children.filter(Files::isDirectory)
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString())
                    .reversed())
                .limit(Math.max(limit * 3L, 30))
                .toList();
        }
        StringBuilder stamp = new StringBuilder();
        for (Path directory : newest) {
            stamp.append(directory.getFileName()).append('|');
            for (String name : List.of(SUITE_REPORT, TEST_REPORT, RunProgress.FILE)) {
                Path file = directory.resolve(name);
                try {
                    stamp.append(Files.size(file)).append('@')
                        .append(Files.getLastModifiedTime(file).toMillis());
                } catch (IOException absent) {
                    stamp.append('-');
                }
                stamp.append('|');
            }
            stamp.append('\n');
        }
        return stamp.toString();
    }
}
