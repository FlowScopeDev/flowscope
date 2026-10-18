package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        boolean versionSeen = false;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            int lineNumber = index + 1;
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
                case "test" -> entries.add(parseEntry(tokens, lineNumber));
                default -> throw error(lineNumber, "unknown statement: " + keyword);
            }
        }
        if (!versionSeen) throw error(0, "Empty suite: " + origin);
        if (entries.isEmpty()) throw error(0, "Suite has no tests: " + origin);
        return new FlowSuite(name, continueOnFailure, List.copyOf(entries));
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
