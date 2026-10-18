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
 * A finished run, read back from its directory — a single test's `report.json` or a suite's
 * `suite-report.json`. The IDE lists these, so it reads them through this typed accessor rather
 * than growing more ad-hoc string extraction.
 */
public record RunReport(Path directory, String kind, String name, String status,
                        String startedAt, int total, int passed, int failed) {
    public static final String TEST_REPORT = "report.json";
    public static final String SUITE_REPORT = "suite-report.json";

    public boolean isSuite() {
        return "suite".equals(kind);
    }

    public boolean isPassed() {
        return "passed".equals(status);
    }

    /** Empty when the directory holds neither report, or holds one that cannot be read. */
    public static Optional<RunReport> read(Path runDirectory) {
        Path suiteReport = runDirectory.resolve(SUITE_REPORT);
        if (Files.isRegularFile(suiteReport)) return readSuite(runDirectory, suiteReport);
        Path testReport = runDirectory.resolve(TEST_REPORT);
        if (Files.isRegularFile(testReport)) return readTest(runDirectory, testReport);
        return Optional.empty();
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
                count(totals, "total"), count(totals, "passed"), count(totals, "failed")));
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
                total, passed, total - passed));
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
}
