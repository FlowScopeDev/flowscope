package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmulatorClientTest {
    @TempDir Path temp;

    @Test
    void parsesAvdListAndSkipsNoise() {
        assertEquals(List.of("Pixel_7", "Tablet_API_34"),
            EmulatorClient.parseAvds("INFO    | Storing crashdata\nPixel_7\n\n  Tablet_API_34  \n"));
        assertEquals(List.of(), EmulatorClient.parseAvds(""));
    }

    @Test
    void parsesAvdNameBeforeOk() {
        assertEquals("Pixel_7", EmulatorClient.parseAvdName("Pixel_7\r\nOK\r\n"));
        assertNull(EmulatorClient.parseAvdName("OK\n"));
    }

    @Test
    void headlessAddsNoWindowFlags() {
        assertEquals(List.of("emu", "-avd", "Pixel_7"), EmulatorClient.command("emu", "Pixel_7", false));
        assertEquals(List.of("emu", "-avd", "Pixel_7", "-no-window", "-no-audio", "-no-boot-anim"),
            EmulatorClient.command("emu", "Pixel_7", true));
    }

    @Test
    void resolvesExplicitThenSdkThenPath() throws IOException {
        Path sdk = temp.resolve("sdk");
        Path binary = sdk.resolve("emulator/emulator");
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "#!/bin/sh\n");
        assertTrue(binary.toFile().setExecutable(true));

        assertEquals("/custom/emulator",
            EmulatorClient.resolveExecutable("/custom/emulator", sdk.toString(), null, null, null, null, "Linux"));
        assertEquals(binary.toString(),
            EmulatorClient.resolveExecutable(null, sdk.toString(), null, null, null, null, "Linux"));
        assertEquals(binary.toString(),
            EmulatorClient.resolveExecutable(null, null, sdk.toString(), null, null, null, "Linux"));
        assertEquals(binary.toString(), EmulatorClient.resolveExecutable(null, null, null, null,
            binary.getParent().toString(), temp.resolve("nohome").toString(), "Linux"));
        assertNull(EmulatorClient.resolveExecutable(null, null, null, null, "", temp.resolve("nohome").toString(),
            "Linux"));
    }

    @Test
    void registryRoundTripPrunesDeadProcesses() throws IOException {
        EmulatorRegistry registry = new EmulatorRegistry(temp.resolve("emulators.json"));
        long self = ProcessHandle.current().pid();
        registry.add(new EmulatorRegistry.Entry("Pixel_7", self));
        registry.add(new EmulatorRegistry.Entry("Gone", Long.MAX_VALUE - 1));
        assertEquals(List.of(new EmulatorRegistry.Entry("Pixel_7", self)), registry.read());
        assertEquals(self, registry.find("Pixel_7").pid());
        registry.remove(self);
        assertEquals(List.of(), registry.read());
    }

    @Test
    void registryRejectsMalformedFiles() throws IOException {
        Path file = temp.resolve("emulators.json");
        EmulatorRegistry registry = new EmulatorRegistry(file);
        Files.writeString(file, "{\"emulators\":[{\"avd\":\"x\",\"pid\":1,\"extra\":true}]}");
        assertThrows(IllegalArgumentException.class, registry::read);
        Files.writeString(file, "{\"emulators\":[{\"avd\":\"x\"}]}");
        assertThrows(IllegalArgumentException.class, registry::read);
        Files.writeString(file, "{\"emulators\":[],}");
        assertThrows(IllegalArgumentException.class, registry::read);
    }

    @Test
    void waitForBootPollsUntilBootCompleted() throws Exception {
        FakeAdb adb = new FakeAdb("Pixel_7", 2);
        EmulatorClient client = client("emu");
        client.pollEvery(1);
        assertEquals("emulator-5554", client.waitForBoot(adb, "Pixel_7", 5_000));
        assertEquals(3, adb.bootChecks);
    }

    @Test
    void waitForBootTimesOutWhenNeverBooted() {
        FakeAdb adb = new FakeAdb("Pixel_7", Integer.MAX_VALUE);
        EmulatorClient client = client("emu");
        client.pollEvery(5);
        assertThrows(IOException.class, () -> client.waitForBoot(adb, "Pixel_7", 50));
    }

    @Test
    void stopRefusesEmulatorsFlowScopeDidNotLaunch() {
        FakeAdb adb = new FakeAdb("Pixel_7", 0);
        assertThrows(IllegalArgumentException.class, () -> client("emu").stop(adb, "emulator-5554"));
        assertFalse(adb.commands.stream().anyMatch(command -> command.contains("kill")));
    }

    @Test
    void missingBinaryIsARuntimeFailure() {
        EmulatorClient client = new EmulatorClient(null, new EmulatorRegistry(temp.resolve("r.json")), temp);
        assertThrows(IllegalStateException.class, client::listAvds);
    }

    @Test
    void emulatorCommandValidatesInput() throws Exception {
        assertEquals(2, Main.emulator(new FakeAdb("x", 0), client("emu"), List.of()));
        assertEquals(2, Main.emulator(new FakeAdb("x", 0), client("emu"), List.of("start")));
        assertEquals(2, Main.emulator(new FakeAdb("x", 0), client("emu"), List.of("stop")));
        assertEquals(2, Main.emulator(new FakeAdb("x", 0), client("emu"), List.of("reboot")));
        assertThrows(IllegalArgumentException.class,
            () -> Main.emulator(new FakeAdb("x", 0), client("emu"), List.of("start", "a", "--bogus")));
    }

    private EmulatorClient client(String executable) {
        return new EmulatorClient(executable, new EmulatorRegistry(temp.resolve("emulators.json")), temp);
    }

    /** One running emulator that reports boot completed after {@code bootAfter} polls. */
    private static final class FakeAdb extends AdbClient {
        private final String avd;
        private final int bootAfter;
        int bootChecks;
        final List<String> commands = new ArrayList<>();

        FakeAdb(String avd, int bootAfter) {
            this.avd = avd;
            this.bootAfter = bootAfter;
        }

        @Override
        public CommandResult run(List<String> arguments, long timeoutMillis) {
            String joined = String.join(" ", arguments);
            commands.add(joined);
            if (joined.equals("devices -l")) {
                return new CommandResult(0,
                    "List of devices attached\nemulator-5554          device product:sdk model:sdk\n", "");
            }
            if (joined.endsWith("emu avd name")) return new CommandResult(0, avd + "\nOK\n", "");
            if (joined.endsWith("getprop sys.boot_completed")) {
                return new CommandResult(0, bootChecks++ >= bootAfter ? "1\n" : "\n", "");
            }
            return new CommandResult(0, "", "");
        }
    }
}
