package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

/**
 * Parses the `.suite` format — the same line-oriented idiom as `.flow`, down to the tokenizer, so
 * quoting and escaping behave identically in both files.
 *
 * <pre>
 * flowscope-suite 1
 * name "Nightly regression"
 * continue-on-failure true
 *
 * test tests/login.flow
 * test tests/terms.flow
 * </pre>
 *
 * <p>Errors are reported as {@link FlowTestParser.ParseError}: the shared tokenizer already throws
 * it, and the IDE already catches that one type for both formats.
 */
public final class FlowSuiteParser {
    private static final Set<String> HOST_ACTIONS = Set.of(
        "api-request", "set-date", "set-value", "transform", "secret");
    private FlowSuiteParser() {}

    public static FlowSuite parse(Path path) throws IOException {
        return parseLines(Files.readAllLines(path, StandardCharsets.UTF_8),
            path.getFileName().toString(), path.toString());
    }

    /** Parses source that is not on disk yet, so the IDE validates exactly what the CLI would. */
    public static FlowSuite parseSource(String source, String name) {
        return parseLines(List.of(source.split("\\R", -1)), name, name);
    }

    private static FlowSuite parseLines(List<String> lines, String defaultName, String origin) {
        String name = defaultName;
        boolean continueOnFailure = false;
        List<FlowSuite.Entry> entries = new ArrayList<>();
        List<FlowTest.Requirement> requirements = new ArrayList<>();
        List<FlowTest.Step> hooks = new ArrayList<>();
        boolean versionSeen = false;
        boolean afterSeen = false;

        for (LogicalLine logical : logicalLines(lines)) {
            String line = logical.text().strip();
            int lineNumber = logical.line();
            if (line.isEmpty() || line.startsWith("#")) continue;
            List<String> tokens = FlowTestParser.tokenize(line, lineNumber);
            String keyword = tokens.getFirst();
            if (!versionSeen) {
                if (tokens.size() != 2 || !keyword.equals("flowscope-suite")
                    || !tokens.get(1).equals("1")) {
                    throw error(lineNumber, "first statement must be: flowscope-suite 1");
                }
                versionSeen = true;
                continue;
            }
            switch (keyword) {
                case "name" -> name = remainder(tokens, lineNumber);
                case "continue-on-failure" ->
                    continueOnFailure = parseBoolean(tokens, lineNumber);
                case "require" -> requirements.add(FlowTestParser.parseRequirement(tokens, lineNumber));
                case "before", "after" -> {
                    boolean after = keyword.equals("after");
                    if (!after && afterSeen) throw error(lineNumber, "before statements must precede after statements");
                    afterSeen |= after;
                    hooks.add(parseHook(tokens, lineNumber,
                        after ? FlowTest.Phase.AFTER : FlowTest.Phase.BEFORE));
                }
                case "test" -> {
                    if (afterSeen) throw error(lineNumber, "test statements must precede after statements");
                    entries.add(parseEntry(tokens, lineNumber));
                }
                default -> throw error(lineNumber, "unknown statement: " + keyword);
            }
        }
        if (!versionSeen) throw error(0, "Empty suite: " + origin);
        if (entries.isEmpty()) throw error(0, "Suite has no tests: " + origin);
        return new FlowSuite(name, continueOnFailure, List.copyOf(requirements),
            List.copyOf(hooks), List.copyOf(entries));
    }

    private static FlowTest.Step parseHook(List<String> tokens, int line, FlowTest.Phase phase) {
        if (tokens.size() < 2) throw error(line, phase.value() + " requires an action");
        String action = tokens.get(1);
        if (!HOST_ACTIONS.contains(action)) {
            throw error(line, "suite hooks allow only api-request, set-date, set-value, transform, or secret");
        }
        Map<String, String> arguments = new LinkedHashMap<>();
        for (int index = 2; index < tokens.size(); index++) {
            String token = tokens.get(index);
            int equals = token.indexOf('=');
            if (equals <= 0) throw error(line, "expected key=value, found: " + token);
            String key = token.substring(0, equals);
            if (arguments.put(key, token.substring(equals + 1)) != null) {
                throw error(line, "duplicate argument: " + key);
            }
        }
        // Reuse the flow parser's complete action validation without exposing another public parser.
        String source = "flowscope 1\npackage dev.flowscope.validation\n" + phase.value() + " "
            + tokens.subList(1, tokens.size()).stream().map(FlowSuiteParser::quote).collect(
                java.util.stream.Collectors.joining(" "))
            + (phase == FlowTest.Phase.BEFORE ? "\nstep launch" : "\n");
        if (phase == FlowTest.Phase.AFTER) source = "flowscope 1\npackage dev.flowscope.validation\nstep launch\nafter "
            + tokens.subList(1, tokens.size()).stream().map(FlowSuiteParser::quote).collect(
                java.util.stream.Collectors.joining(" ")) + "\n";
        FlowTest parsed = FlowTestParser.parseSource(source, "suite-hook");
        return parsed.steps().stream().filter(step -> step.phase() == phase).findFirst()
            .map(step -> new FlowTest.Step(line, phase, action, step.arguments())).orElseThrow();
    }

    private static String quote(String token) {
        if (!token.matches(".*[\\s'\"\\\\].*")) return token;
        return "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record LogicalLine(int line, String text) {}

    private static List<LogicalLine> logicalLines(List<String> physical) {
        List<LogicalLine> result = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        int first = 1;
        for (int index = 0; index < physical.size(); index++) {
            String raw = physical.get(index);
            if (joined.isEmpty()) first = index + 1;
            String trimmed = raw.stripTrailing();
            boolean continues = trimmed.endsWith("\\") && !trimmed.endsWith("\\\\");
            joined.append(continues ? trimmed.substring(0, trimmed.length() - 1) : raw);
            if (continues) joined.append(' ');
            else { result.add(new LogicalLine(first, joined.toString())); joined.setLength(0); }
        }
        if (!joined.isEmpty()) throw error(first, "line continuation has no following line");
        return result;
    }

    private static FlowSuite.Entry parseEntry(List<String> tokens, int line) {
        if (tokens.size() != 2) throw error(line, "test requires exactly one path");
        String reference = tokens.get(1);
        if (reference.isBlank()) throw error(line, "test requires a path");
        // Validated here rather than at resolution time so `suite validate` catches a suite that
        // would only ever work on the machine that authored it.
        if (reference.contains("\\")) {
            throw error(line, "test path must use forward slashes: " + reference);
        }
        if (reference.startsWith("/") || reference.matches("^[A-Za-z]:.*")) {
            throw error(line, "test path must be relative to the flows directory: " + reference);
        }
        for (String segment : reference.split("/")) {
            if (segment.equals("..")) {
                throw error(line, "test path must not contain '..': " + reference);
            }
        }
        if (!reference.endsWith(FlowProject.TEST_EXTENSION)) {
            throw error(line, "test path must end in " + FlowProject.TEST_EXTENSION + ": "
                + reference);
        }
        return new FlowSuite.Entry(line, reference);
    }

    private static boolean parseBoolean(List<String> tokens, int line) {
        if (tokens.size() != 2) {
            throw error(line, "continue-on-failure requires exactly one value");
        }
        return switch (tokens.get(1)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw error(line, "continue-on-failure must be true or false");
        };
    }

    private static String remainder(List<String> tokens, int line) {
        if (tokens.size() < 2) throw error(line, "name requires a value");
        return String.join(" ", tokens.subList(1, tokens.size()));
    }

    private static FlowTestParser.ParseError error(int line, String message) {
        return new FlowTestParser.ParseError(line, message);
    }
}
