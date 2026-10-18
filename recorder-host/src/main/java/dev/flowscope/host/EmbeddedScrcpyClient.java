package dev.flowscope.host;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.Socket;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs scrcpy-server in raw-stream mode and decodes its H.264 output to JPEG frames with FFmpeg.
 * Input is deliberately sent through ADB so the video protocol remains isolated and replaceable.
 */
public final class EmbeddedScrcpyClient {
    private static final String REMOTE_SERVER = "/data/local/tmp/flowscope-scrcpy-server.jar";
    private final AdbClient adb;
    private volatile Session active;
    /** Stream cap on the larger dimension; the device is downscaled to fit. */
    private static final int MAX_SIZE = 1080;
    private final ScrcpyClient scrcpy;
    private final String ffmpeg;

    public EmbeddedScrcpyClient(AdbClient adb, ScrcpyClient scrcpy) {
        this.adb = adb;
        this.scrcpy = scrcpy;
        this.ffmpeg = resolveExecutable("FFMPEG", isWindows() ? "ffmpeg.exe" : "ffmpeg",
            isWindows() ? List.of() : List.of("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"));
    }

    public boolean available() {
        return scrcpy.serverPath() != null && scrcpy.version() != null && ffmpeg != null;
    }

    public String availabilitySummary() {
        if (scrcpy.serverPath() == null) return "scrcpy-server not found";
        if (scrcpy.version() == null) return "scrcpy version unavailable";
        if (ffmpeg == null) return "FFmpeg not found";
        return "Embedded scrcpy ready";
    }

    public Session start(String serial, Consumer<Frame> onFrame) throws Exception {
        // Match the display instead of hard-coding 30fps; a 90Hz panel streamed at 30 looks
        // noticeably worse than scrcpy does on the same device.
        int fps = AdbClient.DEFAULT_REFRESH_HZ;
        try {
            fps = adb.displayRefreshHz(serial);
        } catch (Exception ignored) {
            // A device that will not answer dumpsys still streams fine at the default.
        }
        return start(serial, fps, onFrame);
    }

    public Session start(String serial, int maxFps, Consumer<Frame> onFrame) throws Exception {
        if (!available()) throw new IOException(availabilitySummary());
        Path server = scrcpy.serverPath();
        String version = scrcpy.version();
        boolean control = ScrcpyControl.supports(version);
        int port = freePort();

        requireSuccess(adb.run(List.of("-s", serial, "push", server.toString(), REMOTE_SERVER)),
            "Could not install scrcpy-server");
        requireSuccess(adb.run(List.of("-s", serial, "forward", "tcp:" + port, "localabstract:scrcpy")),
            "Could not create scrcpy ADB tunnel");

        Process serverProcess = adb.start(List.of(
            "-s", serial, "shell",
            "CLASSPATH=" + REMOTE_SERVER,
            "app_process", "/", "com.genymobile.scrcpy.Server", version,
            "tunnel_forward=true", "audio=false", "control=" + control, "cleanup=false",
            "raw_stream=true", "max_size=" + MAX_SIZE, "max_fps=" + maxFps
        ));
        AtomicReference<String> diagnostics = new AtomicReference<>("");
        capture(serverProcess.getInputStream(), diagnostics);

        // The adb forward exists before app_process has bound its local socket. A short bounded
        // delay avoids consuming the server's only stream with a readiness probe.
        Thread.sleep(1_000);
        if (!serverProcess.isAlive()) {
            throw new IOException("scrcpy-server exited before the video tunnel was ready");
        }

        // Own both sockets here rather than letting FFmpeg dial the port itself. The server
        // accepts video first and then control, and does not start encoding until every expected
        // connection exists — so the order must be deterministic, which it is not if an external
        // process races us for the first accept.
        Socket videoSocket = new Socket("127.0.0.1", port);
        Socket controlSocket = null;
        if (control) {
            try {
                controlSocket = new Socket("127.0.0.1", port);
            } catch (IOException controlRefused) {
                diagnostics.set("control socket unavailable: " + controlRefused.getMessage());
            }
        }

        // Decode straight to raw BGRA at a size we compute, so the reader knows exactly how many
        // bytes a frame is and no second codec is involved.
        int[] deviceSize = null;
        try {
            deviceSize = adb.displaySize(serial);
        } catch (Exception ignored) {
            // Fall back to the stream cap below.
        }
        int[] frameSize = deviceSize == null
            ? new int[] {MAX_SIZE / 2, MAX_SIZE}
            : fitToMaxSize(deviceSize[0], deviceSize[1], MAX_SIZE);

        Process decoder = new ProcessBuilder(
            ffmpeg,
            "-hide_banner", "-loglevel", "error",
            "-flags", "low_delay",
            "-probesize", "32", "-analyzeduration", "0",
            "-f", "h264", "-i", "pipe:0",
            "-an",
            "-vf", "scale=" + frameSize[0] + ":" + frameSize[1],
            "-f", "rawvideo", "-pix_fmt", "bgra", "pipe:1"
        ).start();
        capture(decoder.getErrorStream(), diagnostics);

        Session session = new Session(adb, serial, port, serverProcess, decoder, diagnostics,
            videoSocket, controlSocket);
        session.installShutdownHook();

        session.pump = Thread.ofVirtual().name("flowscope-scrcpy-video").start(() -> {
            try (InputStream in = videoSocket.getInputStream();
                 OutputStream out = decoder.getOutputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // Closing either end during shutdown surfaces here; nothing to report.
            }
        });
        session.reader = Thread.ofVirtual().name("flowscope-embedded-scrcpy").start(() -> {
            try {
                readRawFrames(
                    new BufferedInputStream(decoder.getInputStream(), 1 << 20),
                    frameSize[0], frameSize[1], onFrame);
            } catch (IOException ignored) {
                // Closing the decoder interrupts the stream during normal shutdown.
            } finally {
                session.close();
            }
        });
        active = session;
        return session;
    }

