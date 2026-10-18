package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Reads an APK application id before installation so package-scoped tests cannot install another app. */
interface ApkInspector {
    String packageName(Path apk) throws Exception;

    static ApkInspector platform() { return new Platform(); }

    final class Platform implements ApkInspector {
        @Override public String packageName(Path apk) throws Exception {
            List<Path> candidates = candidates(System.getenv());
            IOException last = null;
            for (Path tool : candidates) {
                try {
                    Process process = new ProcessBuilder(tool.toString(), "manifest", "application-id",
                        apk.toString()).start();
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                    String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                    if (process.waitFor() == 0 && output.matches("[A-Za-z][A-Za-z0-9_.]+")) return output;
                    last = new IOException(error.isBlank() ? "apkanalyzer could not inspect the APK" : error);
                } catch (IOException unavailable) { last = unavailable; }
            }
            throw new IOException("APK package verification requires Android SDK apkanalyzer"
                + (last == null || last.getMessage() == null ? "" : ": " + last.getMessage()));
        }

        static List<Path> candidates(Map<String, String> environment) {
            List<Path> result = new ArrayList<>();
            for (String key : List.of("ANDROID_SDK_ROOT", "ANDROID_HOME")) {
                String raw = environment.get(key);
                if (raw != null && !raw.isBlank()) addSdkCandidates(result, Path.of(raw));
            }
            String user = System.getProperty("user.home", "");
            if (!user.isBlank()) addSdkCandidates(result, Path.of(user, "Library", "Android", "sdk"));
            result.add(Path.of(isWindows() ? "apkanalyzer.bat" : "apkanalyzer"));
            return result.stream().distinct().toList();
        }

        private static void addSdkCandidates(List<Path> output, Path sdk) {
            String executable = isWindows() ? "apkanalyzer.bat" : "apkanalyzer";
            Path tools = sdk.resolve("cmdline-tools");
            if (Files.isDirectory(tools)) {
                try (var versions = Files.list(tools)) {
                    versions.filter(Files::isDirectory).sorted(Comparator.reverseOrder())
                        .map(path -> path.resolve("bin").resolve(executable)).filter(Files::isRegularFile)
                        .forEach(output::add);
                } catch (IOException ignored) {}
            }
            Path legacy = sdk.resolve("tools").resolve("bin").resolve(executable);
            if (Files.isRegularFile(legacy)) output.add(legacy);
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        }
    }
}
