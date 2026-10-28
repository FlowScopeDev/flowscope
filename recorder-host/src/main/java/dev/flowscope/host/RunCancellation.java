package dev.flowscope.host;

/** One run's stop signal. Only ordinary steps bind a worker; cleanup is never interrupted. */
public final class RunCancellation {
    private static final ThreadLocal<RunCancellation> CURRENT = new ThreadLocal<>();
    static void checkCurrent() throws InterruptedException {
        RunCancellation signal = CURRENT.get();
        if (signal != null && signal.isRequested()) throw new InterruptedException("Run cancelled");
    }
    private boolean requested;
    private boolean finished;
    private Thread worker;

    public synchronized boolean request() {
        if (finished || requested) return false;
        requested = true;
        if (worker != null) worker.interrupt();
        return true;
    }

    public synchronized boolean isRequested() { return requested; }

    /** Freeze the verdict before writing reports. Late clicks must not alter a completed run. */
    public synchronized void finish() { finished = true; }

    @FunctionalInterface
    interface Work<T> { T run() throws Exception; }

    <T> T execute(Work<T> work) throws Exception {
        synchronized (this) {
            if (requested) throw new Cancelled();
            if (worker != null) throw new IllegalStateException("Cancellation worker already bound");
            worker = Thread.currentThread();
            CURRENT.set(this);
        }
        try {
            T result = work.run();
            synchronized (this) {
                if (requested) throw new Cancelled();
            }
            return result;
        } catch (Exception failure) {
            if (isRequested()) throw new Cancelled();
            throw failure;
        } finally {
            synchronized (this) {
                worker = null;
                CURRENT.remove();
                // Consume our interrupt before cleanup or a pooled worker's next job.
                if (requested) Thread.interrupted();
            }
        }
    }

    static final class Cancelled extends Exception {
        Cancelled() { super("Cancelled by user"); }
    }
}
