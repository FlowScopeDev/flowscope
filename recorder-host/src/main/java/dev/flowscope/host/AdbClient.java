package dev.flowscope.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdbClient {
    private final String executable;

    public AdbClient() {
        this.executable = resolveAdb();
    }

    public String executable() {
        return executable;
    }

    public CommandResult run(List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        int exitCode = process.waitFor();
        return new CommandResult(exitCode,
            new String(stdout, StandardCharsets.UTF_8),
            new String(stderr, StandardCharsets.UTF_8));
    }

    public Process start(List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    public byte[] execOut(String serial, String... deviceCommand)
        throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-s");
        command.add(serial);
        command.add("exec-out");
        command.addAll(List.of(deviceCommand));
        Process process = new ProcessBuilder(command).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        process.getInputStream().transferTo(bytes);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new IOException("ADB command failed: " + error.strip());
        return bytes.toByteArray();
    }

    public Process streamToFile(String serial, Path target, String... deviceCommand) throws IOException {
        return streamToFile(serial, target, false, deviceCommand);
    }

    public Process streamToFile(String serial, Path target, boolean append, String... deviceCommand)
        throws IOException {
        List<String> command = shellCommand(serial, deviceCommand);
        Files.createDirectories(target.getParent());
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.redirectOutput(append
            ? ProcessBuilder.Redirect.appendTo(target.toFile())
            : ProcessBuilder.Redirect.to(target.toFile()));
        return builder.start();
    }

    public Process stream(String serial, String... deviceCommand) throws IOException {
        return new ProcessBuilder(shellCommand(serial, deviceCommand))
            .redirectErrorStream(true)
            .start();
    }

    public List<Device> devices() throws IOException, InterruptedException {
        CommandResult result = run(List.of("devices", "-l"));
        if (result.exitCode() != 0) {
            throw new IOException("Unable to list devices: " + result.stderr().strip());
        }
        return Device.parse(result.stdout());
    }

    /** True once Android reports `sys.boot_completed=1`; false while booting or unreachable. */
    public boolean bootCompleted(String serial) throws IOException, InterruptedException {
        CommandResult result = run(List.of("-s", serial, "shell", "getprop", "sys.boot_completed"));
        return result.exitCode() == 0 && result.stdout().strip().equals("1");
    }

    /** The AVD name behind an emulator serial (`adb emu avd name`), or null if it cannot be read. */
    public String avdName(String serial) throws IOException, InterruptedException {
        CommandResult result = run(List.of("-s", serial, "emu", "avd", "name"));
        if (result.exitCode() != 0) return null;
        return EmulatorClient.parseAvdName(result.stdout());
    }

    /**
     * Installed application IDs on the device, third-party packages first so the list is useful
     * for picking a target under test. Returns an empty list if the device cannot be queried.
     */
    public List<String> installedPackages(String serial) throws IOException, InterruptedException {
        List<String> thirdParty = listPackages(serial, "-3");
        List<String> all = listPackages(serial);
        LinkedHashSet<String> ordered = new LinkedHashSet<>(thirdParty);
        ordered.addAll(all);
        return List.copyOf(ordered);
    }

    private List<String> listPackages(String serial, String... extraFlags) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("-s", serial, "shell", "pm", "list", "packages"));
        command.addAll(List.of(extraFlags));
        CommandResult result = run(command);
        if (result.exitCode() != 0) return List.of();
        return parsePackageList(result.stdout());
    }

    /** Parses {@code pm list packages} output, whose lines look like {@code package:com.example}. */
    static List<String> parsePackageList(String output) {
        if (output == null || output.isBlank()) return List.of();
        List<String> packages = new ArrayList<>();
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            if (!line.startsWith("package:")) continue;
            String id = line.substring("package:".length()).strip();
            // `pm list packages -f` prefixes the APK path and `-U` appends a uid. The package id
            // is what follows the LAST '=' — base-64 APK directory names contain '==' themselves.
            int space = id.indexOf(' ');
            if (space >= 0) id = id.substring(0, space).strip();
            int lastEquals = id.lastIndexOf('=');
            if (lastEquals >= 0) id = id.substring(lastEquals + 1).strip();
            if (!id.isEmpty() && !packages.contains(id)) packages.add(id);
        }
        packages.sort(String::compareTo);
        return List.copyOf(packages);
    }

    /** The package whose activity is currently resumed, or null when it cannot be determined. */
    public String foregroundPackage(String serial) throws IOException, InterruptedException {
        CommandResult activities = run(List.of("-s", serial, "shell", "dumpsys", "activity", "activities"));
        String fromActivities = parseForegroundPackage(activities.stdout());
        if (fromActivities != null) return fromActivities;
        CommandResult window = run(List.of("-s", serial, "shell", "dumpsys", "window", "displays"));
        return parseForegroundPackage(window.stdout());
    }

    /**
     * Pulls the package id out of a `topResumedActivity=`/`mResumedActivity=`/`mCurrentFocus=`
     * line. Those carry `…{hash u0 com.example/.MainActivity t42}`; the id precedes the slash.
     */
    static String parseForegroundPackage(String output) {
        if (output == null) return null;
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            boolean interesting = line.startsWith("topResumedActivity=")
                || line.startsWith("mResumedActivity=")
                || line.startsWith("mCurrentFocus=");
            if (!interesting || line.endsWith("=null")) continue;
            int slash = line.indexOf('/');
            if (slash < 0) continue;
            String head = line.substring(0, slash);
            int space = head.lastIndexOf(' ');
            String candidate = (space < 0 ? head : head.substring(space + 1)).strip();
            if (candidate.contains(".") && !candidate.contains("=")) return candidate;
        }
        return null;
    }

    /** Whether the installed package carries the DEBUGGABLE flag — required for zero-code capture. */
    public boolean isDebuggable(String serial, String packageName) throws IOException, InterruptedException {
        CommandResult result = run(List.of("-s", serial, "shell", "dumpsys", "package", packageName));
        return parseDebuggable(result.stdout());
    }

    static boolean parseDebuggable(String output) {
        if (output == null) return false;
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            if (line.startsWith("flags=[") && line.contains("DEBUGGABLE")) return true;
        }
        return false;
    }

    /** The running pid for a package, or null when it is not running. */
    public String pidOf(String serial, String packageName) throws IOException, InterruptedException {
        CommandResult result = run(List.of("-s", serial, "shell", "pidof", packageName));
        String pid = result.stdout().strip();
        return pid.isEmpty() ? null : pid.split("\\s+")[0];
    }

    /**
     * Whether the debug-only agent has emitted events for this package. The agent tags its Logcat
     * entries `FlowScopeAgent`; matching on the package's own pid avoids crediting one app with
     * another app's agent.
     */
    public boolean hasAgentEvidence(String serial, String packageName)
        throws IOException, InterruptedException {
        String pid = pidOf(serial, packageName);
        if (pid == null) return false;
        CommandResult logs = run(List.of("-s", serial, "logcat", "-d", "-v", "threadtime",
            "-t", "2000", "-s", "FlowScopeAgent:I"));
        return agentSeenForPid(logs.stdout(), pid);
    }

    /** Logcat threadtime lines read `MM-DD HH:MM:SS.mmm  PID  TID I FlowScopeAgent: {…}`. */
    static boolean agentSeenForPid(String logcat, String pid) {
        if (logcat == null || pid == null) return false;
        for (String rawLine : logcat.split("\\R")) {
            String line = rawLine.strip();
            if (!line.contains("FlowScopeAgent")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length > 2 && parts[2].equals(pid)) return true;
        }
        return false;
    }

    /**
     * The display's highest supported refresh rate, used to pick a capture frame rate instead of
     * hard-coding 30. Returns {@link #DEFAULT_REFRESH_HZ} when the device cannot be queried.
     */
    public int displayRefreshHz(String serial) throws IOException, InterruptedException {
        CommandResult result = run(List.of("-s", serial, "shell", "dumpsys", "display"));
        return parseRefreshHz(result.stdout());
    }

    public static final int DEFAULT_REFRESH_HZ = 60;

    /**
     * Reads `supportedRefreshRates [90.0, 60.0]` and falls back to `renderFrameRate 90.0`.
     * Clamped to a sane capture range: below 30 is not worth streaming, above 90 costs more than
     * it shows for UI recording.
     */
    static int parseRefreshHz(String dumpsysDisplay) {
        if (dumpsysDisplay == null || dumpsysDisplay.isBlank()) return DEFAULT_REFRESH_HZ;
        double best = 0;
        Matcher supported = Pattern.compile("[Ss]upportedRefreshRates\\s*=?\\s*\\[([^\\]]*)\\]")
            .matcher(dumpsysDisplay);
        while (supported.find()) {
            for (String piece : supported.group(1).split(",")) {
                try {
                    best = Math.max(best, Double.parseDouble(piece.strip()));
                } catch (NumberFormatException ignored) {
                    // A malformed entry just does not contribute.
                }
            }
        }
        if (best <= 0) {
            Matcher render = Pattern.compile("renderFrameRate\\s+([0-9]+(?:\\.[0-9]+)?)")
                .matcher(dumpsysDisplay);
            if (render.find()) best = Double.parseDouble(render.group(1));
        }
        if (best <= 0) return DEFAULT_REFRESH_HZ;
        return (int) Math.max(30, Math.min(90, Math.round(best)));
    }

    /** The display's physical pixel size as {width, height}, or null when unavailable. */
    public int[] displaySize(String serial) throws IOException, InterruptedException {
        CommandResult result = run(List.of("-s", serial, "shell", "wm", "size"));
        return parseDisplaySize(result.stdout());
    }

    /** `wm size` prints `Physical size: 720x1600`, and an `Override size:` line when one is set. */
    static int[] parseDisplaySize(String output) {
        if (output == null) return null;
        int[] physical = null;
        int[] override = null;
        Matcher matcher = Pattern.compile("(Physical|Override) size:\\s*(\\d+)x(\\d+)").matcher(output);
        while (matcher.find()) {
            int[] size = {Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))};
            if (matcher.group(1).equals("Override")) override = size; else physical = size;
        }
        // An override is what the device actually renders, so it wins.
        return override != null ? override : physical;
    }

    private static String resolveAdb() {
        String explicit = System.getenv("ADB");
        if (explicit != null && !explicit.isBlank()) return explicit;
        String sdk = System.getenv("ANDROID_HOME");
        if (sdk == null || sdk.isBlank()) sdk = System.getenv("ANDROID_SDK_ROOT");
        if (sdk != null && !sdk.isBlank()) {
            Path candidate = Path.of(sdk, "platform-tools", "adb");
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        Path macDefault = Path.of(System.getProperty("user.home"), "Library", "Android", "sdk",
            "platform-tools", "adb");
        if (Files.isExecutable(macDefault)) return macDefault.toString();
        return "adb";
    }

    private List<String> shellCommand(String serial, String... deviceCommand) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-s");
        command.add(serial);
        command.add("shell");
        command.addAll(List.of(deviceCommand));
        return command;
    }

    public record CommandResult(int exitCode, String stdout, String stderr) {}
}
