package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Starts and owns a scrcpy process for one FlowScope device mirror. */
public final class ScrcpyClient {
    private final String executable;

    public ScrcpyClient() {
        executable = resolveExecutable();
    }

    ScrcpyClient(String executable) {
        this.executable = executable;
    }

    public boolean available() {
        return executable != null;
    }

    public String executable() {
        return executable;
    }

    public String versionSummary() {
        if (!available()) return "not installed";
        try {
            Process process = new ProcessBuilder(executable, "--version")
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "installed at " + executable;
            }
            return output.lines().findFirst().orElse("scrcpy") + " (" + executable + ")";
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return "installed at " + executable;
        }
    }

    public String version() {
        if (!available()) return null;
        try {
            Process process = new ProcessBuilder(executable, "--version").redirectErrorStream(true).start();
            String firstLine = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .lines().findFirst().orElse("");
            process.waitFor(5, TimeUnit.SECONDS);
            String[] parts = firstLine.split("\\s+");
            return parts.length >= 2 ? parts[1] : null;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    public Path serverPath() {
        String explicit = System.getenv("SCRCPY_SERVER_PATH");
        if (explicit != null && !explicit.isBlank() && Files.isRegularFile(Path.of(explicit))) {
            return Path.of(explicit);
        }
        if (!available()) return null;
        Path executablePath = Path.of(executable).toAbsolutePath().normalize();
        List<Path> candidates = List.of(
            executablePath.getParent().resolve("scrcpy-server"),
            executablePath.getParent().resolve("../share/scrcpy/scrcpy-server").normalize(),
            Path.of("/opt/homebrew/share/scrcpy/scrcpy-server"),
            Path.of("/usr/local/share/scrcpy/scrcpy-server")
        );
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    public Session start(String serial, boolean readOnly) throws IOException {
        if (!available()) {
            throw new IOException("scrcpy was not found. Install it or set the SCRCPY environment variable.");
        }
        Process process = new ProcessBuilder(command(executable, serial, readOnly))
            .inheritIO()
            .start();
        return new Session(process);
    }

    static List<String> command(String executable, String serial, boolean readOnly) {
        if (serial == null || serial.isBlank()) throw new IllegalArgumentException("Device serial is required");
        List<String> command = new ArrayList<>(List.of(
            executable,
            "-s", serial,
            "--window-title=FlowScope - " + serial,
            "--max-size=1080",
            "--max-fps=60",
            "--no-audio",
            "--stay-awake"
        ));
        if (readOnly) command.add("--no-control");
        return List.copyOf(command);
    }

    private static String resolveExecutable() {
        String explicit = System.getenv("SCRCPY");
        if (explicit != null && !explicit.isBlank()) return explicit;

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String fileName = windows ? "scrcpy.exe" : "scrcpy";
        Set<Path> candidates = new LinkedHashSet<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                if (!directory.isBlank()) candidates.add(Path.of(directory, fileName));
            }
        }
        if (windows) {
            addWindowsCandidate(candidates, System.getenv("LOCALAPPDATA"), "scrcpy", fileName);
            addWindowsCandidate(candidates, System.getenv("ProgramFiles"), "scrcpy", fileName);
            candidates.add(Path.of(fileName).toAbsolutePath());
        } else {
            candidates.add(Path.of("/opt/homebrew/bin/scrcpy"));
            candidates.add(Path.of("/usr/local/bin/scrcpy"));
            candidates.add(Path.of("/usr/bin/scrcpy"));
        }
        return candidates.stream()
            .filter(Files::isRegularFile)
            .filter(pathCandidate -> windows || Files.isExecutable(pathCandidate))
            .map(Path::toString)
            .findFirst()
            .orElse(null);
    }

    private static void addWindowsCandidate(Set<Path> candidates, String root, String directory,
        String fileName) {
        if (root != null && !root.isBlank()) candidates.add(Path.of(root, directory, fileName));
    }

    public static final class Session implements AutoCloseable {
        private final Process process;

        private Session(Process process) {
            this.process = process;
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public int waitFor() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public void close() {
            if (!process.isAlive()) return;
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
