package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiautomatorCleanupTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    @Test
    void aHungAdbCallIsKilledAfterTheTimeout() {
        assumeTrue(java.nio.file.Files.isExecutable(Path.of("/bin/sleep")));
        AdbClient hung = new AdbClient("/bin/sleep");
        long begin = System.nanoTime();
        assertThrows(IOException.class, () -> hung.run(List.of("30"), 200));
        assertTrue((System.nanoTime() - begin) / 1_000_000 < 10_000, "timeout must not wait for the process");
    }

    @Test
    void onlyProcessesStartedAfterTheSnapshotAreKilled() throws Exception {
        PidAdb adb = new PidAdb("100 200");
        adb.pids.add("300");
        assertEquals(1, adb.killNewUiautomator("TEST", Set.of("100", "200")));
        assertEquals(List.of("kill 300"), adb.kills());
    }

    @Test
    void nothingIsKilledWhenThereAreNoStrays() throws Exception {
        PidAdb adb = new PidAdb("100");
        assertEquals(0, adb.killNewUiautomator("TEST", Set.of("100")));
        assertEquals(List.of(), adb.kills());
    }

    @Test
    void aSurvivingProcessIsEscalatedToSigkill() throws Exception {
        PidAdb adb = new PidAdb("");
        adb.pids.add("300");
        adb.survivesTerm = true;
        adb.killNewUiautomator("TEST", Set.of());
        assertEquals(List.of("kill 300", "kill -9 300"), adb.kills());
    }

    @Test
    void aRunSweepsStraysItLeftBehind() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step force-stop
            """, "sweep.flow");
        PidAdb adb = new PidAdb("100");
        adb.spawnOnForceStop = "555";
        FlowTestRunner runner = new FlowTestRunner(adb, DEVICE, test, temp, "run",
            FlowHttpClient.platform(), Map.of());
        assertTrue(runner.run());
        assertEquals(List.of("kill 555"), adb.kills());
    }

    @Test
    void aRunWithNoStraysIssuesNoKill() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step force-stop
            """, "clean.flow");
        PidAdb adb = new PidAdb("100");
        assertTrue(new FlowTestRunner(adb, DEVICE, test, temp, "run",
            FlowHttpClient.platform(), Map.of()).run());
        assertFalse(adb.commands.stream().anyMatch(command -> command.contains("kill")));
    }

    /** Serves `pidof uiautomator` from a mutable set and records every kill. */
    private static final class PidAdb extends AdbClient {
        final Set<String> pids = new LinkedHashSet<>();
        final List<String> commands = new ArrayList<>();
        boolean survivesTerm;
        String spawnOnForceStop;

        PidAdb(String initial) {
            super("unused");
            if (!initial.isBlank()) pids.addAll(List.of(initial.split(" ")));
        }

        List<String> kills() {
            return commands.stream().filter(command -> command.contains(" kill ") || command.startsWith("kill"))
                .map(command -> command.substring(command.indexOf("kill"))).toList();
        }

        @Override
        public CommandResult run(List<String> arguments, long timeoutMillis) {
            String joined = String.join(" ", arguments);
            commands.add(joined);
            if (joined.endsWith("pidof uiautomator")) {
                return new CommandResult(pids.isEmpty() ? 1 : 0, String.join(" ", pids) + "\n", "");
            }
            if (joined.contains("shell kill ")) {
                String[] parts = joined.split(" ");
                String pid = parts[parts.length - 1];
                if (!(survivesTerm && !joined.contains("-9"))) pids.remove(pid);
            }
            if (joined.contains("am force-stop") && spawnOnForceStop != null) pids.add(spawnOnForceStop);
            if (joined.contains("uiautomator")) return new CommandResult(0, "dumped", "");
            return new CommandResult(0, "Success", "");
        }

        @Override
        public byte[] execOut(String serial, String... command) {
            return "<?xml version=\"1.0\"?><hierarchy rotation=\"0\"/>".getBytes();
        }
    }
}
