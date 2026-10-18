package dev.flowscope.host;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.zip.GZIPOutputStream;

public final class SessionRecorder implements AutoCloseable {
    private static final DateTimeFormatter DIRECTORY_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AdbClient adb;
    private final Device device;
    private final String packageName;
    private final CaptureMode mode;
    private final Path root;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Process logcat;
    private Thread logcatPump;
    private Process agentEvents;
    private Thread agentEventsPump;
    private Process accessibility;
    private Thread accessibilityPump;
    private String accessibilityPid;
    private int checkpointNumber;
    private volatile BiConsumer<String, String> observer = (channel, line) -> {};

    public SessionRecorder(AdbClient adb, Device device, String packageName, Path outputRoot)
        throws IOException {
        this(adb, device, packageName, outputRoot, CaptureMode.INSTRUMENTED);
    }

    public SessionRecorder(AdbClient adb, Device device, String packageName, Path outputRoot,
        CaptureMode mode) throws IOException {
        this.adb = adb;
        this.device = device;
        this.packageName = packageName;
        this.mode = mode;
        String directory = DIRECTORY_TIME.format(startedAt) + "-" + safeName(packageName);
        this.root = outputRoot.resolve(directory).toAbsolutePath();
        Files.createDirectories(root.resolve("checkpoints"));
    }

    public Path root() {
        return root;
    }

    public SessionRecorder observe(BiConsumer<String, String> observer) {
        this.observer = observer == null ? (channel, line) -> {} : observer;
        return this;
    }

    public int checkpointCount() {
        return checkpointNumber;
    }

    public synchronized void recordTap(int x, int y, int displayWidth, int displayHeight)
        throws IOException {
        recordTap(x, y, displayWidth, displayHeight, null);
    }

    public synchronized void recordTap(int x, int y, int displayWidth, int displayHeight,
        UiSelectorResolver.Resolution target) throws IOException {
        recordHostInteraction("ui.tap", "{\"x\":" + x + ",\"y\":" + y
            + ",\"displayWidth\":" + displayWidth + ",\"displayHeight\":" + displayHeight
            + ",\"target\":" + (target == null ? "null" : target.toJson()) + "}");
    }

    public synchronized UiSelectorResolver.Resolution resolveTap(int x, int y)
        throws IOException, InterruptedException {
        boolean resumeAccessibility = accessibility != null;
        if (resumeAccessibility) stopAccessibility();
        try {
            String remote = "/data/local/tmp/flowscope-resolve-window.xml";
            AdbClient.CommandResult dump = adb.run(List.of("-s", device.serial(), "shell",
                "uiautomator", "dump", remote));
            if (dump.exitCode() != 0) {
                throw new IOException("UI hierarchy failed: " + (dump.stdout() + dump.stderr()).strip());
            }
            try {
                return UiSelectorResolver.resolve(adb.execOut(device.serial(), "cat", remote), x, y);
            } catch (Exception parseFailure) {
                throw new IOException("Could not resolve the tapped UI node", parseFailure);
            }
        } finally {
            if (resumeAccessibility && !closed.get()) startAccessibilityBestEffort(true);
        }
    }

    public synchronized void recordSwipe(int startX, int startY, int endX, int endY,
        int durationMillis, int displayWidth, int displayHeight) throws IOException {
        recordHostInteraction("ui.swipe", "{\"startX\":" + startX + ",\"startY\":" + startY
            + ",\"endX\":" + endX + ",\"endY\":" + endY
            + ",\"durationMillis\":" + durationMillis
            + ",\"direction\":" + Json.quote(InteractionSteps.swipeDirection(startX, startY, endX, endY))
            + ",\"displayWidth\":" + displayWidth + ",\"displayHeight\":" + displayHeight + "}");
    }

