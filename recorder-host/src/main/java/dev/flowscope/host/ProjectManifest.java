package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

/**
 * `flowscope.json` — a project's marker and configuration.
 *
 * <p>Everything under {@code defaults} is an authoring and run default: it pre-fills a new
 * recording's header and supplies the CLI's device/report defaults. Nothing in this file can
 * influence a replay. Each `.flow` keeps its own full `package`/`mode`/`timeout` header and runs
 * with no project present, so the checked-in test plus the runner remains the complete runtime
 * input (CLAUDE.md, product contract).
 */
public record ProjectManifest(
    int schemaVersion,
    String name,
    String flowsDir,
    String reportsDir,
    Defaults defaults,
    String defaultSuite
) {
    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_FLOWS_DIR = "flows";
    public static final String DEFAULT_REPORTS_DIR = "reports";

    private static final Set<String> TOP_LEVEL_KEYS =
        Set.of("schemaVersion", "name", "flowsDir", "reportsDir", "defaults", "suite");
    private static final Set<String> DEFAULTS_KEYS =
        Set.of("package", "mode", "timeout", "serial");

    /** Authoring and run defaults. Every field is optional; null means "not configured". */
    public record Defaults(String packageName, CaptureMode mode, Long timeoutMillis, String serial) {
        public static final Defaults NONE = new Defaults(null, null, null, null);
    }

    public ProjectManifest {
        if (defaults == null) defaults = Defaults.NONE;
    }

    /** An invalid manifest. Mirrors {@link FlowTestParser.ParseError}'s shape for the IDE. */
    public static final class ManifestError extends IllegalArgumentException {
        private final String origin;
        private final String detail;

        ManifestError(String origin, String detail) {
            super(origin + ": " + detail);
            this.origin = origin;
            this.detail = detail;
        }

        public String origin() {
            return origin;
        }

        public String detail() {
            return detail;
        }
    }

    public static ProjectManifest defaults(String name) {
        return new ProjectManifest(SCHEMA_VERSION, name, DEFAULT_FLOWS_DIR, DEFAULT_REPORTS_DIR,
            Defaults.NONE, null);
    }

    public static ProjectManifest read(Path manifestPath) throws IOException {
        String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
        return parse(json, manifestPath.getFileName().toString());
    }

    public static ProjectManifest parse(String json, String origin) {
        Map<String, Object> root;
        try {
            root = Json.parseObject(json);
            Json.rejectUnknownKeys(root, TOP_LEVEL_KEYS, "");
        } catch (IllegalArgumentException invalid) {
            throw new ManifestError(origin, invalid.getMessage());
        }

        try {
            Long version = Json.optionalInteger(root, "schemaVersion", "");
            if (version == null) throw new IllegalArgumentException("schemaVersion is required");
            if (version != SCHEMA_VERSION) {
                throw new IllegalArgumentException(version > SCHEMA_VERSION
                    ? "schemaVersion " + version + " is newer than this FlowScope build"
                    : "schemaVersion must be " + SCHEMA_VERSION);
            }

            String name = Json.requireString(root, "name", "");
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");

            String flowsDir = requireRelativeDirectory(root, "flowsDir", DEFAULT_FLOWS_DIR);
            String reportsDir = requireRelativeDirectory(root, "reportsDir", DEFAULT_REPORTS_DIR);
            String suite = Json.optionalString(root, "suite", "");
            if (suite != null && suite.isBlank()) {
                throw new IllegalArgumentException("suite must not be blank");
            }

            return new ProjectManifest(SCHEMA_VERSION, name, flowsDir, reportsDir,
                parseDefaults(root), suite);
        } catch (IllegalArgumentException invalid) {
            throw new ManifestError(origin, invalid.getMessage());
        }
    }

    private static Defaults parseDefaults(Map<String, Object> root) {
        Map<String, Object> raw = Json.optionalObject(root, "defaults", "");
        if (raw == null) return Defaults.NONE;
        Json.rejectUnknownKeys(raw, DEFAULTS_KEYS, "defaults");

        String packageName = Json.optionalString(raw, "package", "defaults");
        if (packageName != null && packageName.isBlank()) {
            throw new IllegalArgumentException("defaults.package must not be blank");
        }
        String modeName = Json.optionalString(raw, "mode", "defaults");
        CaptureMode mode;
        try {
            mode = modeName == null ? null : CaptureMode.parse(modeName);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("defaults.mode: " + unknown.getMessage());
        }
        Long timeout = Json.optionalInteger(raw, "timeout", "defaults");
        if (timeout != null && timeout <= 0) {
            throw new IllegalArgumentException("defaults.timeout must be positive");
        }
        String serial = Json.optionalString(raw, "serial", "defaults");
        if (serial != null && serial.isBlank()) {
            throw new IllegalArgumentException("defaults.serial must not be blank");
        }
        return new Defaults(packageName, mode, timeout, serial);
    }

    /**
     * Directory fields must stay inside the project: an absolute or `..`-bearing value would let a
     * checked-in manifest redirect reads and writes anywhere on the machine that runs it.
     */
    private static String requireRelativeDirectory(
        Map<String, Object> root, String key, String fallback) {
        String value = Json.optionalString(root, key, "");
        if (value == null) return fallback;
        if (value.isBlank()) throw new IllegalArgumentException(key + " must not be blank");
        if (value.contains("\\")) {
            throw new IllegalArgumentException(key + " must use forward slashes");
        }
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(key + " must be relative to the project root");
        }
        for (String segment : value.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException(key + " must not contain '..'");
            }
        }
        return value;
    }

    /** Fixed key order, so an edit from the IDE produces a minimal, reviewable diff. */
    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"schemaVersion\": ").append(schemaVersion).append(",\n");
        out.append("  \"name\": ").append(Json.quote(name)).append(",\n");
        out.append("  \"flowsDir\": ").append(Json.quote(flowsDir)).append(",\n");
        out.append("  \"reportsDir\": ").append(Json.quote(reportsDir)).append(",\n");
        out.append("  \"defaults\": {\n");
        out.append("    \"package\": ").append(Json.quote(defaults.packageName())).append(",\n");
        out.append("    \"mode\": ")
            .append(defaults.mode() == null ? "null" : Json.quote(defaults.mode().cliName()))
            .append(",\n");
        out.append("    \"timeout\": ")
            .append(defaults.timeoutMillis() == null ? "null" : defaults.timeoutMillis())
            .append(",\n");
        out.append("    \"serial\": ").append(Json.quote(defaults.serial())).append("\n");
        out.append("  },\n");
        out.append("  \"suite\": ").append(Json.quote(defaultSuite)).append("\n");
        out.append("}\n");
        return out.toString();
    }

    /** Atomic: the IDE writes this file while the user may have it open in an editor. */
    public static void write(Path manifestPath, ProjectManifest manifest) throws IOException {
        Path parent = manifestPath.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = parent.resolve(manifestPath.getFileName() + ".tmp");
        Files.writeString(temporary, manifest.toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, manifestPath,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(temporary, manifestPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
