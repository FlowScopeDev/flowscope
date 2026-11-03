package dev.flowscope.host;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Shared host scheduling foundation; jobs own their runner, output, secrets and cancellation. */
public final class ExecutionCoordinator {
    private ExecutionCoordinator() {}
    private static final ThreadLocal<String> OWNED = new ThreadLocal<>();

    public record Queue(String project, String serial, List<Callable<Boolean>> jobs,
                        boolean continueOnFailure) {
        public Queue {
            if (project == null || project.isBlank() || serial == null || serial.isBlank())
                throw new IllegalArgumentException("Project and device are required");
            jobs = List.copyOf(jobs);
        }
    }
    public enum Status { PASSED, FAILED, SKIPPED }
    public record Result(String project, String serial, List<Status> jobs) {
        public Result { jobs = List.copyOf(jobs); }
    }

    /** Nested suite children share the owning thread's reservation. */
    public static <T> T withDevice(String serial, Callable<T> work) throws Exception {
        if (serial.equals(OWNED.get())) return work.call();
        if (OWNED.get() != null) throw new IllegalStateException("Cannot switch devices inside a reserved job");
        try (DeviceLease lease = DeviceLease.acquire(serial)) {
            OWNED.set(serial);
            try { return work.call(); }
            finally { OWNED.remove(); }
        }
    }

    /** Blocking call. All reservations are acquired before any job starts; results follow input order.
     * Job exceptions fail their queue entry. Callers cancel via each job's RunCancellation,
     * rather than interrupting workers and their always-run cleanup.
     */
    public static List<Result> run(List<Queue> queues, int maxParallel) throws Exception {
        queues = List.copyOf(queues);
        if (maxParallel < 1) throw new IllegalArgumentException("Parallelism must be positive");
        var serials = new HashSet<String>();
        for (Queue queue : queues) if (!serials.add(queue.serial()))
            throw new IllegalArgumentException("Duplicate device assignment: " + queue.serial());
        var leases = new ArrayList<DeviceLease>();
        try {
            for (Queue queue : queues) leases.add(DeviceLease.acquire(queue.serial()));
            try (var workers = Executors.newFixedThreadPool(maxParallel)) {
                var futures = new ArrayList<Future<Result>>();
                for (Queue queue : queues) futures.add(workers.submit(() -> {
                    OWNED.set(queue.serial());
                    try {
                        var statuses = new ArrayList<Status>();
                        boolean stopped = false;
                        for (Callable<Boolean> job : queue.jobs()) {
                            if (stopped) { statuses.add(Status.SKIPPED); continue; }
                            boolean passed;
                            try { passed = job.call(); }
                            catch (Exception failure) { passed = false; }
                            statuses.add(passed ? Status.PASSED : Status.FAILED);
                            stopped = !passed && !queue.continueOnFailure();
                        }
                        return new Result(queue.project(), queue.serial(), statuses);
                    } finally { OWNED.remove(); }
                }));
                var results = new ArrayList<Result>();
                for (Future<Result> future : futures) results.add(future.get());
                return List.copyOf(results);
            }
        } finally {
            IOExceptionCollector.close(leases);
        }
    }

    private static final class IOExceptionCollector {
        static void close(List<DeviceLease> leases) throws java.io.IOException {
            java.io.IOException failure = null;
            for (DeviceLease lease : leases) try { lease.close(); }
            catch (java.io.IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }
}
