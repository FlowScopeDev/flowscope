package dev.flowscope.host;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects the debug agent's Logcat events while an instrumented test runs, so the report can show
 * what the app itself saw: semantic actions, network calls, and background work.
 *
 * <p>Events are matched by the package's UID, not its PID, so they survive the process restarts a
 * test causes with {@code force-stop} and {@code launch}. Only fields the agent contract already
 * sanitizes (action and component names, route templates, status codes, work names) are summarized;
 * nothing else from an event is retained. Capture is best-effort and never changes a test result.
 */
final class AgentEventCapture {
    /** Kinds listed individually. Everything else is counted but not listed, to keep reports readable. */
    private static final java.util.Set<String> LISTED = java.util.Set.of(
        "agent.hello", "semantic.action", "network.request", "network.response", "background.work");
    private static final int MAX_LISTED = 500;
    private static final Pattern UID = Pattern.compile("uid:(\\d+)");

    record Event(Instant arrivedAt, String kind, String summary) {}

    private final List<Event> listed = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> counts = Collections.synchronizedMap(new TreeMap<>());
    private volatile String unavailable;
    private Process process;
    private Thread pump;

    /** Starts streaming; on any failure records why and leaves the run unaffected. */
    static AgentEventCapture start(AdbClient adb, String serial, String packageName) {
        AgentEventCapture capture = new AgentEventCapture();
        try {
            AdbClient.CommandResult uids = adb.run(
                List.of("-s", serial, "shell", "pm", "list", "packages", "-U", packageName));
            Matcher matcher = UID.matcher(uids.stdout());
            if (uids.exitCode() != 0 || !matcher.find()) {
                capture.unavailable = "the package's UID could not be determined";
                return capture;
            }
            capture.process = adb.stream(serial, "logcat", "--uid=" + matcher.group(1), "-T", "1",
                "-v", "raw", "-s", "FlowScopeAgent:I", "*:S");
            Process stream = capture.process;
            capture.pump = Thread.ofVirtual().name("flowscope-test-agent-events").start(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) capture.accept(line.strip());
                } catch (IOException ignored) {
                    // The stream closes when the run ends.
                }
            });
        } catch (Exception failure) {
            capture.unavailable = "capture could not start";
        }
        return capture;
    }

    private void accept(String line) {
        if (!line.startsWith("{") || !line.endsWith("}")) return;
        try {
            Map<String, Object> event = Json.parseObject(line);
            String kind = String.valueOf(event.get("kind"));
            counts.merge(kind, 1, Integer::sum);
            if (!LISTED.contains(kind) || listed.size() >= MAX_LISTED) return;
            Object payload = event.get("payload");
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = payload instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            listed.add(new Event(Instant.now(), kind, summarize(kind, fields)));
        } catch (RuntimeException malformed) {
            // A line that is not a valid event is ignored, as the recorder does.
        }
    }

    static String summarize(String kind, Map<String, Object> payload) {
        return switch (kind) {
            case "semantic.action" -> join(text(payload, "action"), text(payload, "componentId"),
                payload.get("screen") == null ? "" : "@" + text(payload, "screen"));
            case "network.request" -> join(text(payload, "method"), text(payload, "routeTemplate"));
            case "network.response" -> join(text(payload, "method"), text(payload, "routeTemplate"),
                text(payload, "statusCode"), payload.get("durationMillis") == null
                    ? "" : text(payload, "durationMillis") + " ms");
            case "background.work" -> join(text(payload, "workName"), text(payload, "state"));
            case "agent.hello" -> "agent " + text(payload, "agentVersion");
            default -> "";
        };
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(part);
        }
        return out.toString();
    }

    /** Stops the stream after giving buffered lines a moment to arrive. */
    void stop() {
        if (process == null) return;
        try {
            Thread.sleep(300);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        process.destroy();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
            if (pump != null) pump.join(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    boolean captured() { return unavailable == null; }

    String unavailableReason() { return unavailable; }

    List<Event> events() {
        synchronized (listed) { return List.copyOf(listed); }
    }

    Map<String, Integer> counts() {
        synchronized (counts) { return new TreeMap<>(counts); }
    }
}