    /** The control channel of the running session for this serial, or null when unavailable. */
    private ScrcpyControl controlFor(String serial) {
        Session session = active;
        if (session == null || !session.serial.equals(serial) || !session.isAlive()) return null;
        return session.control;
    }

    public void tap(String serial, int x, int y, int width, int height)
        throws IOException, InterruptedException {
        ScrcpyControl control = controlFor(serial);
        if (control != null) {
            control.tap(x, y, width, height);
            return;
        }
        requireSuccess(adb.run(List.of("-s", serial, "shell", "input", "tap",
            Integer.toString(x), Integer.toString(y))), "Tap injection failed");
    }

    public void swipe(String serial, int startX, int startY, int endX, int endY, int durationMs,
        int width, int height) throws IOException, InterruptedException {
        ScrcpyControl control = controlFor(serial);
        if (control != null) {
            control.swipe(startX, startY, endX, endY, durationMs, width, height);
            return;
        }
        requireSuccess(adb.run(List.of("-s", serial, "shell", "input", "swipe",
            Integer.toString(startX), Integer.toString(startY), Integer.toString(endX),
            Integer.toString(endY), Integer.toString(durationMs))), "Swipe injection failed");
    }

    public void keyEvent(String serial, DeviceKey key) throws IOException, InterruptedException {
        ScrcpyControl control = controlFor(serial);
        if (control != null) {
            control.send(ScrcpyControl.keycode(ScrcpyControl.ACTION_DOWN, key.androidKeyCode));
            control.send(ScrcpyControl.keycode(ScrcpyControl.ACTION_UP, key.androidKeyCode));
            return;
        }
        requireSuccess(adb.run(List.of("-s", serial, "shell", "input", "keyevent", key.androidCode)),
            key.name() + " injection failed");
    }

    public void typeText(String serial, String text) throws IOException, InterruptedException {
        if (text == null || text.isEmpty()) return;
        ScrcpyControl control = controlFor(serial);
        if (control != null) {
            // The control socket takes arbitrary UTF-8; `input text` does not.
            control.send(ScrcpyControl.text(text));
            return;
        }
        requireSuccess(adb.run(List.of("-s", serial, "shell", "input", "text", encodeInputText(text))),
            "Text injection failed");
    }

