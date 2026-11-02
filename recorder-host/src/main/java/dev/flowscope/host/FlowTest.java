package dev.flowscope.host;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

public record FlowTest(
    String name,
    String packageName,
    CaptureMode mode,
    long timeoutMillis,
    Path sourceDirectory,
    List<Requirement> requirements,
    List<Step> steps
) {
    public FlowTest(String name, String packageName, CaptureMode mode, long timeoutMillis,
        List<Step> steps) {
        this(name, packageName, mode, timeoutMillis, Path.of(".").toAbsolutePath(), List.of(), steps);
    }

    public FlowTest(String name, String packageName, CaptureMode mode, long timeoutMillis,
        Path sourceDirectory, List<Step> steps) {
        this(name, packageName, mode, timeoutMillis, sourceDirectory, List.of(), steps);
    }

    public record Requirement(int line, String source, String name) {}

    public enum Phase {
        BEFORE("before"), TEST("test"), AFTER("after");

        private final String value;
        Phase(String value) { this.value = value; }
        public String value() { return value; }
    }

    /**
     * {@code continueOnFailure} marks a test step whose failure fails the test but does not stop it: the
     * steps after it still run.
     */
    public record Step(int line, Phase phase, String action, Map<String, String> arguments, String label,
                       boolean continueOnFailure) {
        public Step(int line, Phase phase, String action, Map<String, String> arguments, String label) {
            this(line, phase, action, arguments, label, false);
        }
        public Step(int line, Phase phase, String action, Map<String, String> arguments) {
            this(line, phase, action, arguments, "");
        }
        public String displayName() { return label == null || label.isBlank() ? action : label; }
        public Step(int line, String action, Map<String, String> arguments) {
            this(line, Phase.TEST, action, arguments);
        }
    }
}
