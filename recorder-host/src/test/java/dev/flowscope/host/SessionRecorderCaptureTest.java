package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionRecorderCaptureTest {
    @TempDir Path root;
    static final class Stream extends Process {
        final PipedInputStream input = new PipedInputStream();
        final PipedOutputStream output;
        volatile boolean alive = true;
        Stream() throws IOException { output = new PipedOutputStream(input); }
        void emit(String line) throws IOException { output.write((line + "\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { alive = false; try { output.close(); } catch (IOException ignored) {} }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }
    static final class Adb extends AdbClient {
        final List<List<String>> commands = new ArrayList<>();
        final List<Stream> streams = new ArrayList<>();
        boolean failScreenshot;
        boolean failLogcat;
        String packages = "package:com.example.extra uid:999\npackage:com.example uid:123\n";
        @Override public CommandResult run(List<String> args, long timeout) {
            if (args.contains("packages")) return new CommandResult(0, packages, "");
            return new CommandResult(0, "", "");
        }
        @Override public byte[] execOut(String serial, String... args) throws IOException {
            if (args[0].equals("screencap") && failScreenshot) throw new IOException("capture failure");
            return "<hierarchy/>".getBytes(StandardCharsets.UTF_8);
        }
        @Override public Process stream(String serial, String... args) throws IOException {
            commands.add(List.of(args));
            if (failLogcat && !List.of(args).contains("FlowScopeAgent:I")) throw new IOException("start failure");
            Stream stream = new Stream(); streams.add(stream); return stream;
        }
    }
    SessionRecorder recorder(Adb adb) throws IOException {
        return new SessionRecorder(adb, new Device("test", "device", Map.of()), "com.example", root, CaptureMode.INSTRUMENTED);
    }
    static void await(java.util.function.BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(ready.getAsBoolean());
    }
    @Test void uidCaptureStartsWithoutProcessAndContinuesAcrossRestart() throws Exception {
        Adb adb = new Adb();
        List<String> events = new CopyOnWriteArrayList<>();
        SessionRecorder recorder = recorder(adb).observe((channel, line) -> events.add(channel + ":" + line));
        recorder.start();
        assertEquals(2, adb.commands.size());
        assertTrue(adb.commands.stream().allMatch(args -> args.contains("--uid=123")));
        assertTrue(adb.commands.stream().noneMatch(args -> args.stream().anyMatch(a -> a.startsWith("--pid="))));
        adb.streams.get(1).emit("{\"kind\":\"activity.lifecycle\",\"pid\":100}");
        adb.streams.get(1).emit("{\"kind\":\"activity.lifecycle\",\"pid\":200}");
        adb.streams.get(0).emit("warning from restarted process");
        await(() -> events.size() == 3);
        recorder.close();
        assertTrue(recorder.captureIssues().isEmpty());
        assertEquals(2, Files.readAllLines(recorder.root().resolve("events.jsonl")).size());
        try (var in = new GZIPInputStream(Files.newInputStream(recorder.root().resolve("logcat.txt.gz")))) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("restarted process"));
        }
        assertTrue(adb.streams.stream().noneMatch(Stream::isAlive));
    }
    @Test void unexpectedStreamExitIsVisible() throws Exception {
        Adb adb = new Adb();
        try (SessionRecorder recorder = recorder(adb)) {
            recorder.start();
            adb.streams.get(1).destroy();
            await(() -> recorder.captureIssues().containsKey("agent"));
            assertTrue(recorder.captureIssues().get("agent").contains("unexpectedly"));
        }
    }
    @Test void failedCheckpointClosesPreviouslyStartedStreams() throws Exception {
        Adb adb = new Adb(); adb.failScreenshot = true;
        SessionRecorder recorder = recorder(adb);
        assertThrows(IOException.class, recorder::start);
        assertTrue(adb.streams.stream().noneMatch(Stream::isAlive));
    }
    @Test void unavailableChannelsDoNotPretendTheyAreCapturing() throws Exception {
        Adb adb = new Adb(); adb.failLogcat = true;
        try (SessionRecorder recorder = recorder(adb)) {
            recorder.start();
            assertTrue(recorder.captureIssues().containsKey("logs"));
        }
    }
    @Test void uidLookupNeverMatchesAPackagePrefix() throws Exception {
        Adb adb = new Adb();
        assertEquals("123", adb.packageUid("test", "com.example"));
        adb.packages = "package:com.example.extra uid:999\n";
        assertNull(adb.packageUid("test", "com.example"));
        try (SessionRecorder recorder = recorder(adb)) {
            recorder.start();
            assertEquals(Set.of("agent", "logs"), recorder.captureIssues().keySet());
            assertTrue(adb.streams.isEmpty());
        }
    }
    @Test void accessibilityFailureIsSurfaced() throws Exception {
        Adb adb = new Adb();
        try (SessionRecorder recorder = new SessionRecorder(adb, new Device("test", "device", Map.of()), "com.example", root, CaptureMode.ZERO_CODE)) {
            recorder.start();
            assertTrue(recorder.captureIssues().containsKey("accessibility"));
        }
    }
}
