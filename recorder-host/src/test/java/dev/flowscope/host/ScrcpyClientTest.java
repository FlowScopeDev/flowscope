package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScrcpyClientTest {
    @Test
    void interactiveMirrorTargetsExactlyOneDevice() {
        List<String> command = ScrcpyClient.command("scrcpy", "device-1", false);

        assertEquals("scrcpy", command.getFirst());
        assertTrue(command.containsAll(List.of("-s", "device-1", "--no-audio", "--stay-awake")));
        assertFalse(command.contains("--no-control"));
    }

    @Test
    void readOnlyMirrorDisablesInput() {
        assertTrue(ScrcpyClient.command("scrcpy.exe", "emulator-5554", true)
            .contains("--no-control"));
    }
}