    /** Pushes text to the device clipboard, optionally pasting it into the focused field. */
    public boolean setDeviceClipboard(String serial, String text, boolean paste) throws IOException {
        ScrcpyControl control = controlFor(serial);
        if (control == null) return false;
        control.send(ScrcpyControl.setClipboard(text, paste));
        return true;
    }

    static String encodeInputText(String text) {
        if (!text.matches("[a-zA-Z0-9@._+ -]+")) {
            throw new IllegalArgumentException(
                "Text input supports letters, numbers, spaces, and @ . _ + -");
        }
        return text.replace(" ", "%s");
    }

    public enum DeviceKey {
        BACK("KEYCODE_BACK", 4), HOME("KEYCODE_HOME", 3), APP_SWITCH("KEYCODE_APP_SWITCH", 187);

        private final String androidCode;
        /** android.view.KeyEvent constant, required by the control protocol. */
        final int androidKeyCode;

        DeviceKey(String androidCode, int androidKeyCode) {
            this.androidCode = androidCode;
            this.androidKeyCode = androidKeyCode;
        }
    }

    /**
     * A decoded frame: tightly packed BGRA, {@code width * height * 4} bytes.
     *
     * <p>Call {@link #recycle()} once the pixels have been consumed. Skia's
     * {@code Image.makeRaster} copies the array, so the buffer is free as soon as the image
     * exists; returning it avoids allocating a fresh multi-megabyte array for every frame.
     * Recycling is optional — a frame that is never recycled is simply garbage collected.
     */
    public static final class Frame {
        private final byte[] pixels;
        private final int width;
        private final int height;
        private final FramePool pool;

        Frame(byte[] pixels, int width, int height, FramePool pool) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.pool = pool;
        }

        public byte[] pixels() { return pixels; }

        public int width() { return width; }

        public int height() { return height; }

