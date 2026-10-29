package dev.flowscope.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Bounded access to existing run metadata for desktop inspection. No runtime values are added. */
public final class RunArtifacts {
    private RunArtifacts() {}

    public static Path containedFile(Path directory, String reference) throws IOException {
        Path relative = Path.of(reference);
        if (relative.isAbsolute() || reference.isBlank() || reference.contains("\\"))
            throw new IOException("Invalid artifact reference");
        for (Path part : relative) if (part.toString().equals("..")) throw new IOException("Invalid artifact reference");
        Path root = directory.toRealPath();
        Path file = root.resolve(relative).toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IOException("Artifact is outside its run folder");
        return file;
    }

    public static Map<String, Object> readObject(Path directory, String reference) throws IOException {
        Path file = containedFile(directory, reference);
        if (Files.size(file) > 16 * 1024 * 1024) throw new IOException("Run metadata is too large to inspect");
        try { return Json.parseObject(Files.readString(file)); }
        catch (IllegalArgumentException invalid) { throw new IOException("Run metadata is incomplete or invalid"); }
    }
}
