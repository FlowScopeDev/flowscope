package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Project default discovery and explicit secret editing. Never returns source values in errors. */
public final class ProjectSecrets {
    private ProjectSecrets() {}
    public static Path defaultFile(FlowProject project) { return project.flowsDir().resolve(".secrets"); }

    public static Path resolve(FlowProject project, Path explicit) throws IOException {
        if (explicit != null) return explicit;
        if (project == null) return null;
        Path file = defaultFile(project);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!file.toRealPath().startsWith(project.flowsDir().toRealPath())) {
            throw new IllegalArgumentException("Project secrets file escapes the flows directory");
        }
        // Do not silently fall back when a default exists but cannot be used.
        SecretFile.load(file, Map.of());
        return file;
    }

    public record Document(String json, String revision) {}

    public static List<String> names(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        return SecretFile.load(file, Map.of()).keySet().stream().sorted().toList();
    }

    public static Document read(Path file) throws IOException {
        Map<String,String> entries = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            ? SecretFile.load(file, Map.of()) : Map.of();
        StringJoiner json = new StringJoiner(",\n", "{\n", "\n}");
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            json.add("  " + Json.quote(entry.getKey()) + ": " + Json.quote(entry.getValue())));
        return new Document(json.toString(), revision(file));
    }

    public static void save(Path file, String json, String expectedRevision, Path projectRoot) throws IOException {
        String dotenv = dotenv(json); // Validate all input before touching any file.
        Path target = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("Choose the real secrets file before editing a symlink");
        if (!Objects.equals(revision(target), expectedRevision)) {
            throw new IllegalArgumentException("Secrets changed on disk; hide and reopen the editor before saving");
        }
        if (Files.exists(target)) SecretFile.load(target, Map.of());
        Path root = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        if (root != null && target.startsWith(root)) {
            Path ancestor = target.getParent();
            while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
            if (!ancestor.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("Project secrets folder escapes the project");
            }
        }
        Files.createDirectories(target.getParent());
        if (projectRoot != null && target.startsWith(projectRoot.toAbsolutePath().normalize())) {
            Path ignore = projectRoot.resolve(".gitignore");
            if (Files.isSymbolicLink(ignore)) throw new IllegalArgumentException("Project .gitignore must not be a symlink");
            String line = "/" + projectRoot.toAbsolutePath().normalize().relativize(target).toString().replace('\\', '/');
            String old = Files.exists(ignore) ? Files.readString(ignore) : "";
            String merged = ProjectScaffold.mergeGitignore(old, List.of(line, line.substring(0, line.lastIndexOf('/') + 1) + ".secrets-*.tmp"));
            if (!merged.equals(old)) Files.writeString(ignore, merged);
        }
        Path staging;
        try {
            staging = Files.createTempFile(target.getParent(), ".secrets-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException unsupported) {
            staging = Files.createTempFile(target.getParent(), ".secrets-", ".tmp");
        }
        try {
            Files.writeString(staging, dotenv, StandardCharsets.UTF_8);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staging); }
    }

    private static String revision(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return "missing";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static String dotenv(String json) {
        Map<String,Object> entries;
        try { entries = Json.parseObject(json); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Enter a JSON object with unique secret names and string values"); }
        StringBuilder out = new StringBuilder("# Local FlowScope secrets — do not commit\n");
        for (var entry : entries.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") || !(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException("Secret names must be identifiers and every JSON value must be a string");
            }
            if (value.contains("${") || value.chars().anyMatch(c -> c < 32 || c == 127)) {
                throw new IllegalArgumentException("Secret values cannot contain interpolation or control characters");
            }
            String encoded;
            if (!value.contains("'")) encoded = "'" + value + "'";
            else if (!value.contains("\"")) encoded = "\"" + value + "\"";
            else if (!value.contains("#") && value.chars().noneMatch(Character::isWhitespace)
                && !value.startsWith("'") && !value.startsWith("\"")) encoded = value;
            else throw new IllegalArgumentException("A value cannot be represented in dotenv; avoid mixing both quote styles with whitespace");
            out.append(entry.getKey()).append('=').append(encoded).append('\n');
        }
        return out.toString();
    }
}
