package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FlowTestParser {
    private static final Set<String> ACTIONS = Set.of(
        "launch", "wait-visible", "tap", "tap-if-visible", "type", "scroll", "back",
        "long-press", "swipe", "scroll-to", "press-key", "clear-text", "hide-keyboard",
        "assert-visible", "assert-not-visible", "screenshot", "api-request",
        "clear-app-data", "force-stop", "grant-permission", "revoke-permission",
        "reset-app-permissions", "open-deep-link", "assert-deep-link-handler",
        "assert-app-link-verified", "install-apk", "uninstall-app", "assert-installed",
        "set-install-referrer", "clear-install-referrer",
        "web-wait", "web-tap", "web-type", "web-assert-visible", "web-assert-text",
        "web-assert-url", "web-screenshot", "web-assert-cookie", "web-clear-cookies",
        "web-assert-storage", "web-clear-storage", "web-assert-interface",
        "web-assert-secure-context", "web-snapshot");

    /** Keys `press-key` accepts. The runner maps each to one fixed Android keycode; raw keycodes are never accepted. */
    static final Set<String> PRESS_KEYS = Set.of(
        "enter", "search", "delete", "tab", "home", "app-switch", "volume-up", "volume-down");
    static final long LONG_PRESS_MIN_MILLIS = 500, LONG_PRESS_MAX_MILLIS = 10_000;
    static final long SWIPE_MIN_MILLIS = 100, SWIPE_MAX_MILLIS = 5_000;
    static final long SWIPE_MIN_PERCENT = 10, SWIPE_MAX_PERCENT = 90;
    static final long SCROLL_TO_MAX_SWIPES_LIMIT = 50;
    private static final Set<String> SELECTOR_ACTIONS = Set.of("wait-visible", "tap", "tap-if-visible",
        "assert-visible", "assert-not-visible", "long-press", "scroll-to", "clear-text");
    private static final Set<String> DIRECTIONS = Set.of("up", "down", "left", "right");

    private FlowTestParser() {}

    /** Every action name the parser accepts, for tools that must stay in step with it. */
    public static Set<String> actions() {
        return ACTIONS;
    }

    public static FlowTest parse(Path path) throws IOException {
        return parseLines(Files.readAllLines(path, StandardCharsets.UTF_8),
            path.getFileName().toString(), path.toString(), path.toAbsolutePath().getParent());
    }

    /**
     * Parses `.flow` source that is not on disk yet, so an editor can validate exactly what
     * {@code flowscope test validate} would say about the same text. Never reads or writes files.
     */
    public static FlowTest parseSource(String source, String name) {
        return parseLines(List.of(source.split("\\R", -1)), name, name,
            Path.of(".").toAbsolutePath().normalize());
    }

    private static FlowTest parseLines(List<String> physicalLines, String defaultName, String origin,
        Path sourceDirectory) {
        String name = defaultName;
        String packageName = null;
        CaptureMode mode = CaptureMode.ZERO_CODE;
        long timeout = 10_000;
        List<FlowTest.Step> steps = new ArrayList<>();
        boolean versionSeen = false;
        int lastPhase = 0;

        List<LogicalLine> lines = logicalLines(physicalLines);
        for (LogicalLine logical : lines) {
            String line = logical.text().strip();
            int lineNumber = logical.line();
            if (line.isEmpty() || line.startsWith("#")) continue;
            List<String> tokens = tokenize(line, lineNumber);
            String keyword = tokens.getFirst();
            if (!versionSeen) {
                if (tokens.size() != 2 || !keyword.equals("flowscope") || !tokens.get(1).equals("1")) {
                    throw error(lineNumber, "first statement must be: flowscope 1");
                }
                versionSeen = true;
                continue;
            }
            switch (keyword) {
                case "name" -> name = remainder(tokens, lineNumber, "name");
                case "package" -> packageName = singleValue(tokens, lineNumber, "package");
                case "mode" -> mode = CaptureMode.parse(singleValue(tokens, lineNumber, "mode"));
                case "timeout" -> timeout = parsePositiveLong(
                    singleValue(tokens, lineNumber, "timeout"), lineNumber, "timeout");
                case "before", "step", "after" -> {
                    FlowTest.Phase phase = keyword.equals("before") ? FlowTest.Phase.BEFORE
                        : keyword.equals("after") ? FlowTest.Phase.AFTER : FlowTest.Phase.TEST;
                    int ordinal = phase.ordinal();
                    if (ordinal < lastPhase) {
                        throw error(lineNumber, keyword + " statements are out of lifecycle order");
                    }
                    lastPhase = ordinal;
                    steps.add(parseStep(tokens, lineNumber, phase));
                }
                default -> throw error(lineNumber, "unknown statement: " + keyword);
            }
        }
        if (!versionSeen) throw new ParseError(0, "Empty test: " + origin);
        if (packageName == null || packageName.isBlank()) {
            throw new ParseError(0, "Missing package statement in " + origin);
        }
        if (steps.stream().noneMatch(step -> step.phase() == FlowTest.Phase.TEST)) {
            throw new ParseError(0, "Test has no main steps: " + origin);
        }
        return new FlowTest(name, packageName, mode, timeout, sourceDirectory, List.copyOf(steps));
    }

    /** An {@link IllegalArgumentException} that also reports which line failed (0 = whole file). */
    public static final class ParseError extends IllegalArgumentException {
        private final int line;

        ParseError(int line, String message) {
            super(line > 0 ? "Line " + line + ": " + message : message);
            this.line = line;
        }

        public int line() {
            return line;
        }

        /** The message without the "Line N: " prefix. */
        public String detail() {
            String message = getMessage();
            int cut = message.indexOf(": ");
            return line > 0 && cut >= 0 ? message.substring(cut + 2) : message;
        }
    }

    private static FlowTest.Step parseStep(
        List<String> tokens, int line, FlowTest.Phase phase) {
        if (tokens.size() < 2) throw error(line, "step requires an action");
        String action = tokens.get(1);
        if (!ACTIONS.contains(action)) throw error(line, "unknown action: " + action);
        Map<String, String> arguments = new LinkedHashMap<>();
        for (int index = 2; index < tokens.size(); index++) {
            String token = tokens.get(index);
            int equals = token.indexOf('=');
            if (equals <= 0) throw error(line, "expected key=value, found: " + token);
            String previous = arguments.put(token.substring(0, equals), token.substring(equals + 1));
            if (previous != null) throw error(line, "duplicate argument: " + token.substring(0, equals));
        }
        validateAction(action, arguments, line);
        return new FlowTest.Step(line, phase, action, Map.copyOf(arguments));
    }

    private static void validateAction(String action, Map<String, String> arguments, int line) {
        boolean hasSelector = arguments.keySet().stream().anyMatch(UiHierarchy.SELECTOR_KEYS::contains);
        if ((SELECTOR_ACTIONS.contains(action)) && !hasSelector) {
            throw error(line,
                action + " requires id=, text=, text-contains=, desc=, or class=");
        }
        if (SELECTOR_ACTIONS.contains(action) || action.equals("type") || action.equals("swipe")) {
            validateSelectorFilters(arguments, line);
        }
        validateGestures(action, arguments, line);
        if (action.equals("type") && !arguments.containsKey("value")) {
            throw error(line, "type requires value=");
        }
        if (action.equals("scroll")) {
            String direction = arguments.get("direction");
            if (!Set.of("up", "down", "left", "right").contains(direction)) {
                throw error(line, "scroll direction must be up, down, left, or right");
            }
        }
        if (Set.of("grant-permission", "revoke-permission").contains(action)
            && !arguments.containsKey("permission")) {
            throw error(line, action + " requires permission=");
        }
        if (action.equals("api-request")) {
            String method = arguments.getOrDefault("method", "GET").toUpperCase();
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD").contains(method)) {
                throw error(line, "api-request method must be GET, POST, PUT, PATCH, DELETE, or HEAD");
            }
            if (!arguments.containsKey("url")) throw error(line, "api-request requires url=");
            if (arguments.containsKey("body") && arguments.containsKey("body-file")) {
                throw error(line, "api-request accepts body= or body-file=, not both");
            }
            if (arguments.containsKey("retry-interval") && !Set.of("GET", "HEAD").contains(method)) {
                throw error(line, "retry-interval is only allowed for GET or HEAD");
            }
            if (arguments.containsKey("retry-interval")) {
                parsePositiveLong(arguments.get("retry-interval"), line, "retry-interval");
            }
            if (arguments.containsKey("expect-status")) parseStatuses(arguments.get("expect-status"), line);
        }
        if (Set.of("open-deep-link", "assert-deep-link-handler").contains(action)
            && !arguments.containsKey("uri")) {
            throw error(line, action + " requires uri=");
        }
        if (action.equals("assert-app-link-verified") && !arguments.containsKey("host")) {
            throw error(line, "assert-app-link-verified requires host=");
        }
        if (action.equals("install-apk") && !arguments.containsKey("file")) {
            throw error(line, "install-apk requires file=");
        }
        if (action.equals("set-install-referrer") && !arguments.containsKey("value")) {
            throw error(line, "set-install-referrer requires value=");
        }
        for (String flag : List.of("cold-start", "replace", "downgrade", "grant-permissions",
            "test-only", "keep-data")) {
            if (arguments.containsKey(flag)) parseBoolean(arguments.get(flag), line, flag);
        }
        if (Set.of("web-wait", "web-tap", "web-type", "web-assert-visible",
            "web-assert-text").contains(action) && !arguments.containsKey("css")) {
            throw error(line, action + " requires css=");
        }
        if (Set.of("web-type", "web-assert-text").contains(action)
            && !arguments.containsKey(action.equals("web-type") ? "value" : "text")) {
            throw error(line, action + " requires "
                + (action.equals("web-type") ? "value=" : "text="));
        }
        if (action.equals("web-assert-url")
            && arguments.keySet().stream().noneMatch(Set.of("url", "path", "contains")::contains)) {
            throw error(line, "web-assert-url requires url=, path=, or contains=");
        }
        if (action.equals("web-assert-cookie")) {
            if (!arguments.containsKey("name")) throw error(line, "web-assert-cookie requires name=");
            for (String flag : List.of("exists", "secure", "http-only", "session")) {
                if (arguments.containsKey(flag)) parseBoolean(arguments.get(flag), line, flag);
            }
            if (arguments.containsKey("same-site")
                && !Set.of("Lax", "Strict", "None").contains(arguments.get("same-site"))) {
                throw error(line, "same-site must be Lax, Strict, or None");
            }
        }
        if (action.equals("web-clear-cookies") && !arguments.containsKey("domain")) {
            throw error(line, "web-clear-cookies requires domain=");
        }
        if (action.equals("web-clear-cookies") && arguments.containsKey("domain")
            && !arguments.get("domain").contains("${")
            && !arguments.get("domain").matches("\\.?[A-Za-z0-9.-]+")) {
            throw error(line, "web-clear-cookies domain must be a hostname");
        }
        if (Set.of("web-assert-cookie", "web-clear-cookies").contains(action)
            && arguments.containsKey("path") && !arguments.get("path").startsWith("/")) {
            throw error(line, action + " path must start with '/'");
        }
        if (action.equals("web-assert-storage")) {
            if (!Set.of("local", "session").contains(arguments.get("area"))) {
                throw error(line, "web-assert-storage area must be local or session");
            }
            if (!arguments.containsKey("key")) throw error(line, "web-assert-storage requires key=");
            if (arguments.containsKey("exists")) parseBoolean(arguments.get("exists"), line, "exists");
        }
        if (action.equals("web-clear-storage")) {
            if (!arguments.containsKey("origin")) throw error(line, "web-clear-storage requires origin=");
            if (!arguments.containsKey("types")) throw error(line, "web-clear-storage requires types=");
            Set<String> allowed = Set.of("local", "session", "indexeddb", "cache");
            for (String type : arguments.get("types").split(",")) {
                if (!allowed.contains(type.strip())) {
                    throw error(line, "web-clear-storage types must use local, session, indexeddb, or cache");
                }
            }
        }
        if (action.equals("web-assert-interface")) {
            if (!arguments.containsKey("name")) throw error(line, "web-assert-interface requires name=");
            if (arguments.containsKey("exists")) parseBoolean(arguments.get("exists"), line, "exists");
            if (arguments.containsKey("method") && arguments.containsKey("methods")) {
                throw error(line, "web-assert-interface accepts method= or methods=, not both");
            }
            if (arguments.containsKey("methods") && Arrays.stream(arguments.get("methods").split(",", -1))
                .map(String::strip).anyMatch(String::isBlank)) {
                throw error(line, "web-assert-interface methods must be a comma-separated non-empty list");
            }
            if (arguments.containsKey("source")
                && !Set.of("any", "runtime", "agent").contains(arguments.get("source"))) {
                throw error(line, "web-assert-interface source must be any, runtime, or agent");
            }
        }
        if (action.equals("web-assert-secure-context")) {
            if (!arguments.containsKey("value")) throw error(line, "web-assert-secure-context requires value=");
            parseBoolean(arguments.get("value"), line, "value");
        }
    }

    /** `index` and the accessibility-state filters narrow a selector; they never select on their own. */
    private static void validateSelectorFilters(Map<String, String> arguments, int line) {
        if (arguments.containsKey("index")) {
            String index = arguments.get("index").strip();
            if (!index.contains("${") && !index.matches("\\d{1,4}")) {
                throw error(line, "index must be a zero-based number, 0 to 9999");
            }
        }
        for (String key : UiHierarchy.STATE_KEYS) {
            String value = arguments.get(key);
            if (value != null && !value.contains("${")) parseBoolean(value.strip(), line, key);
        }
    }

    private static void validateGestures(String action, Map<String, String> arguments, int line) {
        switch (action) {
            case "long-press" -> boundedMillis(arguments, "duration", LONG_PRESS_MIN_MILLIS, LONG_PRESS_MAX_MILLIS, line);
            case "swipe" -> {
                requireDirection(action, arguments, line, true);
                boundedMillis(arguments, "duration", SWIPE_MIN_MILLIS, SWIPE_MAX_MILLIS, line);
                boundedMillis(arguments, "distance", SWIPE_MIN_PERCENT, SWIPE_MAX_PERCENT, line);
            }
            case "scroll-to" -> {
                requireDirection(action, arguments, line, false);
                boundedMillis(arguments, "max-swipes", 1, SCROLL_TO_MAX_SWIPES_LIMIT, line);
            }
            case "press-key" -> {
                String key = arguments.get("key");
                if (key == null || !PRESS_KEYS.contains(key)) {
                    throw error(line, "press-key requires key= one of " + String.join(", ", new java.util.TreeSet<>(PRESS_KEYS)));
                }
            }
            default -> { }
        }
    }

    private static void requireDirection(String action, Map<String, String> arguments, int line, boolean required) {
        String direction = arguments.get("direction");
        if (direction == null && !required) return;
        if (direction == null || !DIRECTIONS.contains(direction)) {
            throw error(line, action + " direction must be up, down, left, or right");
        }
    }

    /** Validates an optional numeric argument against inclusive bounds; values with `${...}` are checked at run time. */
    private static void boundedMillis(Map<String, String> arguments, String key, long min, long max, int line) {
        String raw = arguments.get(key);
        if (raw == null || raw.contains("${")) return;
        long value = parsePositiveLong(raw.strip(), line, key);
        if (value < min || value > max) throw error(line, key + " must be between " + min + " and " + max);
    }

    private static boolean parseBoolean(String value, int line, String name) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw error(line, name + " must be true or false");
        };
    }

    private static void parseStatuses(String value, int line) {
        for (String item : value.split(",")) {
            long status = parsePositiveLong(item.strip(), line, "expect-status");
            if (status < 100 || status > 599) throw error(line, "expect-status must contain HTTP status codes");
        }
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

    static List<String> tokenize(String line, int lineNumber) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (escaping) {
                current.append(character);
                escaping = false;
            } else if (character == '\\' && quote != 0) {
                escaping = true;
            } else if (quote != 0 && character == quote) {
                quote = 0;
            } else if (quote == 0 && (character == '"'
                || (character == '\'' && (current.isEmpty()
                    || current.charAt(current.length() - 1) == '=')))) {
                quote = character;
            } else if (Character.isWhitespace(character) && quote == 0) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (quote != 0 || escaping) throw error(lineNumber, "unterminated quoted value");
        if (!current.isEmpty()) result.add(current.toString());
        if (result.isEmpty()) throw error(lineNumber, "empty statement");
        return result;
    }

    private static String remainder(List<String> tokens, int line, String keyword) {
        if (tokens.size() < 2) throw error(line, keyword + " requires a value");
        return String.join(" ", tokens.subList(1, tokens.size()));
    }

    private static String singleValue(List<String> tokens, int line, String keyword) {
        if (tokens.size() != 2) throw error(line, keyword + " requires exactly one value");
        return tokens.get(1);
    }

    static long parsePositiveLong(String value, int line, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw error(line, name + " must be positive");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw error(line, name + " must be a number");
        }
    }

    private static ParseError error(int line, String message) {
        return new ParseError(line, message);
    }
}
