package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Launches and stops Android Virtual Devices through the SDK's {@code emulator} binary. FlowScope
 * does not bundle or download it. Only emulators launched here are ever stopped: each launch is
 * recorded in {@link EmulatorRegistry}, and {@link #stop} refuses anything else.
 */
public final class EmulatorClient {
    public static final long DEFAULT_BOOT_TIMEOUT_MS = 180_000;

    private final String executable;
    private final EmulatorRegistry registry;
    private final Path logDirectory;
    private long pollMillis = 1_000;

    public EmulatorClient() {
        this(resolveExecutable(), EmulatorRegistry.defaultRegistry(),
            Path.of(System.getProperty("user.home"), ".flowscope"));
    }

    EmulatorClient(String executable, EmulatorRegistry registry, Path logDirectory) {
        this.executable = executable;
        this.registry = registry;
        this.logDirectory = logDirectory;
    }

    void pollEvery(long millis) {
        this.pollMillis = millis;
    }

    /** Resolved path of the `emulator` binary, or null when the SDK does not have one. */
    public String executable() {
        return executable;
    }

    public boolean available() {
        return executable != null;
    }

    /** What `flowscope doctor` prints: the path, or how to fix it. */
    public String summary() {
        return executable != null ? executable
            : "not installed (install the SDK emulator package, or set EMULATOR / ANDROID_HOME)";
    }

    public List<String> listAvds() throws IOException, InterruptedException {
        requireExecutable();
        Process process = new ProcessBuilder(executable, "-list-avds").start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("emulator -list-avds failed: " + stderr.strip());
        }
        return parseAvds(stdout);
    }

    /** `emulator -list-avds` prints one name per line, sometimes with `INFO |` noise. */
    static List<String> parseAvds(String output) {
        List<String> avds = new ArrayList<>();
        if (output == null) return avds;
        for (String line : output.split("\\R")) {
            String name = line.strip();
            if (name.isEmpty() || name.startsWith("INFO") || name.startsWith("WARNING")
                || name.startsWith("ERROR")) continue;
            avds.add(name);
        }
        return avds;
    }

    /** `adb emu avd name` prints the name, then `OK`. */
    static String parseAvdName(String output) {
        if (output == null) return null;
        for (String line : output.split("\\R")) {
            String name = line.strip();
            if (!name.isEmpty() && !name.equals("OK")) return name;
        }
        return null;
    }

    static List<String> command(String executable, String avd, boolean headless) {
        List<String> command = new ArrayList<>(List.of(executable, "-avd", avd));
        if (headless) command.addAll(List.of("-no-window", "-no-audio", "-no-boot-anim"));
        return List.copyOf(command);
    }

    /** Running emulators, serial to AVD name. Emulators whose name cannot be read are skipped. */
    public Map<String, String> runningEmulators(AdbClient adb) throws IOException, InterruptedException {
        Map<String, String> running = new LinkedHashMap<>();
        for (Device device : adb.devices()) {
            if (!device.serial().startsWith("emulator-") || !device.state().equals("device")) continue;
            String name = adb.avdName(device.serial());
            if (name != null) running.put(device.serial(), name);
        }
        return running;
    }

    /** AVD names FlowScope launched that are still running. */
    public Set<String> ownedAvds() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (EmulatorRegistry.Entry entry : registry.read()) names.add(entry.avd());
        return names;
    }

    /** Launches the AVD detached and records it; returns without waiting for boot. */
    public long start(AdbClient adb, String avd, boolean headless) throws IOException, InterruptedException {
        requireExecutable();
        if (!listAvds().contains(avd)) {
            throw new IllegalArgumentException("Unknown AVD: " + avd + ". Run `flowscope emulators`.");
        }
        if (runningEmulators(adb).containsValue(avd) || registry.find(avd) != null) {
            throw new IllegalStateException("AVD " + avd + " is already running.");
        }
        Files.createDirectories(logDirectory);
        Process process = new ProcessBuilder(command(executable, avd, headless))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(logDirectory.resolve("emulator-" + avd + ".log").toFile()))
            .start();
        process.getOutputStream().close();
        registry.add(new EmulatorRegistry.Entry(avd, process.pid()));
        return process.pid();
    }

    /** Waits for the AVD to appear on ADB and finish booting; returns its serial. */
    public String waitForBoot(AdbClient adb, String avd, long timeoutMillis)
        throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        String serial = null;
        while (System.nanoTime() < deadline) {
            if (serial == null) {
                for (Map.Entry<String, String> entry : runningEmulators(adb).entrySet()) {
                    if (entry.getValue().equals(avd)) serial = entry.getKey();
                }
            }
            if (serial != null && adb.bootCompleted(serial)) return serial;
            Thread.sleep(pollMillis);
        }
        throw new IOException("Timed out after " + timeoutMillis + " ms waiting for " + avd + " to boot.");
    }

    /** Stops an emulator FlowScope launched. Any other serial is refused as invalid input. */
    public void stop(AdbClient adb, String serial) throws IOException, InterruptedException {
        String avd = adb.avdName(serial);
        EmulatorRegistry.Entry owned = avd == null ? null : registry.find(avd);
        if (owned == null) {
            throw new IllegalArgumentException(serial
                + " was not launched by FlowScope; stop it where you started it.");
        }
        adb.run(List.of("-s", serial, "emu", "kill"));
        ProcessHandle handle = ProcessHandle.of(owned.pid()).orElse(null);
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (handle != null && handle.isAlive() && System.nanoTime() < deadline) Thread.sleep(200);
        if (handle != null && handle.isAlive()) handle.destroyForcibly();
        registry.remove(owned.pid());
    }

    private void requireExecutable() {
        if (executable == null) {
            throw new IllegalStateException("emulator binary not found. Install the SDK emulator "
                + "package, or set EMULATOR or ANDROID_HOME / ANDROID_SDK_ROOT.");
        }
    }

    /** `EMULATOR`, then the SDK's emulator directory, then the usual SDK homes, then PATH. */
    static String resolveExecutable() {
        return resolveExecutable(System.getenv("EMULATOR"), System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"), System.getenv("LOCALAPPDATA"),
            System.getenv("PATH"), System.getProperty("user.home"),
            System.getProperty("os.name", ""));
    }

    static String resolveExecutable(String explicit, String androidHome, String sdkRoot,
        String localAppData, String path, String home, String osName) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        String fileName = windows ? "emulator.exe" : "emulator";
        Set<Path> candidates = new LinkedHashSet<>();
        for (String sdk : new String[] {androidHome, sdkRoot}) {
            if (sdk != null && !sdk.isBlank()) candidates.add(Path.of(sdk, "emulator", fileName));
        }
        if (home != null) {
            candidates.add(Path.of(home, "Library", "Android", "sdk", "emulator", fileName));
            candidates.add(Path.of(home, "Android", "Sdk", "emulator", fileName));
        }
        if (windows && localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData, "Android", "Sdk", "emulator", fileName));
        }
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                if (!directory.isBlank()) candidates.add(Path.of(directory, fileName));
            }
        }
        return candidates.stream()
            .filter(Files::isRegularFile)
            .filter(candidate -> windows || Files.isExecutable(candidate))
            .map(Path::toString)
            .findFirst()
            .orElse(null);
    }
}