        public void recycle() {
            if (pool != null) pool.offer(pixels);
        }
    }

    /**
     * A tiny bounded free-list of frame buffers. Bounded because an unbounded one would just hold
     * memory: only a couple of buffers are ever in flight.
     */
    static final class FramePool {
        private final java.util.ArrayDeque<byte[]> free = new java.util.ArrayDeque<>();
        private final int frameBytes;
        private final int capacity;

        FramePool(int frameBytes, int capacity) {
            this.frameBytes = frameBytes;
            this.capacity = capacity;
        }

        synchronized byte[] take() {
            byte[] buffer = free.poll();
            return buffer != null ? buffer : new byte[frameBytes];
        }

        synchronized void offer(byte[] buffer) {
            if (buffer.length == frameBytes && free.size() < capacity) free.addLast(buffer);
        }

        synchronized int available() { return free.size(); }
    }

    /**
     * scrcpy fits the larger dimension to {@code maxSize}; both dimensions are then rounded down
     * to even numbers because the encoder requires 2px alignment.
     */
    static int[] fitToMaxSize(int width, int height, int maxSize) {
        int w = width;
        int h = height;
        if (maxSize > 0 && Math.max(w, h) > maxSize) {
            double scale = (double) maxSize / Math.max(w, h);
            w = (int) Math.round(w * scale);
            h = (int) Math.round(h * scale);
        }
        return new int[] {Math.max(2, w & ~1), Math.max(2, h & ~1)};
    }

    /**
     * Reads fixed-size raw frames. The previous MJPEG reader scanned for JPEG markers one byte at
     * a time — millions of single-byte reads per second — on top of a needless encode/decode round
     * trip. A known frame size turns that into one bulk read per frame.
     */
    static void readRawFrames(InputStream input, int width, int height, Consumer<Frame> onFrame)
        throws IOException {
        int frameBytes = width * height * 4;
        FramePool pool = new FramePool(frameBytes, 3);
        while (true) {
            byte[] pixels = pool.take();
            int read = input.readNBytes(pixels, 0, frameBytes);
            if (read < frameBytes) return;
            onFrame.accept(new Frame(pixels, width, height, pool));
        }
    }

    static void readJpegFrames(InputStream input, Consumer<byte[]> onFrame) throws IOException {
        ByteArrayOutputStream frame = null;
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (frame == null) {
                if (previous == 0xff && current == 0xd8) {
                    frame = new ByteArrayOutputStream(256_000);
                    frame.write(0xff);
                    frame.write(0xd8);
                }
            } else {
                frame.write(current);
                if (previous == 0xff && current == 0xd9) {
                    onFrame.accept(frame.toByteArray());
                    frame = null;
                } else if (frame.size() > 12_000_000) {
                    frame = null;
                }
            }
            previous = current;
        }
    }

    private static void requireSuccess(AdbClient.CommandResult result, String message) throws IOException {
        if (result.exitCode() != 0) throw new IOException(message + ": " + result.stderr().strip());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void capture(InputStream input, AtomicReference<String> diagnostics) {
        Thread.ofVirtual().start(() -> {
            try (input) {
                String text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
                if (!text.isBlank()) diagnostics.set(text.lines().reduce((first, second) -> second).orElse(text));
            }
            catch (IOException ignored) { }
        });
    }

    private static String resolveExecutable(String environment, String name, List<String> defaults) {
        String explicit = System.getenv(environment);
        if (explicit != null && !explicit.isBlank()) return explicit;
        Set<Path> candidates = new LinkedHashSet<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                if (!directory.isBlank()) candidates.add(Path.of(directory, name));
            }
        }
        defaults.stream().map(Path::of).forEach(candidates::add);
        return candidates.stream().filter(Files::isRegularFile)
            .filter(candidate -> isWindows() || Files.isExecutable(candidate))
            .map(Path::toString).findFirst().orElse(null);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static final class Session implements AutoCloseable {
        private final AdbClient adb;
        private final String serial;
        private final int port;
        private final Process server;
        private final Process decoder;
        private final AtomicReference<String> diagnostics;
        private final Socket videoSocket;
        private final Socket controlSocket;
        /** Null when the resolved scrcpy version is outside the verified protocol range. */
        final ScrcpyControl control;
        private volatile boolean closed;
        private Thread reader;
        private Thread pump;

        private Session(AdbClient adb, String serial, int port, Process server, Process decoder,
            AtomicReference<String> diagnostics, Socket videoSocket, Socket controlSocket)
            throws IOException {
            this.videoSocket = videoSocket;
            this.controlSocket = controlSocket;
            this.control = controlSocket == null
                ? null : new ScrcpyControl(controlSocket.getOutputStream());
            this.adb = adb;
            this.serial = serial;
            this.port = port;
            this.server = server;
            this.decoder = decoder;
            this.diagnostics = diagnostics;
        }

        /**
         * Reaps the server if the JVM exits without a clean shutdown. Without this the `adb shell`
         * child survives, keeps the device's video stream claimed, and the next launch contends
         * with it (observed: two scrcpy servers on one serial after a SIGTERM).
         */
        void installShutdownHook() {
            shutdownHook = new Thread(this::close, "flowscope-scrcpy-reaper");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }

        public boolean isAlive() { return !closed && decoder.isAlive() && server.isAlive(); }

        private static void closeQuietly(Socket socket) {
            if (socket == null) return;
            try {
                socket.close();
            } catch (IOException ignored) {
                // Shutdown path; a socket that is already gone needs no report.
            }
        }

        /** Whether input is going through scrcpy's control socket rather than `adb shell input`. */
        public boolean hasControl() { return control != null; }

        public String failureSummary() {
            String detail = diagnostics.get();
            return detail.isBlank() ? "decoder or server exited" : detail;
        }

        private Thread shutdownHook;

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
                // Removing the hook from inside it would throw; only unregister on a normal close.
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException shuttingDown) {
                    // The JVM is already exiting; the hook is running and will finish the work.
                }
            }
            closeQuietly(videoSocket);
            closeQuietly(controlSocket);
            decoder.destroy();
            server.destroy();
            try {
                if (!decoder.waitFor(1, TimeUnit.SECONDS)) decoder.destroyForcibly();
                if (!server.waitFor(1, TimeUnit.SECONDS)) server.destroyForcibly();
                adb.run(List.of("-s", serial, "forward", "--remove", "tcp:" + port));
            } catch (IOException ignored) {
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
