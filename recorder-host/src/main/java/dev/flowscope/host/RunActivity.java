package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * `activity.json`: what the step that is running right now is doing, replaced in place.
 *
 * <p>A step used to report one `running` event and then nothing until it finished, so a 30-second
 * `api-request` or a 10-second `wait-visible` looked identical to a hang. This publishes elapsed
 * time, the step's own budget, a stage word and a counter while the step is still in flight.
 *
 * <p>Two design points are load-bearing.
 *
 * <p><b>It is a separate file, overwritten, not a line appended to `progress.jsonl`.</b> Activity is
 * state: only the newest value is ever read. Appending it would make that file's size proportional
 * to a run's duration rather than its step count, and both {@link RunProgress#read} and
 * {@link RunSteps#read} re-parse the whole file on every poll. Keeping it out also leaves
 * `progress.jsonl` carrying exactly one `running` and one terminal event per step.
 *
 * <p><b>It is published by a heartbeat thread, not by the loops.</b> The case this exists for —
 * an `api-request` with no `retry-interval` — blocks inside one `HttpClient` call with no loop
 * iterating and no callback firing, so nothing posted from a loop body would ever be reached. Loop
 * sites only write memory; the heartbeat turns memory into a frame on a cadence. That also makes
 * throttling structural rather than a rate limiter repeated at every call site.
 *
 * <p>Lines are redaction-free <b>by construction</b>: every mutator below takes an enum or an int,
 * so there is no parameter a URL, selector, header name or interpolated value could arrive through.
 *
 * <p>Writing is best effort, like {@link RunProgress}. A frame that cannot be written, or a
 * heartbeat that dies, must never change a run's verdict.
 */
public final class RunActivity {
    public static final String FILE = "activity.json";
    static final int SCHEMA_VERSION = 1;
    /** Half a second reads as live; a whole second reads as frozen. */
    static final long CADENCE_MILLIS = 500;

    /** What a step is doing. A closed vocabulary, so a stage can never carry a value. */
    public enum Stage {
        RUNNING("running"),
        LOOKING_UP("looking-up"),
        CHECKING_ABSENT("checking-absent"),
        RETRYING_DUMP("retrying-dump"),
        SETTLING("settling"),
        SCROLLING("scrolling"),
        AWAITING_RESPONSE("awaiting-response"),
        RECEIVING_BODY("receiving-body"),
        RETRYING("retrying"),
        WEB_POLLING("web-polling");

        private final String value;

        Stage(String value) { this.value = value; }

        public String value() { return value; }
    }

    /**
     * One published frame. [phase] and [number] identify the step it belongs to so a reader can
     * drop a frame left over from the previous one; [budgetMillis] is 0 when the action declares no
     * deadline and [attemptLimit] is 0 when the count is unbounded.
     */
    public record Snapshot(String phase, int number, int total, String stage, long elapsedMillis,
                           long budgetMillis, int attempt, int attemptLimit) {

        /**
         * The worded suffix every surface renders — the CLI line, the IDE run bar, the detail pane
         * and the step row — so none of the four can drift from the others.
         */
        public String describe() {
            StringBuilder out = new StringBuilder();
            String words = words();
            if (!words.isEmpty()) out.append(words);
            // A first attempt is not news, and saying so costs a surface the room to say what the
            // step is doing. `scrolling` already reads "swipe 3 of 10", so it never repeats here.
            if (attempt > 1 && !"scrolling".equals(stage)) {
                if (!out.isEmpty()) out.append(" · ");
                out.append("attempt ").append(attempt);
                if (attemptLimit > 0) out.append(" of ").append(attemptLimit);
            }
            if (!out.isEmpty()) out.append(" · ");
            return out.append(timing()).toString();
        }

        /**
         * Just the clock. The part a reader is actually waiting on, and the last thing a surface
         * too narrow for the whole description should give up.
         */
        public String timing() {
            String elapsed = seconds(elapsedMillis);
            return budgetMillis > 0 ? elapsed + " of " + seconds(budgetMillis) : elapsed;
        }

        private String words() {
            if (stage == null) return "";
            return switch (stage) {
                // The default stage adds nothing a reader cannot already see.
                case "running" -> "";
                case "looking-up" -> "looking up";
                case "checking-absent" -> "checking it is gone";
                case "retrying-dump" -> "retrying a bad UI dump";
                case "settling" -> "waiting for the UI to settle";
                case "scrolling" -> attemptLimit > 0
                    ? "swipe " + attempt + " of " + attemptLimit : "swipe " + attempt;
                case "awaiting-response" -> "awaiting response";
                case "receiving-body" -> "receiving response";
                case "retrying" -> "retrying";
                case "web-polling" -> "polling the WebView";
                // A newer host and an older reader ship together, but render rather than break.
                default -> stage;
            };
        }

        /** Short enough to sit in a one-line status: a decimal only while it still reads as one. */
        private static String seconds(long millis) {
            return millis < 10_000
                ? String.format(Locale.ROOT, "%.1fs", millis / 1000.0)
                : (millis / 1000) + "s";
        }
    }

    /** A monitor nothing observes, so no caller needs a null check. */
    static final RunActivity NONE = new RunActivity();

    /**
     * The whole state in one reference. Not separate volatile fields: {@link FlowHttpClient}
     * reports its stages from HTTP-client threads while the runner thread is updating counters, and
     * independent fields would let the heartbeat read a torn mix of the two.
     */
    private record State(String phase, int number, int total, Stage stage, long startedNanos,
                         long budgetMillis, int attempt, int attemptLimit) {}

    private final AtomicReference<State> state = new AtomicReference<>();

    /** The step is about to run. Until {@link #end()} the heartbeat has something to publish. */
    void begin(String phase, int number, int total) {
        state.set(new State(phase, number, total, Stage.RUNNING, System.nanoTime(), 0, 0, 0));
    }

    /** The step finished, however it finished. Nothing is published between steps. */
    void end() {
        state.set(null);
    }

    void stage(Stage stage) {
        state.updateAndGet(current -> current == null ? null : new State(current.phase(),
            current.number(), current.total(), stage, current.startedNanos(),
            current.budgetMillis(), current.attempt(), current.attemptLimit()));
    }

    /**
     * The deadline this step is actually working to. Only code that builds a deadline out of a
     * timeout calls this, so an action with no real bound reports elapsed and nothing else rather
     * than borrowing a number that does not govern it.
     */
    void budget(long millis) {
        state.updateAndGet(current -> current == null ? null : new State(current.phase(),
            current.number(), current.total(), current.stage(), current.startedNanos(),
            millis, current.attempt(), current.attemptLimit()));
    }

    void attempt(int attempt, int limit) {
        state.updateAndGet(current -> current == null ? null : new State(current.phase(),
            current.number(), current.total(), current.stage(), current.startedNanos(),
            current.budgetMillis(), attempt, limit));
    }

    /** Stage and attempt together, so the heartbeat cannot catch the pair half-written. */
    void stage(Stage stage, int attempt, int limit) {
        state.updateAndGet(current -> current == null ? null : new State(current.phase(),
            current.number(), current.total(), stage, current.startedNanos(),
            current.budgetMillis(), attempt, limit));
    }

    /** The current frame, or null while no step is in flight. Elapsed is monotonic, from here. */
    Snapshot snapshot() {
        State current = state.get();
        if (current == null) return null;
        return new Snapshot(current.phase(), current.number(), current.total(),
            current.stage().value(),
            Duration.ofNanos(System.nanoTime() - current.startedNanos()).toMillis(),
            current.budgetMillis(), current.attempt(), current.attemptLimit());
    }

    /**
     * Publishes the monitor's state on a cadence: one daemon thread, one small file replaced in
     * place, and a listener for whoever started the run.
     *
     * <p>The cadence governs how quickly a change is seen; the change check governs how much is
     * written. Holding one stage steady therefore costs one frame a second, not two, and a stage
     * change still lands within a cadence.
     */
    static final class Heartbeat implements AutoCloseable {
        private final Thread thread;
        private volatile boolean stopped;

        Heartbeat(RunActivity monitor, Path runDirectory, Consumer<Snapshot> listener,
                  long cadenceMillis) {
            Path file = runDirectory.resolve(FILE);
            Path temporary = runDirectory.resolve(FILE + ".tmp");
            thread = new Thread(() -> {
                Snapshot last = null;
                while (!stopped) {
                    try {
                        Thread.sleep(cadenceMillis);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    try {
                        Snapshot now = monitor.snapshot();
                        // Between steps there is nothing to say, so nothing is written. Nor is
                        // there in the first moments of one: the ticks are not aligned to a step's
                        // start, so without this a step beginning just before a tick would publish
                        // a frame reading `0.0s` and then finish.
                        if (now == null || now.elapsedMillis() < cadenceMillis
                            || unchanged(last, now)) {
                            continue;
                        }
                        write(file, temporary, now);
                        listener.accept(now);
                        last = now;
                    } catch (Throwable broken) {
                        // Stop reporting rather than let a courtesy take the run with it.
                        return;
                    }
                }
            }, "flowscope-activity");
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        /** Stops before the report is written, so a frame cannot outlive the file's own lifecycle. */
        @Override
        public void close() {
            stopped = true;
            thread.interrupt();
            try {
                thread.join(2 * CADENCE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        /** The same second, the same step and the same counters is the same frame to a reader. */
        private static boolean unchanged(Snapshot last, Snapshot now) {
            return last != null
                && last.number() == now.number()
                && last.phase().equals(now.phase())
                && last.stage().equals(now.stage())
                && last.attempt() == now.attempt()
                && last.attemptLimit() == now.attemptLimit()
                && last.budgetMillis() == now.budgetMillis()
                && last.elapsedMillis() / 1000 == now.elapsedMillis() / 1000;
        }

        private static void write(Path file, Path temporary, Snapshot frame) throws IOException {
            String json = "{\"schemaVersion\":" + SCHEMA_VERSION
                + ",\"phase\":" + Json.quote(frame.phase())
                + ",\"number\":" + frame.number()
                + ",\"total\":" + frame.total()
                + ",\"stage\":" + Json.quote(frame.stage())
                + ",\"elapsedMillis\":" + frame.elapsedMillis()
                + ",\"budgetMillis\":" + frame.budgetMillis()
                + ",\"attempt\":" + frame.attempt()
                + ",\"attemptLimit\":" + frame.attemptLimit()
                + ",\"updatedAt\":" + Json.quote(Instant.now().toString()) + "}\n";
            // Replaced, never appended: a reader must never see half a frame.
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Starts a heartbeat for [monitor] writing into [runDirectory]. */
    static Heartbeat heartbeat(RunActivity monitor, Path runDirectory, Consumer<Snapshot> listener) {
        return heartbeat(monitor, runDirectory, listener, CADENCE_MILLIS);
    }

    static Heartbeat heartbeat(RunActivity monitor, Path runDirectory, Consumer<Snapshot> listener,
                               long cadenceMillis) {
        Heartbeat beating = new Heartbeat(monitor, runDirectory, listener, cadenceMillis);
        beating.start();
        return beating;
    }

    /** Removes the file and anything left of a replacement. A no-op when neither exists. */
    static void clear(Path runDirectory) {
        for (String name : new String[] {FILE, FILE + ".tmp"}) {
            try {
                Files.deleteIfExists(runDirectory.resolve(name));
            } catch (IOException ignored) {
                // A leftover frame is harmless: a reader checks the writer is still alive.
            }
        }
    }

    /**
     * What a run directory says it is doing right now, or empty when it says nothing. The caller
     * still has to decide the frame is live — that it belongs to the step the run reports as
     * running, and that the writer's process is still there.
     */
    public static Optional<Snapshot> read(Path runDirectory) {
        Path file = runDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        Map<String, Object> frame;
        try {
            frame = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException unreadable) {
            // Mid-replacement or truncated: the next poll is 500 ms away.
            return Optional.empty();
        }
        if (!(frame.get("phase") instanceof String phase)
            || !(frame.get("stage") instanceof String stage)) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot(phase, (int) number(frame, "number"),
            (int) number(frame, "total"), stage, number(frame, "elapsedMillis"),
            number(frame, "budgetMillis"), (int) number(frame, "attempt"),
            (int) number(frame, "attemptLimit")));
    }

    private static long number(Map<String, Object> frame, String key) {
        return frame.get(key) instanceof Long value ? value : 0;
    }
}
