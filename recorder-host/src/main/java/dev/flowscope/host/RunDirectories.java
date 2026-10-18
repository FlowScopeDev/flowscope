package dev.flowscope.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** One place that names run output directories, so a test run and a suite run agree. */
final class RunDirectories {
    private static final DateTimeFormatter DIRECTORY_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private RunDirectories() {}

    static Path create(Path outputRoot, String label) throws IOException {
        Path root = outputRoot.resolve(stamp() + "-" + sanitize(label)).toAbsolutePath();
        Files.createDirectories(root);
        return root;
    }

    static String stamp() {
        return DIRECTORY_TIME.format(Instant.now());
    }

    static String sanitize(String label) {
        String cleaned = label == null ? "" : label.replaceAll("[^a-zA-Z0-9._-]", "_");
        return cleaned.isBlank() ? "run" : cleaned;
    }
}
