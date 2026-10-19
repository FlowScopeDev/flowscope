package dev.flowscope.host;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** In-memory values with secret taint and one interpolation implementation for tests and suites. */
final class RuntimeValues {
    private static final Pattern REFERENCE = Pattern.compile(
        "\\$\\{(env|var|secret|run|test|device|suite)\\.([A-Za-z_][A-Za-z0-9_.-]*)}");

    record Value(String text, boolean secret) {}

    private final Map<String, Value> environment = new LinkedHashMap<>();
    private final Map<String, Value> secretSources = new LinkedHashMap<>();
    private final Map<String, Value> secrets = new LinkedHashMap<>();
    private final Map<String, Value> variables = new LinkedHashMap<>();
    private final Map<String, Value> suiteVariables;
    private final Map<String, Value> builtins = new LinkedHashMap<>();
    private final Redactor redactor;

    RuntimeValues(Map<String, String> environment, Map<String, String> secretSources,
        Map<String, Value> suiteVariables, String testName, String deviceSerial,
        Instant startedAt, UUID runId, Map<String, String> suiteBuiltins) {
        this(environment, secretSources, suiteVariables, testName, deviceSerial, startedAt,
            runId, suiteBuiltins, null);
    }

    RuntimeValues(Map<String, String> environment, Map<String, String> secretSources,
        Map<String, Value> suiteVariables, String testName, String deviceSerial,
        Instant startedAt, UUID runId, Map<String, String> suiteBuiltins, Redactor redactor) {
        this.redactor = redactor == null ? new Redactor() : redactor;
        environment.forEach((key, value) -> this.environment.put(key, new Value(value, true)));
        secretSources.forEach((key, value) -> this.secretSources.put(key, new Value(value, true)));
        this.suiteVariables = suiteVariables == null ? Map.of() : Map.copyOf(suiteVariables);
        this.suiteVariables.values().forEach(this.redactor::remember);
        builtins.put("run.id", new Value(runId.toString(), false));
        builtins.put("run.startedAt", new Value(startedAt.toString(), false));
        builtins.put("test.name", new Value(testName == null ? "" : testName, false));
        builtins.put("test.attempt", new Value("1", false));
        builtins.put("device.serial", new Value(deviceSerial == null ? "" : deviceSerial, false));
        if (suiteBuiltins != null) suiteBuiltins.forEach((key, value) ->
            builtins.put("suite." + key, new Value(value, false)));
    }

    void require(FlowTest.Requirement requirement) {
        Value value = requirement.source().equals("env")
            ? environment.get(requirement.name()) : sourceSecret(requirement.name());
        if (value == null) throw new IllegalArgumentException(
            "Missing " + requirement.source() + " value: " + requirement.name());
        redactor.remember(value);
    }

    void bindSecret(String alias, String source) {
        Value value = sourceSecret(source);
        if (value == null) throw new IllegalArgumentException("Missing secret value: " + source);
        secrets.put(alias, value);
        redactor.remember(value);
    }

    private Value sourceSecret(String name) {
        Value environmentValue = environment.get(name);
        return environmentValue != null ? environmentValue : secretSources.get(name);
    }

    void put(String name, String value, boolean secret) {
        Value stored = new Value(value, secret);
        variables.put(name, stored);
        redactor.remember(stored);
    }

    boolean hasVariable(String name) { return variables.containsKey(name); }
    boolean hasEnvironment(String name) { return environment.containsKey(name); }
    boolean hasSecret(String name) { return secrets.containsKey(name) || sourceSecret(name) != null; }
    Value variable(String name) { return variables.get(name); }
    Map<String, Value> variables() { return Map.copyOf(variables); }

