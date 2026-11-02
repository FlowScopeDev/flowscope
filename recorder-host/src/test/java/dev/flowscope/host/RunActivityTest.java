package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a step says about itself while it is still running.
 *
 * <p>A step used to report one `running` event and then nothing, so a 30-second `api-request` was
 * indistinguishable from a hang. These cover the frame's contents, the guarantee that it can never
 * carry an argument value, and the guarantee that none of it can fail a run.
 */
class RunActivityTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    /** The nine keys a frame is allowed to have. A tenth has to be a deliberate edit here. */
    private static final Set<String> KEYS = Set.of("schemaVersion", "phase", "number", "total",
        "stage", "elapsedMillis", "budgetMillis", "attempt", "attemptLimit", "updatedAt");

    @Test
    void aBlockedRequestReportsItselfInsteadOfGoingSilent() throws Exception {
        // The case this exists for: with no retry-interval the retry loop runs once and the thread
        // blocks inside one HTTP call, so nothing posted from a loop body would ever be reached.
        try (Slow endpoint = Slow.start(1200)) {
            List<RunActivity.Snapshot> frames = new CopyOnWriteArrayList<>();
            FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE,
                FlowTestParser.parseSource("""
                    flowscope 1
                    package com.example.debug
                    step api-request url="%s" expect-status=200 timeout=20000
                    """.formatted(endpoint.url()), "slow.flow"),
                temp, "slow", FlowHttpClient.platform(), Map.of());
            runner.onActivity(frames::add);

            assertTrue(runner.run());
            assertFalse(frames.isEmpty(), "a blocked request reported nothing");
            RunActivity.Snapshot frame = frames.getFirst();
            assertEquals("awaiting-response", frame.stage(), frame.describe());
            assertEquals(1, frame.number());
            // The budget is the step's own, because api-request is what builds the deadline from it.
            assertEquals(20_000, frame.budgetMillis());
            assertEquals(1, frame.attempt());
            assertTrue(frame.elapsedMillis() > 0);
            assertTrue(frame.describe().contains("awaiting response"), frame.describe());
            assertTrue(frame.describe().contains("of 20s"), frame.describe());
        }
    }

    @Test
    void aPollingLookupReportsHowManyTimesItHasLooked() throws Exception {
        List<RunActivity.Snapshot> frames = new CopyOnWriteArrayList<>();
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE,
            FlowTestParser.parseSource("""
                flowscope 1
                package com.example.debug
                step wait-visible text=Missing timeout=2000
                """, "poll.flow"),
            temp, "poll", new NoHttp(), Map.of());
        runner.onActivity(frames::add);

        assertFalse(runner.run());
        assertFalse(frames.isEmpty(), "a polling lookup reported nothing");
        assertTrue(frames.stream().allMatch(frame -> frame.stage().equals("looking-up")),
            frames.stream().map(RunActivity.Snapshot::stage).toList().toString());
        assertEquals(2000, frames.getFirst().budgetMillis());
        // The attempt count climbs, which is what tells a slow lookup from a stuck one.
        assertTrue(frames.getLast().attempt() > frames.getFirst().attempt(),
            frames.stream().map(RunActivity.Snapshot::describe).toList().toString());
        // A first attempt is not news, so the count appears only once there has been more than one.
        assertEquals("looking up", frames.getFirst().describe().split(" · ")[0],
            frames.getFirst().describe());
        assertTrue(frames.getLast().describe().contains("attempt "), frames.getLast().describe());
    }

    @Test
    void aFrameCarriesNoArgumentValue() throws Exception {
        // Every mutator takes an enum or an int, so this is structural rather than a convention —
        // but the written file is what a reader sees, so assert on that too.
        List<RunActivity.Snapshot> frames = new CopyOnWriteArrayList<>();
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE,
            FlowTestParser.parseSource("""
                flowscope 1
                package com.example.debug
                before set-value name=token value=sentinel-value
                step wait-visible text=sentinel-selector desc=sentinel-desc timeout=1500
                """, "secrets.flow"),
            temp, "secrets", new NoHttp(), Map.of());
        runner.onActivity(frames::add);

        assertFalse(runner.run());
        assertFalse(frames.isEmpty());
        for (RunActivity.Snapshot frame : frames) {
            String rendered = frame + " " + frame.describe();
            for (String leak : List.of("sentinel-value", "sentinel-selector", "sentinel-desc",
                "token", "com.example.debug")) {
                assertFalse(rendered.contains(leak), "Leaked " + leak + " in " + rendered);
            }
        }
    }

    @Test
    void theWrittenFrameHasExactlyTheDeclaredKeys() throws Exception {
        RunActivity monitor = new RunActivity();
        monitor.begin("before", 3, 34);
        monitor.budget(30_000);
        monitor.stage(RunActivity.Stage.AWAITING_RESPONSE, 2, 0);
        List<RunActivity.Snapshot> seen = new CopyOnWriteArrayList<>();
        try (RunActivity.Heartbeat beating = RunActivity.heartbeat(monitor, temp, seen::add, 20)) {
            waitFor(() -> Files.isRegularFile(temp.resolve(RunActivity.FILE)));
        }
        String written = Files.readString(temp.resolve(RunActivity.FILE), StandardCharsets.UTF_8);
        assertEquals(KEYS, Json.parseObject(written).keySet(), written);
        assertTrue(written.contains("\"stage\":\"awaiting-response\""), written);
        assertTrue(written.contains("\"budgetMillis\":30000"), written);
    }

    @Test
    void stageWordsAreAClosedVocabulary() {
        assertEquals(List.of("running", "looking-up", "checking-absent", "retrying-dump", "settling",
                "scrolling", "awaiting-response", "receiving-body", "retrying", "web-polling"),
            java.util.Arrays.stream(RunActivity.Stage.values()).map(RunActivity.Stage::value).toList());
        for (RunActivity.Stage stage : RunActivity.Stage.values()) {
            assertTrue(stage.value().matches("^[a-z][a-z-]{0,23}$"), stage.value());
        }
    }

    @Test
    void holdingOneStageSteadyPublishesAboutOneFramePerSecond() throws Exception {
        // The cadence governs how fast a change is seen; the change check governs how much is
        // written. Without the second of those a 30 s step would write 60 frames.
        RunActivity monitor = new RunActivity();
        monitor.begin("test", 1, 1);
        List<RunActivity.Snapshot> frames = new CopyOnWriteArrayList<>();
        try (RunActivity.Heartbeat beating = RunActivity.heartbeat(monitor, temp, frames::add, 25)) {
            Thread.sleep(1_100);
        }
        assertFalse(frames.isEmpty(), "nothing published at all");
        // Around 44 cadences elapsed; only the second boundaries may be published.
        assertTrue(frames.size() <= 4, "published " + frames.size() + " frames for ~1.1 s");
    }

    @Test
    void aStageChangeIsPublishedWithoutWaitingForTheNextSecond() throws Exception {
        RunActivity monitor = new RunActivity();
        monitor.begin("test", 1, 1);
        List<RunActivity.Snapshot> frames = new CopyOnWriteArrayList<>();
        try (RunActivity.Heartbeat beating = RunActivity.heartbeat(monitor, temp, frames::add, 20)) {
            waitFor(() -> !frames.isEmpty());
            monitor.stage(RunActivity.Stage.SCROLLING, 3, 10);
            waitFor(() -> frames.stream().anyMatch(frame -> frame.stage().equals("scrolling")));
        }
        RunActivity.Snapshot scrolling = frames.stream()
            .filter(frame -> frame.stage().equals("scrolling")).findFirst().orElseThrow();
        // The count is folded into the phrase rather than repeated after it.
        assertTrue(scrolling.describe().startsWith("swipe 3 of 10 · "), scrolling.describe());
        assertFalse(scrolling.describe().contains("attempt"), scrolling.describe());
    }

    @Test
    void aStepFasterThanOneCadenceSaysNothingAtAll() throws Exception {
        // The ticks are not aligned to a step's start, so a step beginning just before one would
        // otherwise publish a single frame reading `0.0s` and then finish.
        RunActivity monitor = new RunActivity();
        List<RunActivity.Snapshot> frames = new CopyOnWriteArrayList<>();
        try (RunActivity.Heartbeat beating = RunActivity.heartbeat(monitor, temp, frames::add, 60)) {
            for (int step = 1; step <= 12; step++) {
                monitor.begin("test", step, 12);
                Thread.sleep(15);
                monitor.end();
            }
            Thread.sleep(120);
        }
        assertTrue(frames.isEmpty(),
            frames.stream().map(RunActivity.Snapshot::describe).toList().toString());
    }

    @Test
    void nothingIsPublishedBetweenSteps() throws Exception {
        RunActivity monitor = new RunActivity();
        monitor.begin("test", 1, 2);
        assertNotNull(monitor.snapshot());
        monitor.end();
        assertNull(monitor.snapshot());

        List<RunActivity.Snapshot> frames = new CopyOnWriteArrayList<>();
        try (RunActivity.Heartbeat beating = RunActivity.heartbeat(monitor, temp, frames::add, 20)) {
            Thread.sleep(150);
        }
        assertTrue(frames.isEmpty(), frames.toString());
        assertFalse(Files.exists(temp.resolve(RunActivity.FILE)));
    }

    @Test
    void anUnwritableRunDirectoryDoesNotFailTheRun() throws Exception {
        // Reporting is a courtesy. A frame that cannot be written must not reach the verdict, the
        // same way a hierarchy that cannot be dumped becomes details.hierarchyError.
        RunActivity monitor = new RunActivity();
        monitor.begin("test", 1, 1);
        Path missing = temp.resolve("gone");
        List<RunActivity.Snapshot> frames = new CopyOnWriteArrayList<>();
        try (RunActivity.Heartbeat beating = RunActivity.heartbeat(monitor, missing, frames::add, 20)) {
            Thread.sleep(150);
        }
        assertTrue(frames.isEmpty(), "a frame was published from an unwritable directory");

        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE,
            FlowTestParser.parseSource("""
                flowscope 1
                package com.example.debug
                step force-stop
                """, "unwritable.flow"), temp, "unwritable", new NoHttp(), Map.of());
        assertTrue(runner.run());
    }

    @Test
    void theFileIsGoneOnceTheReportIsWritten() throws Exception {
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE,
            FlowTestParser.parseSource("""
                flowscope 1
                package com.example.debug
                step force-stop
                """, "clean.flow"), temp, "clean", new NoHttp(), Map.of());

        assertTrue(runner.run());
        // A finished run folder looks exactly as it did before either transient file existed.
        assertFalse(Files.exists(runner.root().resolve(RunActivity.FILE)));
        assertFalse(Files.exists(runner.root().resolve(RunActivity.FILE + ".tmp")));
        assertFalse(Files.exists(runner.root().resolve(RunProgress.FILE)));
        assertTrue(Files.isRegularFile(runner.root().resolve("report.json")));
    }

    @Test
    void theHeartbeatThreadIsADaemonAndIsGoneAfterTheRun() throws Exception {
        FlowTestRunner runner = new FlowTestRunner(new FakeAdb(), DEVICE,
            FlowTestParser.parseSource("""
                flowscope 1
                package com.example.debug
                step force-stop
                """, "threads.flow"), temp, "threads", new NoHttp(), Map.of());

        assertTrue(runner.run());
        assertTrue(Thread.getAllStackTraces().keySet().stream()
            .noneMatch(thread -> thread.getName().equals("flowscope-activity") && thread.isAlive()),
            "the heartbeat outlived the run");
    }

    @Test
    void anUntimedActionReportsElapsedWithoutInventingABudget() throws Exception {
        // force-stop's real bound is AdbClient's own, not the step's `timeout`, so reporting a
        // budget here would be reporting a deadline nothing is working to.
        RunActivity monitor = new RunActivity();
        monitor.begin("test", 1, 1);
        RunActivity.Snapshot frame = monitor.snapshot();
        assertEquals(0, frame.budgetMillis());
        assertEquals("running", frame.stage());
        // The default stage says nothing a reader cannot already see, so only elapsed is rendered.
        assertTrue(frame.describe().matches("\\d+\\.\\ds"), frame.describe());
    }

    @Test
    void aFrameIsReadBackFromTheFileAsItWasWritten() throws Exception {
        Files.writeString(temp.resolve(RunActivity.FILE), "{\"schemaVersion\":1,\"phase\":\"after\","
            + "\"number\":12,\"total\":12,\"stage\":\"settling\",\"elapsedMillis\":1500,"
            + "\"budgetMillis\":10000,\"attempt\":4,\"attemptLimit\":0,\"updatedAt\":\"x\"}");
        RunActivity.Snapshot frame = RunActivity.read(temp).orElseThrow();
        assertEquals("after", frame.phase());
        assertEquals(12, frame.number());
        assertEquals("waiting for the UI to settle · attempt 4 · 1.5s of 10s", frame.describe());
    }

    @Test
    void anUnknownStageIsRenderedRatherThanBreakingTheReader() {
        RunActivity.Snapshot frame =
            new RunActivity.Snapshot("test", 1, 1, "inventing-things", 2000, 0, 0, 0);
        assertEquals("inventing-things · 2.0s", frame.describe());
    }

    @Test
    void anAbsentOrUnreadableFileSaysNothing() throws Exception {
        assertTrue(RunActivity.read(temp).isEmpty());
        Files.writeString(temp.resolve(RunActivity.FILE), "{half a fr");
        assertTrue(RunActivity.read(temp).isEmpty());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("condition never held");
    }

    /** A real endpoint that answers late, so the request genuinely blocks. */
    private static final class Slow implements AutoCloseable {
        private final HttpServer server;

        private Slow(HttpServer server) { this.server = server; }

        static Slow start(long millis) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/slow", exchange -> {
                try { Thread.sleep(millis); } catch (InterruptedException ignored) { }
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return new Slow(server);
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/slow"; }

        @Override public void close() { server.stop(0); }
    }

    private static final class NoHttp implements FlowHttpClient {
        @Override public Response send(String method, java.net.URI uri, Map<String, String> headers,
            String body, long timeoutMillis) {
            return new Response(200, "{}");
        }
    }

    /** An empty screen, so a selector never resolves and a lookup polls for its whole budget. */
    private static final class FakeAdb extends AdbClient {
        @Override public CommandResult run(List<String> arguments, long timeoutMillis) {
            return new CommandResult(0, "Success", "");
        }
        @Override public byte[] execOut(String serial, String... command) {
            if (command.length > 0 && command[0].equals("screencap")) return new byte[0];
            return "<?xml version=\"1.0\"?><hierarchy rotation=\"0\"/>"
                .getBytes(StandardCharsets.UTF_8);
        }
    }
}
