package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the on-disk shape of a new project. `project init` is designed to run inside an existing
 * application repository, so it only ever adds: an existing `.gitignore` is appended to, never
 * rewritten.
 */
public final class ProjectScaffold {
    private ProjectScaffold() {}

    public static FlowProject initialize(Path root, ProjectManifest manifest) throws IOException {
        Path directory = root.toAbsolutePath().normalize();
        Path manifestPath = directory.resolve(FlowProject.MANIFEST_NAME);
        if (Files.exists(manifestPath)) {
            throw new IllegalArgumentException("Project already initialised: " + manifestPath);
        }
        Files.createDirectories(directory);

        FlowProject project = new FlowProject(directory, manifest);
        ProjectManifest.write(manifestPath, manifest);

        // Git does not track empty directories; without a placeholder the layout vanishes on clone.
        for (Path dir : List.of(project.testsDir(), project.suitesDir())) {
            Files.createDirectories(dir);
            Path keep = dir.resolve(".gitkeep");
            if (!Files.exists(keep)) Files.writeString(keep, "", StandardCharsets.UTF_8);
        }

        // `reports/` is deliberately not created: the first run creates it, and gitignoring a
        // directory that does not exist yet is fine.
        writeGitignore(directory.resolve(".gitignore"));
        return project;
    }

    private static void writeGitignore(Path gitignore) throws IOException {
        String existing = Files.exists(gitignore)
            ? Files.readString(gitignore, StandardCharsets.UTF_8)
            : "";
        String merged = mergeGitignore(existing, gitignoreLines());
        if (!merged.equals(existing)) {
            Files.writeString(gitignore, merged, StandardCharsets.UTF_8);
        }
    }

    /**
     * Run output holds failure evidence and `step screenshot` images, so it carries visible screen
     * content and must stay out of Git (CLAUDE.md).
     */
    static List<String> gitignoreLines() {
        return List.of("# FlowScope", "reports/", "sessions/");
    }

    /** Appends only the missing lines. Never reorders or rewrites what is already there. */
    static String mergeGitignore(String existing, List<String> required) {
        List<String> present = new ArrayList<>();
        for (String line : existing.split("\n", -1)) present.add(line.strip());

        List<String> missing = new ArrayList<>();
        for (String line : required) {
            if (line.startsWith("#")) continue;
            if (!present.contains(line)) missing.add(line);
        }
        if (missing.isEmpty()) return existing;

        StringBuilder out = new StringBuilder(existing);
        if (!existing.isEmpty() && !existing.endsWith("\n")) out.append('\n');
        if (!existing.isBlank()) out.append('\n');
        for (String line : required) {
            if (line.startsWith("#") || missing.contains(line)) out.append(line).append('\n');
        }
        return out.toString();
    }
}
