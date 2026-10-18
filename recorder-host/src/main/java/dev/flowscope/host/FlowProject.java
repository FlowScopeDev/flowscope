package dev.flowscope.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A FlowScope project: a directory marked by `flowscope.json` holding a project's configuration,
 * its `.flow` tests, and its `.suite` regression suites.
 *
 * <p>A project is an authoring and organisation unit, never a runtime dependency: see
 * {@link ProjectManifest}.
 */
public record FlowProject(Path root, ProjectManifest manifest) {
    public static final String MANIFEST_NAME = "flowscope.json";
    public static final String TESTS_DIRECTORY = "tests";
    public static final String SUITES_DIRECTORY = "suites";
    public static final String TEST_EXTENSION = ".flow";
    public static final String SUITE_EXTENSION = ".suite";

    /** A pathological symlink loop must not hang discovery. No real tree is this deep. */
    private static final int MAX_DISCOVERY_DEPTH = 64;
    private static final int MAX_WALK_DEPTH = 8;

    public FlowProject {
        root = root.toAbsolutePath().normalize();
    }

    public Path manifestPath() {
        return root.resolve(MANIFEST_NAME);
    }

    public Path flowsDir() {
        return resolveConfigured(manifest.flowsDir());
    }

    public Path testsDir() {
        return flowsDir().resolve(TESTS_DIRECTORY);
    }

    public Path suitesDir() {
        return flowsDir().resolve(SUITES_DIRECTORY);
    }

    public Path reportsDir() {
        return resolveConfigured(manifest.reportsDir());
    }

    private Path resolveConfigured(String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes the project: " + relative);
        }
        return resolved;
    }

    /**
     * Resolves a reference written inside a checked-in `.suite`. Strict on purpose: a suite is
     * untrusted input relative to whoever runs it, and a reference that only resolves on the
     * author's machine is a broken suite, not a convenience.
     */
    public Path resolveSuiteEntry(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("empty test reference");
        }
        if (!reference.endsWith(TEST_EXTENSION)) {
            throw new IllegalArgumentException("test reference must end in " + TEST_EXTENSION
                + ": " + reference);
        }
        Path resolved = requireInside(flowsDir(), reference);
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("test not found: " + reference);
        }
        return resolved;
    }

    /**
     * Resolves a human-typed CLI argument. Forgiving on purpose: `test run login` should find
     * `flows/tests/login.flow`. Absolute paths are accepted as given.
     */
    public Path resolveTestArgument(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("empty test reference");
        }
        Path direct = Path.of(reference);
        if (direct.isAbsolute()) {
            if (Files.isRegularFile(direct)) return direct.normalize();
            throw new IllegalArgumentException("Test not found: " + reference);
        }
        String withExtension =
            reference.endsWith(TEST_EXTENSION) ? reference : reference + TEST_EXTENSION;
        for (Path base : List.of(testsDir(), flowsDir(), root)) {
            for (String candidate : List.of(withExtension, reference)) {
                Path resolved = base.resolve(candidate).normalize();
                if (resolved.startsWith(root) && Files.isRegularFile(resolved)) return resolved;
            }
        }
        throw new IllegalArgumentException("Test not found: " + reference
            + " · searched " + relativize(testsDir()) + " in " + root);
    }

    /** Resolves a suite argument through the same ladder, against `flows/suites`. */
    public Path resolveSuiteArgument(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("empty suite reference");
        }
        Path direct = Path.of(reference);
        if (direct.isAbsolute()) {
            if (Files.isRegularFile(direct)) return direct.normalize();
            throw new IllegalArgumentException("Suite not found: " + reference);
        }
        String withExtension =
            reference.endsWith(SUITE_EXTENSION) ? reference : reference + SUITE_EXTENSION;
        for (Path base : List.of(suitesDir(), flowsDir(), root)) {
            for (String candidate : List.of(withExtension, reference)) {
                Path resolved = base.resolve(candidate).normalize();
                if (resolved.startsWith(root) && Files.isRegularFile(resolved)) return resolved;
            }
        }
        throw new IllegalArgumentException("Suite not found: " + reference
            + " · searched " + relativize(suitesDir()) + " in " + root);
    }

    private Path requireInside(Path base, String reference) {
        if (reference.contains("\\")) {
            throw new IllegalArgumentException(
                "test reference must use forward slashes: " + reference);
        }
        Path candidate = Path.of(reference);
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException("test reference must be relative: " + reference);
        }
        Path resolved = base.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes the project: " + reference);
        }
        return resolved;
    }

    public List<Path> listTests() throws IOException {
        return list(testsDir(), TEST_EXTENSION);
    }

    public List<Path> listSuites() throws IOException {
        return list(suitesDir(), SUITE_EXTENSION);
    }

    private List<Path> list(Path directory, String extension) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> walk = Files.walk(directory, MAX_WALK_DEPTH)) {
            return walk
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> path.getFileName().toString().endsWith(extension))
                .filter(path -> !isHidden(directory, path))
                .sorted(Comparator.comparing(this::relativize))
                .toList();
        }
    }

    private static boolean isHidden(Path base, Path path) {
        Path relative = base.relativize(path);
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) return true;
        }
        return false;
    }

    /** Project-relative, forward-slash. Stable across platforms, so reports stay comparable. */
    public String relativize(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path relative = absolute.startsWith(root) ? root.relativize(absolute) : absolute;
        return relative.toString().replace('\\', '/');
    }

    public static FlowProject open(Path root) throws IOException {
        Path directory = root.toAbsolutePath().normalize();
        Path manifestPath = directory.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalArgumentException(
                "No " + MANIFEST_NAME + " in " + directory + ". Run: flowscope project init");
        }
        return new FlowProject(directory, ProjectManifest.read(manifestPath));
    }

    /** Walks up from {@code start} looking for the marker. The first hit wins. */
    public static Optional<FlowProject> discover(Path start) throws IOException {
        Path directory = start.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            directory = directory.getParent();
        }
        for (int depth = 0; directory != null && depth < MAX_DISCOVERY_DEPTH; depth++) {
            if (Files.isRegularFile(directory.resolve(MANIFEST_NAME))) {
                return Optional.of(open(directory));
            }
            directory = directory.getParent();
        }
        return Optional.empty();
    }

    public static FlowProject require(Path start) throws IOException {
        return discover(start).orElseThrow(() -> new IllegalArgumentException(
            "No FlowScope project found in " + start.toAbsolutePath().normalize()
                + " or any parent directory. Run: flowscope project init"));
    }
}
