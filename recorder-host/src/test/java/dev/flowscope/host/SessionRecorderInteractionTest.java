package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionRecorderInteractionTest {
    @TempDir Path directory;

    @Test
    void appendsTapAndSwipeAsStructuredHostEvents() throws Exception {
        Device device = new Device("device-1", "device", Map.of("model", "Test_Phone"));
        try (SessionRecorder recorder = new SessionRecorder(new AdbClient(), device,
            "com.example.debug", directory, CaptureMode.ZERO_CODE)) {
            recorder.recordTap(120, 340, 1080, 2400);
            recorder.recordSwipe(500, 1800, 500, 400, 280, 1080, 2400);

            var lines = Files.readAllLines(recorder.root().resolve("host-interactions.jsonl"));
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).contains("\"source\":\"desktop-live-device\""));
            assertTrue(lines.get(0).contains("\"kind\":\"ui.tap\""));
            assertTrue(lines.get(0).contains("\"x\":120,\"y\":340"));
            assertTrue(lines.get(1).contains("\"kind\":\"ui.swipe\""));
            assertTrue(lines.get(1).contains("\"durationMillis\":280"));
            assertTrue(lines.get(1).contains("\"direction\":\"up\""));
        }
    }
}
