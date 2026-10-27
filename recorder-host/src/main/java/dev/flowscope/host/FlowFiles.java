package dev.flowscope.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** One containment policy for validation, preflight and the actual file read. */
final class FlowFiles {
    private FlowFiles() {}

    static Path resolve(Path sourceDirectory, String raw, String label) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(label + " must name a file");
        Path base = sourceDirectory.toAbsolutePath().normalize();
        String relative = raw;
        if (raw.startsWith("@fixtures/")) {
            base = fixturesDirectory(base);
            relative = raw.substring("@fixtures/".length());
        }
        Path path = Path.of(relative);
        if (relative.isBlank() || path.isAbsolute() || relative.contains("\\")
            || relative.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(label + " must be a relative path");
        }
        for (Path segment : path) if (segment.toString().equals("..")) {
            throw new IllegalArgumentException(label + " must not contain '..'");
        }
        Path resolved = base.resolve(path).normalize();
        try {
            if (!resolved.toRealPath().startsWith(base.toRealPath())) {
                throw new IllegalArgumentException(label + " escapes the test directory through a symbolic link");
            }
            if (!Files.isRegularFile(resolved) || !Files.isReadable(resolved)) {
                throw new IllegalArgumentException(label + " is not a readable file: " + raw);
            }
            return resolved.toRealPath();
        } catch (IOException missing) {
            throw new IllegalArgumentException(label + " file not found or unreadable: " + raw, missing);
        }
    }

    private static Path fixturesDirectory(Path source) {
        try {
            var project = FlowProject.discover(source);
            Path flows = null;
            if (project.isPresent() && source.startsWith(project.get().flowsDir())) {
                flows = project.get().flowsDir();
            } else {
                // A copied flows/tests + flows/fixtures tree remains standalone, without a manifest.
                for (Path parent = source; parent != null; parent = parent.getParent()) {
                    String name = parent.getFileName() == null ? "" : parent.getFileName().toString();
                    if (name.equals("tests") || name.equals("suites")) {
                        flows = parent.getParent();
                        break;
                    }
                }
            }
            if (flows == null) throw new IllegalArgumentException(
                "@fixtures requires a flows/tests or flows/suites tree, or a project flowsDir");
            Path fixtures = flows.resolve("fixtures");
            if (!fixtures.toRealPath().startsWith(flows.toRealPath())) {
                throw new IllegalArgumentException("fixtures directory escapes flowsDir through a symbolic link");
            }
            return fixtures;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Could not resolve @fixtures directory", invalid);
        }
    }

    static void validate(Path source, List<FlowTest.Step> steps) {
        for (var step : steps) {
            for (String key : List.of("body-file", "file")) {
                String raw = step.arguments().get(key);
                if (raw == null || (key.equals("file") && !step.action().equals("install-apk"))) continue;
                // Environment/secret filenames remain supported; runtime preflight resolves those.
                if (raw.contains("${")) continue;
                try {
                    Path file = resolve(source, raw, key);
                    if (key.equals("file") && !file.toString().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                        throw new IllegalArgumentException("artifact file must end with .apk");
                    }
                } catch (IllegalArgumentException invalid) {
                    throw new FlowTestParser.ParseError(step.line(), invalid.getMessage());
                }
            }
        }
    }
}
