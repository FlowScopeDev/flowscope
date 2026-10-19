package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict, non-expanding dotenv reader for local test secrets. */
public final class SecretFile {
    private SecretFile() {}

    public static Map<String, String> load(Path path, Map<String, String> environment)
        throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (path != null) {
            Path file = path.toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Secrets file not found: " + file);
            }
            rejectBroadPosixPermissions(file);
            int lineNumber = 0;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) throw invalid(lineNumber, "export is not supported");
                int equals = line.indexOf('=');
                if (equals <= 0) throw invalid(lineNumber, "expected NAME=value");
                String name = line.substring(0, equals).strip();
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw invalid(lineNumber, "invalid secret name");
                }
                if (values.containsKey(name)) throw invalid(lineNumber, "duplicate secret: " + name);
                values.put(name, parseValue(line.substring(equals + 1).strip(), lineNumber));
            }
        }
        // CI and explicit process configuration always take precedence over a local file.
        environment.forEach((name, value) -> {
            if (values.containsKey(name)) values.put(name, value);
        });
        return Map.copyOf(values);
    }

    private static String parseValue(String raw, int line) {
        if (raw.contains("${")) throw invalid(line, "interpolation is not supported");
        if (raw.isEmpty()) return "";
        char quote = raw.charAt(0);
        if (quote == '\'' || quote == '"') {
            if (raw.length() < 2 || raw.charAt(raw.length() - 1) != quote) {
                throw invalid(line, "unterminated quoted value");
            }
            String value = raw.substring(1, raw.length() - 1);
            if (value.indexOf(quote) >= 0) throw invalid(line, "embedded quote is not supported");
            return value;
        }
        if (raw.indexOf('#') >= 0 || raw.chars().anyMatch(Character::isWhitespace)) {
            throw invalid(line, "unquoted values cannot contain whitespace or comments");
        }
        return raw;
    }

    private static void rejectBroadPosixPermissions(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                || permission.name().startsWith("OTHERS_"))) {
                throw new IllegalArgumentException(
                    "Secrets file must be readable and writable only by its owner: " + file);
            }
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX file systems rely on their native ACL checks.
        }
    }

    private static IllegalArgumentException invalid(int line, String detail) {
        return new IllegalArgumentException("Secrets file line " + line + ": " + detail);
    }
}
