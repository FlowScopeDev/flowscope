package dev.flowscope.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Metadata-only step plan and results for the IDE. Never serializes step arguments or values. */
public final class RunSteps {
    public static final String FILE = "run-plan.json";
    private static final Set<String> STATUSES = Set.of("pending", "running", "passed", "failed", "skipped", "interrupted", "not-run", "cancelled");

    private RunSteps() {}

    public record Step(int number, int line, String phase, String action, String status) {}
    public record Group(String id, String name, String reference, String report, String status, List<Step> steps) {}
    public record Snapshot(boolean planned, List<Group> groups) {}

    static Group testGroup(int number, String reference, FlowTest test, String report) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < test.steps().size(); i++) steps.add(plannedStep(i + 1, test.steps().get(i)));
        return new Group(number == 0 ? "test" : "test:" + number, test.name(), reference, report, "pending", List.copyOf(steps));
    }

    static Group hookGroup(FlowTest.Phase phase, List<FlowTest.Step> hooks) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < hooks.size(); i++) {
            if (hooks.get(i).phase() == phase) steps.add(plannedStep(i + 1, hooks.get(i)));
        }
        return new Group(phase.value(), phase == FlowTest.Phase.BEFORE ? "Suite setup" : "Suite cleanup",
            "", "", "pending", List.copyOf(steps));
    }

    private static Step plannedStep(int number, FlowTest.Step step) {
        return new Step(number, step.line(), step.phase().value(), step.action(), "pending");
    }

    static void write(Path directory, List<Group> groups) {
        StringBuilder json = new StringBuilder("{\"groups\":[");
        boolean first = true;
        for (Group group : groups) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(Json.quote(group.id())).append(",\"name\":").append(Json.quote(group.name()))
                .append(",\"reference\":").append(Json.quote(group.reference())).append(",\"report\":").append(Json.quote(group.report()))
                .append(",\"steps\":[");
            for (int i = 0; i < group.steps().size(); i++) {
                if (i > 0) json.append(',');
                Step step = group.steps().get(i);
                json.append("{\"number\":").append(step.number()).append(",\"line\":").append(step.line())
                    .append(",\"phase\":").append(Json.quote(step.phase())).append(",\"action\":").append(Json.quote(step.action())).append('}');
            }
            json.append("]}");
        }
        try { Files.writeString(directory.resolve(FILE), json.append("]}\n")); }
        catch (IOException ignored) { /* Observer metadata must never fail a run. */ }
    }

    public static Snapshot read(Path directory, RunReport summary) {
        Map<String, MutableGroup> groups = new LinkedHashMap<>();
        Map<String, Object> plan = object(directory.resolve(FILE));
        for (Map<String, Object> item : objects(plan.get("groups"))) {
            MutableGroup group = group(groups, text(item, "id"), text(item, "name"));
            group.reference = text(item, "reference");
            group.report = text(item, "report");
            for (Map<String, Object> step : objects(item.get("steps"))) group.step(step, "pending");
        }
        boolean planned = !groups.isEmpty();
        boolean suite = summary != null && summary.isSuite();
        int currentTest = 0;
        for (Map<String, Object> event : events(directory.resolve(RunProgress.FILE))) {
            switch (text(event, "event")) {
                case "start" -> suite = "suite".equals(text(event, "kind"));
                case "test" -> {
                    currentTest = number(event, "number");
                    MutableGroup group = group(groups, "test:" + currentTest, text(event, "reference"));
                    group.reference = text(event, "reference");
                    group.status = status(event, "pending");
                    if (group.status.equals("skipped")) group.finishPending("skipped");
                }
                case "step", "hook" -> {
                    boolean hook = "hook".equals(text(event, "event"));
                    String id = hook ? text(event, "phase") : suite ? "test:" + currentTest : "test";
                    MutableGroup group = group(groups, id, hook ? "Suite " + id : summary == null ? "Test" : summary.name());
                    group.step(event, "pending");
                    if (!hook && !planned) group.placeholders(number(event, "total"));
                }
                default -> { }
            }
        }
        Map<String, Object> suiteReport = object(directory.resolve(RunReport.SUITE_REPORT));
        if (!suiteReport.isEmpty()) {
            for (Map<String, Object> item : objects(suiteReport.get("tests"))) {
                MutableGroup group = group(groups, "test:" + number(item, "number"), text(item, "name"));
                group.reference = text(item, "reference");
                group.status = status(item, "not-run");
                if (!text(item, "report").isEmpty()) group.report = text(item, "report");
                readSteps(directory, group);
                group.finishPending(group.status.equals("skipped") ? "skipped" : "not-run");
            }
            for (Map<String, Object> item : objects(suiteReport.get("hooks"))) {
                String phase = text(item, "phase");
                MutableGroup group = group(groups, phase, "Suite " + phase);
                // Hook reports predate numbering: match the original source line.
                int number = group.steps.values().stream().filter(s -> s.line() == number(item, "line"))
                    .mapToInt(Step::number).findFirst().orElse(group.steps.size() + 1);
                Map<String, Object> numbered = new LinkedHashMap<>(item);
                numbered.put("number", (long) number);
                group.step(numbered, "not-run");
            }
        } else if (!suite) {
            Map<String, Object> testReport = object(directory.resolve(RunReport.TEST_REPORT));
            if (!testReport.isEmpty()) {
                MutableGroup group = group(groups, "test", text(testReport, "name"));
                group.status = status(testReport, "not-run");
                for (Map<String, Object> step : objects(testReport.get("steps"))) group.step(step, "not-run");
            }
        } else {
            // Finished children are authoritative even while the rest of the suite is still running.
            for (MutableGroup group : groups.values()) readSteps(directory, group);
        }
        boolean interrupted = summary != null && summary.isInterrupted();
        List<Group> result = new ArrayList<>();
        for (MutableGroup group : groups.values()) {
            boolean terminal = interrupted || Set.of("failed", "passed", "skipped").contains(group.status)
                || (summary != null && !summary.isRunning());
            if (terminal) group.finishPending(group.status.equals("skipped") ? "skipped" : "not-run");
            List<Step> steps = group.steps.values().stream().sorted(java.util.Comparator.comparingInt(Step::number))
                .map(s -> terminal && s.status().equals("running")
                    ? new Step(s.number(), s.line(), s.phase(), s.action(), "interrupted") : s).toList();
            if (!steps.isEmpty()) result.add(new Group(group.id, group.name, group.reference, group.report, group.status, steps));
        }
        result.sort(java.util.Comparator.comparingInt(group -> {
            if (group.id().equals("before")) return -1;
            if (group.id().equals("after")) return Integer.MAX_VALUE;
            try { return Integer.parseInt(group.id().replace("test:", "")); }
            catch (NumberFormatException standalone) { return 0; }
        }));
        return new Snapshot(planned, List.copyOf(result));
    }

    private static void readSteps(Path directory, MutableGroup group) {
        if (group.report.isEmpty()) return;
        try {
            Path root = directory.toRealPath();
            Path relative = Path.of(group.report);
            if (relative.isAbsolute() || group.report.contains("\\")) return;
            for (Path part : relative) if (part.toString().equals("..")) return;
            Path file = root.resolve(relative).toRealPath();
            if (!file.startsWith(root) || !file.getFileName().toString().equals(RunReport.TEST_REPORT)) return;
            Map<String, Object> report = object(file);
            if (!report.isEmpty()) {
                group.status = status(report, group.status);
                for (Map<String, Object> step : objects(report.get("steps"))) group.step(step, "not-run");
            }
        } catch (IOException | IllegalArgumentException ignored) { /* Absent or untrusted child reference. */ }
    }

    private static final class MutableGroup {
        final String id, name;
        String reference = "", report = "", status = "pending";
        final Map<Integer, Step> steps = new LinkedHashMap<>();
        MutableGroup(String id, String name) { this.id = id; this.name = name; }
        void step(Map<String, Object> value, String fallback) {
            int number = number(value, "number");
            if (number < 1) return;
            steps.put(number, new Step(number, number(value, "line"), text(value, "phase"), text(value, "action"), status(value, fallback)));
        }
        void placeholders(int total) {
            for (int i = 1; i <= Math.min(total, 100_000); i++) steps.putIfAbsent(i, new Step(i, 0, "", "", "pending"));
        }
        void finishPending(String replacement) {
            steps.replaceAll((n, s) -> s.status().equals("pending")
                ? new Step(s.number(), s.line(), s.phase(), s.action(), replacement) : s);
        }
    }
    private static MutableGroup group(Map<String, MutableGroup> groups, String id, String name) {
        return groups.computeIfAbsent(id, key -> new MutableGroup(key, name));
    }
    private static Map<String, Object> object(Path path) {
        try { return Json.parseObject(Files.readString(path)); }
        catch (IOException | IllegalArgumentException invalid) { return Map.of(); }
    }
    private static List<Map<String, Object>> events(Path path) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path)) {
                try { result.add(Json.parseObject(line)); }
                catch (IllegalArgumentException torn) { /* Concurrent writer may be mid-line. */ }
            }
        } catch (IOException absent) { /* Completed run. */ }
        return result;
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }
    private static int number(Map<String, Object> value, String key) {
        return value.get(key) instanceof Long number ? number.intValue() : 0;
    }
    private static String text(Map<String, Object> value, String key) {
        return value.get(key) instanceof String text ? text : "";
    }
    private static String status(Map<String, Object> value, String fallback) {
        String status = text(value, "status");
        return STATUSES.contains(status) ? status : fallback;
    }
}