    boolean conditionsPass(Map<String, String> arguments) {
        for (var entry : arguments.entrySet()) {
            String key = entry.getKey();
            boolean matches;
            if (key.equals("if-var")) matches = hasVariable(entry.getValue());
            else if (key.equals("unless-var")) matches = !hasVariable(entry.getValue());
            else if (key.equals("if-env")) matches = hasEnvironment(entry.getValue());
            else if (key.equals("unless-env")) matches = !hasEnvironment(entry.getValue());
            else if (key.equals("if-secret")) matches = hasSecret(entry.getValue());
            else if (key.equals("unless-secret")) matches = !hasSecret(entry.getValue());
            else if (key.startsWith("if-var.")) matches = equals(variables.get(key.substring(7)), entry.getValue());
            else if (key.startsWith("unless-var.")) matches = !equals(variables.get(key.substring(11)), entry.getValue());
            else if (key.startsWith("if-env.")) matches = equals(environment.get(key.substring(7)), entry.getValue());
            else if (key.startsWith("unless-env.")) matches = !equals(environment.get(key.substring(11)), entry.getValue());
            else continue;
            if (!matches) return false;
        }
        return true;
    }

    private boolean equals(Value actual, String expected) {
        redactor.remember(actual);
        return actual != null && actual.text().equals(interpolate(expected));
    }

    String interpolate(String input) {
        Matcher matcher = REFERENCE.matcher(input);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            Value value = resolve(matcher.group(1), matcher.group(2));
            if (value == null) throw new IllegalStateException(
                "Runtime value is not set: " + matcher.group(1) + "." + matcher.group(2));
            matcher.appendReplacement(output, Matcher.quoteReplacement(value.text()));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    boolean isSecretExpression(String input) {
        Matcher matcher = REFERENCE.matcher(input);
        while (matcher.find()) {
            Value value = resolve(matcher.group(1), matcher.group(2));
            if (value != null && value.secret()) return true;
        }
        return false;
    }

    void preflightReferences(String input) {
        Matcher matcher = REFERENCE.matcher(input);
        while (matcher.find()) {
            String namespace = matcher.group(1), name = matcher.group(2);
            if (namespace.equals("env") && !environment.containsKey(name)) {
                throw new IllegalArgumentException("Missing environment variable: " + name);
            }
            if (namespace.equals("secret") && !hasSecret(name)) {
                throw new IllegalArgumentException("Missing secret value: " + name);
            }
            if (namespace.equals("suite") && name.startsWith("var.")
                && !suiteVariables.containsKey(name.substring(4))) {
                throw new IllegalArgumentException("Missing suite variable: " + name.substring(4));
            }
            resolve(namespace, name);
        }
    }

    private Value resolve(String namespace, String name) {
        Value value = switch (namespace) {
            case "env" -> environment.get(name);
            case "var" -> variables.get(name);
            case "secret" -> secrets.containsKey(name) ? secrets.get(name) : sourceSecret(name);
            case "suite" -> name.startsWith("var.")
                ? suiteVariables.get(name.substring(4)) : builtins.get("suite." + name);
            default -> builtins.get(namespace + "." + name);
        };
        if (namespace.equals("env") || namespace.equals("secret") || namespace.equals("var")
            || (namespace.equals("suite") && name.startsWith("var."))) redactor.remember(value);
        return value;
    }

    String redact(String message) {
        return redactor.redact(message);
    }

    Redactor redactor() { return redactor; }

    /** Shared only within a suite run; test variables themselves never escape their test. */
    static final class Redactor {
        // Keep history: overwriting an alias or variable must not expose an earlier value.
        private final Map<String, Boolean> protectedValues = new LinkedHashMap<>();

        void remember(Value value) {
            if (value != null && value.text() != null && !value.text().isEmpty()) {
                protectedValues.merge(value.text(), value.secret(), (left, right) -> left || right);
            }
        }

        String redact(String message) {
            if (message == null) return "";
            if (protectedValues.isEmpty()) return message;
            String alternatives = protectedValues.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(Collectors.joining("|"));
            Matcher matcher = Pattern.compile(alternatives).matcher(message);
            StringBuilder result = new StringBuilder();
            // One pass, longest first: never expose a suffix or reprocess replacement markers.
            while (matcher.find()) {
                matcher.appendReplacement(result,
                    protectedValues.get(matcher.group()) ? "{secret}" : "{runtime}");
            }
            matcher.appendTail(result);
            return result.toString();
        }
    }
}
