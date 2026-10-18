package dev.flowscope.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One option parser for every command. Replaces three hand-rolled loops that disagreed with each
 * other: only `mirror` supported a valueless flag, `record`/`snapshot` silently accepted a repeated
 * flag, and `test validate` ignored trailing arguments entirely — which would have silently
 * swallowed a new `--project`.
 *
 * <p>Error messages are kept byte-identical to the loops this replaces, so the CLI's documented
 * surface does not shift underneath existing scripts.
 */
final class CliOptions {
    private final List<String> positional;
    private final Map<String, String> values;
    private final Set<String> flags;

    private CliOptions(List<String> positional, Map<String, String> values, Set<String> flags) {
        this.positional = List.copyOf(positional);
        this.values = Map.copyOf(values);
        this.flags = Set.copyOf(flags);
    }

    static CliOptions parse(List<String> args, Set<String> valueFlags, Set<String> booleanFlags) {
        List<String> positional = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> flags = new java.util.LinkedHashSet<>();
        boolean optionsEnded = false;

        for (int index = 0; index < args.size(); index++) {
            String token = args.get(index);
            if (optionsEnded) {
                positional.add(token);
            } else if (token.equals("--")) {
                optionsEnded = true;
            } else if (booleanFlags.contains(token)) {
                if (!flags.add(token)) throw repeated(token);
                // Boolean flags take no value.
            } else if (valueFlags.contains(token)) {
                if (++index >= args.size()) {
                    throw new IllegalArgumentException("Missing value for " + token);
                }
                if (values.put(token, args.get(index)) != null) throw repeated(token);
            } else if (token.startsWith("-") && token.length() > 1) {
                throw new IllegalArgumentException("Unknown option: " + token);
            } else {
                positional.add(token);
            }
        }
        return new CliOptions(positional, values, flags);
    }

    private static IllegalArgumentException repeated(String option) {
        return new IllegalArgumentException("Repeated option: " + option);
    }

    List<String> positional() {
        return positional;
    }

    /** The positional at {@code index}, or null when the caller did not supply one. */
    String positional(int index) {
        return index < positional.size() ? positional.get(index) : null;
    }

    void requireNoExtraPositionals(int allowed, String command) {
        if (positional.size() > allowed) {
            throw new IllegalArgumentException(
                "Unexpected argument for " + command + ": " + positional.get(allowed));
        }
    }

    String value(String flag) {
        return values.get(flag);
    }

    String value(String flag, String fallback) {
        return values.getOrDefault(flag, fallback);
    }

    boolean flag(String flag) {
        return flags.contains(flag);
    }

    Path path(String flag, Path fallback) {
        String raw = values.get(flag);
        return raw == null ? fallback : Path.of(raw);
    }

    long positiveLong(String flag, long fallback) {
        String raw = values.get(flag);
        if (raw == null) return fallback;
        long parsed;
        try {
            parsed = Long.parseLong(raw);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(flag + " must be a number");
        }
        if (parsed <= 0) throw new IllegalArgumentException(flag + " must be positive");
        return parsed;
    }

    CaptureMode mode(String flag, CaptureMode fallback) {
        String raw = values.get(flag);
        return raw == null ? fallback : CaptureMode.parse(raw);
    }
}
