package dev.flowscope.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * FlowScope's own version, generated from {@code flowscope.version} in {@code gradle.properties}
 * by the {@code generateVersionResource} task. The CLI and the IDE both read it from here so a bug
 * report can name one build.
 *
 * <p>The resource is absent from a plain-{@code javac} build, which CLAUDE.md requires to keep
 * working; {@link #UNKNOWN} is returned then rather than failing at class-initialisation time.
 */
public final class FlowScopeVersion {
    /** Reported when the generated resource is missing or unreadable. */
    public static final String UNKNOWN = "unknown";

    private static final String RESOURCE = "/dev/flowscope/host/version.properties";
    private static final String VALUE = load();

    private FlowScopeVersion() {}

    /** The running build's version, or {@link #UNKNOWN}. Never null, never empty. */
    public static String current() {
        return VALUE;
    }

    private static String load() {
        try (InputStream stream = FlowScopeVersion.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) return UNKNOWN;
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return UNKNOWN;
        }
    }

    /**
     * Reads the {@code version=} entry out of the generated properties text. Hand-rolled rather
     * than {@link java.util.Properties} so the one value cannot be affected by escape processing,
     * and so this stays testable without a classpath resource.
     */
    static String parse(String text) {
        if (text == null) return UNKNOWN;
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int separator = trimmed.indexOf('=');
            if (separator < 0 || !trimmed.substring(0, separator).strip().equals("version")) continue;
            String value = trimmed.substring(separator + 1).strip();
            if (!value.isEmpty()) return value;
        }
        return UNKNOWN;
    }
}