    private void recordHostInteraction(String kind, String payload) throws IOException {
        if (closed.get()) throw new IOException("Recording session is already closed");
        String event = "{\"schemaVersion\":1,\"elapsedRealtimeNanos\":"
            + (System.nanoTime() - startedNanos)
            + ",\"wallTime\":" + Json.quote(Instant.now().toString())
            + ",\"source\":\"desktop-live-device\",\"kind\":" + Json.quote(kind)
            + ",\"payload\":" + payload + "}";
        Files.writeString(root.resolve("host-interactions.jsonl"), event + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        observer.accept("host", event);
    }

    public void start() throws IOException, InterruptedException {
        writeManifest(null);
        String packagePid = findPackagePid();
        startFilteredLogcat(packagePid);
        if (mode == CaptureMode.INSTRUMENTED) startAgentEvents(packagePid);
        captureCheckpoint("recording-started");
        if (mode == CaptureMode.ZERO_CODE) startAccessibilityBestEffort(false);
    }

    public Path recordInteractively() throws IOException, InterruptedException {
        start();

        System.out.println("Recording " + packageName + " on " + device.serial());
        System.out.println("Press Enter for a checkpoint; type q then Enter to stop.");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = input.readLine()) != null) {
            if (line.strip().equalsIgnoreCase("q")) break;
            captureCheckpoint("manual");
            System.out.println("Captured checkpoint " + (checkpointNumber - 1));
        }
        captureCheckpoint("recording-stopped");
        close();
        return root;
    }

    public void captureCheckpoint(String reason) throws IOException, InterruptedException {
        boolean resumeAccessibility = accessibility != null;
        if (resumeAccessibility) {
            stopAccessibility();
        }
        try {
            captureCheckpointEvidence(reason);
        } finally {
            if (resumeAccessibility && !closed.get()) startAccessibilityBestEffort(true);
        }
    }

    private void captureCheckpointEvidence(String reason) throws IOException, InterruptedException {
        int number = checkpointNumber++;
        Path directory = root.resolve("checkpoints").resolve(String.format("%04d", number));
        Files.createDirectories(directory);

        Files.write(directory.resolve("screenshot.png"),
            adb.execOut(device.serial(), "screencap", "-p"));

        AdbClient.CommandResult dump = adb.run(List.of("-s", device.serial(), "shell",
            "uiautomator", "dump", "/data/local/tmp/ui-flow-window.xml"));
        if (dump.exitCode() == 0) {
            Files.write(directory.resolve("hierarchy.xml"),
                adb.execOut(device.serial(), "cat", "/data/local/tmp/ui-flow-window.xml"));
        } else {
            Files.writeString(directory.resolve("hierarchy-error.txt"),
                "exitCode=" + dump.exitCode() + "\n" + dump.stdout() + dump.stderr());
        }

        Files.write(directory.resolve("window.txt"), adb.execOut(device.serial(), "dumpsys", "window"));
        long elapsedNanos = System.nanoTime() - startedNanos;
        String metadata = "{\n" +
            "  \"sequence\": " + number + ",\n" +
            "  \"reason\": " + Json.quote(reason) + ",\n" +
            "  \"wallTime\": " + Json.quote(Instant.now().toString()) + ",\n" +
            "  \"elapsedNanos\": " + elapsedNanos + "\n" +
            "}\n";
        Files.writeString(directory.resolve("metadata.json"), metadata, StandardCharsets.UTF_8);
    }

    private void startFilteredLogcat(String packagePid) throws IOException {
        Path target = root.resolve("logcat.txt.gz");
        if (packagePid == null) {
            try (GZIPOutputStream ignored = new GZIPOutputStream(Files.newOutputStream(target))) {}
            Files.writeString(root.resolve("logcat-error.txt"),
                "Package process is not running; Logcat was not captured.\n", StandardCharsets.UTF_8);
            return;
        }
        String[] command = new String[] {
            "logcat", "--pid=" + packagePid, "-T", "1", "-v", "epoch", "*:W"};
        logcat = adb.stream(device.serial(), command);
        logcatPump = Thread.ofVirtual().name("flowscope-logcat-compressor").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(logcat.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream file = Files.newOutputStream(target);
                 GZIPOutputStream gzip = new GZIPOutputStream(file);
                 BufferedWriter writer = new BufferedWriter(
                     new java.io.OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    observer.accept("logs", line);
                }
            } catch (IOException ignored) {
                // The ADB stream closes during normal recorder shutdown.
            }
        });
    }

    private void startAgentEvents(String packagePid) throws IOException {
        Path target = root.resolve("events.jsonl");
        if (packagePid == null) {
            Files.writeString(target, "", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("agent-error.txt"),
                "Package process is not running; agent events were not captured.\n",
                StandardCharsets.UTF_8);
            return;
        }
        String[] command = new String[] {"logcat", "--pid=" + packagePid, "-T", "1", "-v",
            "raw", "-s", "FlowScopeAgent:I", "*:S"};
        agentEvents = adb.stream(device.serial(), command);
        agentEventsPump = Thread.ofVirtual().name("flowscope-agent-events").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(agentEvents.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String event = line.strip();
                    if (!event.startsWith("{") || !event.endsWith("}")) continue;
                    writer.write(event);
                    writer.newLine();
                    writer.flush();
                    observer.accept("agent", event);
                }
            } catch (IOException ignored) {
                // The ADB stream closes during normal recorder shutdown.
            }
        });
    }

    private void startAccessibility(boolean append) throws IOException {
        Set<String> existingPids = uiautomatorPids();
        accessibility = adb.stream(device.serial(), "uiautomator", "events");
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(accessibility.getInputStream(), StandardCharsets.UTF_8));
        for (int attempt = 0; attempt < 20 && accessibilityPid == null; attempt++) {
            Set<String> currentPids = uiautomatorPids();
            currentPids.removeAll(existingPids);
            accessibilityPid = currentPids.stream().findFirst().orElse(null);
            if (accessibilityPid == null) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (accessibilityPid == null) {
            stop(accessibility);
            accessibility = null;
            throw new IOException("Could not start the accessibility event monitor");
        }
        Path target = root.resolve("accessibility-events.txt");
        accessibilityPump = Thread.ofVirtual().name("flowscope-accessibility-events").start(() -> {
            StandardOpenOption[] options = append
                ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
            try (reader; BufferedWriter writer = Files.newBufferedWriter(
                target, StandardCharsets.UTF_8, options)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                    observer.accept("accessibility", line);
                }
            } catch (IOException ignored) {
                // The remote monitor closes during normal recorder shutdown.
            }
        });
    }

    private void startAccessibilityBestEffort(boolean append) throws IOException {
        try {
            startAccessibility(append);
        } catch (IOException unavailable) {
            Files.writeString(root.resolve("accessibility-error.txt"),
                unavailable.getMessage() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private Set<String> uiautomatorPids() throws IOException {
        try {
            AdbClient.CommandResult result = adb.run(List.of(
                "-s", device.serial(), "shell", "pidof", "uiautomator"));
            if (result.exitCode() != 0 || result.stdout().isBlank()) return new HashSet<>();
            return new HashSet<>(List.of(result.stdout().strip().split("\\s+")));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while locating the accessibility monitor", interrupted);
        }
    }

    private String findPackagePid() throws IOException, InterruptedException {
        AdbClient.CommandResult result = adb.run(List.of(
            "-s", device.serial(), "shell", "pidof", packageName));
        if (result.exitCode() != 0 || result.stdout().isBlank()) return null;
        return result.stdout().strip().split("\\s+")[0];
    }

    private void writeManifest(Instant endedAt) throws IOException {
        String json = "{\n" +
            "  \"schemaVersion\": 1,\n" +
            "  \"mode\": " + Json.quote(mode.cliName()) + ",\n" +
            "  \"packageName\": " + Json.quote(packageName) + ",\n" +
            "  \"deviceSerial\": " + Json.quote(device.serial()) + ",\n" +
            "  \"deviceName\": " + Json.quote(device.displayName()) + ",\n" +
            "  \"startedAt\": " + Json.quote(startedAt.toString()) + ",\n" +
            "  \"endedAt\": " + (endedAt == null ? "null" : Json.quote(endedAt.toString())) + ",\n" +
            "  \"checkpointCount\": " + checkpointNumber + "\n" +
            "}\n";
        Files.writeString(root.resolve("manifest.json"), json, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        stop(logcat);
        join(logcatPump);
        stop(agentEvents);
        join(agentEventsPump);
        stopAccessibility();
        writeManifest(Instant.now());
    }

    private void stopAccessibility() {
        String remotePid = accessibilityPid;
        accessibilityPid = null;
        if (remotePid != null) {
            try {
                adb.run(List.of("-s", device.serial(), "shell", "kill", remotePid));
            } catch (IOException ignored) {
                // The device may disconnect while a recording is being closed.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        stop(accessibility);
        accessibility = null;
        join(accessibilityPump);
        accessibilityPump = null;
    }

    private static void stop(Process process) {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void join(Thread thread) {
        if (thread == null) return;
        try {
            thread.join(2_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
