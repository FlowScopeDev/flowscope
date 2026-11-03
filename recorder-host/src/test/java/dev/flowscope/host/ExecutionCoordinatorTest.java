package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionCoordinatorTest {
    @TempDir Path temp;

    @Test void reservationsExcludeCompetitorsAndReleaseAfterClose() throws Exception {
        try (var first = DeviceLease.acquire(temp, "a")) {
            assertThrows(IllegalStateException.class, () -> DeviceLease.acquire(temp, "a"));
            try (var other = DeviceLease.acquire(temp, "b")) { assertNotNull(other); }
        }
        try (var again = DeviceLease.acquire(temp, "a")) { assertNotNull(again); }
    }

    @Test void nestedRunnerSharesReservationAndFailureReleasesIt() throws Exception {
        String serial = UUID.randomUUID().toString();
        assertThrows(Exception.class, () -> ExecutionCoordinator.withDevice(serial, () -> {
            assertTrue(ExecutionCoordinator.withDevice(serial, () -> true));
            assertThrows(IllegalStateException.class, () -> DeviceLease.acquire(serial));
            throw new Exception("job failed");
        }));
        try (var again = DeviceLease.acquire(serial)) { assertNotNull(again); }
    }

    @Test void queuesOverlapButFailuresOnlyStopTheirOwnQueue() throws Exception {
        var started = new CountDownLatch(2);
        Callable<Boolean> barrier = () -> {
            started.countDown();
            assertTrue(started.await(5, TimeUnit.SECONDS), "Different devices must overlap");
            return true;
        };
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var results = ExecutionCoordinator.run(List.of(
            new ExecutionCoordinator.Queue("one", a, List.of(barrier, () -> false,
                () -> { fail("Stopped queue ran another job"); return true; }), false),
            new ExecutionCoordinator.Queue("two", b, List.of(barrier, () -> true), false)), 2);
        assertEquals(List.of(ExecutionCoordinator.Status.PASSED, ExecutionCoordinator.Status.FAILED,
            ExecutionCoordinator.Status.SKIPPED), results.getFirst().jobs());
        assertEquals(List.of(ExecutionCoordinator.Status.PASSED, ExecutionCoordinator.Status.PASSED),
            results.get(1).jobs());
        try (var released = DeviceLease.acquire(a)) { assertNotNull(released); }
    }

    @Test void validatesAssignmentsAndAcquiresAllDevicesBeforeStarting() throws Exception {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var queue = new ExecutionCoordinator.Queue("one", a, List.of(() -> {
            fail("Work started before all reservations succeeded"); return true;
        }), false);
        assertThrows(IllegalArgumentException.class,
            () -> ExecutionCoordinator.run(List.of(queue, queue), 2));
        try (var busy = DeviceLease.acquire(b)) {
            assertThrows(IllegalStateException.class, () -> ExecutionCoordinator.run(List.of(queue,
                new ExecutionCoordinator.Queue("two", b, List.of(() -> true), false)), 2));
        }
        try (var released = DeviceLease.acquire(a)) { assertNotNull(released); }
    }

    @Test void exceptionsFailOnlyTheirQueueAndContinuePolicyIsHonoured() throws Exception {
        var result = ExecutionCoordinator.run(List.of(new ExecutionCoordinator.Queue("one",
            UUID.randomUUID().toString(), List.of(() -> { throw new Exception("failed"); },
                () -> true), true)), 1);
        assertEquals(List.of(ExecutionCoordinator.Status.FAILED, ExecutionCoordinator.Status.PASSED),
            result.getFirst().jobs());
    }

    public static final class LockProcess {
        public static void main(String[] args) throws Exception {
            try (var lease = DeviceLease.acquire(Path.of(args[0]), "cross-process")) {
                System.out.println("locked");
                System.out.flush();
                System.in.read();
            }
        }
    }

    @Test void operatingSystemReleasesReservationAfterProcessDeath() throws Exception {
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(DeviceLease.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String tests = Path.of(LockProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process child = new ProcessBuilder(javaBinary, "-cp", classes + java.io.File.pathSeparator + tests,
            LockProcess.class.getName(), temp.toString()).redirectErrorStream(true).start();
        try {
            assertEquals("locked", child.inputReader().readLine());
            assertThrows(IllegalStateException.class, () -> DeviceLease.acquire(temp, "cross-process"));
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        }
        try (var recovered = DeviceLease.acquire(temp, "cross-process")) { assertNotNull(recovered); }
    }
}
