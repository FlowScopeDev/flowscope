package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class ConnectedDeviceInteractionSmokeTest {
    @TempDir Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "FLOWSCOPE_DEVICE_SERIAL", matches = ".+")
    void injectsAndPersistsTapAndSwipeOnAConnectedDevice() throws Exception {
        String serial = System.getenv("FLOWSCOPE_DEVICE_SERIAL");
        String packageName = System.getenv().getOrDefault("FLOWSCOPE_TEST_PACKAGE", "com.android.settings");
        AdbClient adb = new AdbClient();
        EmbeddedScrcpyClient input = new EmbeddedScrcpyClient(adb, new ScrcpyClient());
        Device device = adb.devices().stream()
            .filter(candidate -> candidate.serial().equals(serial) && candidate.state().equals("device"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Connected device not found: " + serial));

        try (SessionRecorder recorder = new SessionRecorder(adb, device,
            packageName, directory, CaptureMode.ZERO_CODE)) {
            recorder.start();

            UiSelectorResolver.Resolution target = recorder.resolveTap(540, 1200);
            assertTrue(target != null, "Expected a UIAutomator node at the device center");
            assertTrue(!target.selector().isEmpty(), "Expected a stable selector at the device center");
            assertTrue(target.flowStep().startsWith("step tap "));

            AdbClient.CommandResult tap = adb.run(List.of(
                "-s", serial, "shell", "input", "tap", "540", "1200"));
            assertEquals(0, tap.exitCode(), tap.stderr());
            recorder.recordTap(540, 1200, 1080, 2400, target);

            AdbClient.CommandResult swipe = adb.run(List.of(
                "-s", serial, "shell", "input", "swipe", "540", "1800", "540", "800", "280"));
            assertEquals(0, swipe.exitCode(), swipe.stderr());
            recorder.recordSwipe(540, 1800, 540, 800, 280, 1080, 2400);

            var lines = Files.readAllLines(recorder.root().resolve("host-interactions.jsonl"));
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).contains("\"kind\":\"ui.tap\""));
            assertTrue(lines.get(0).contains("\"target\":{\"selector\":"));
            assertTrue(lines.get(1).contains("\"kind\":\"ui.swipe\""));
            assertTrue(lines.get(1).contains("\"direction\":\"up\""));
            assertEquals("step scroll direction=up",
                InteractionSteps.scrollStep(540, 1800, 540, 800));

            input.keyEvent(serial, EmbeddedScrcpyClient.DeviceKey.APP_SWITCH);
            input.keyEvent(serial, EmbeddedScrcpyClient.DeviceKey.BACK);
            input.typeText(serial, "flowscope smoke");
        }
    }
}
