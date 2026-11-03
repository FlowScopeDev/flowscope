package dev.flowscope.host;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stop signals for one owned execution. Cancellation never interrupts runner cleanup. */
public final class ExecutionCancellation {
    private record Entry(String role, RunCancellation signal) {}
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> requested = new HashSet<>();
    private boolean all;
    private boolean finished;

    public synchronized boolean request(String role) {
        if (finished || !requested.add(role)) return false;
        for (Entry entry : entries) if (entry.role().equals(role)) entry.signal().request();
        return true;
    }
    public synchronized boolean requestAll() {
        if (finished || all) return false;
        all = true;
        for (Entry entry : entries) entry.signal().request();
        return true;
    }
    synchronized void register(String role, RunCancellation signal) {
        entries.add(new Entry(role, signal));
        if (isRequested(role)) signal.request();
    }
    public synchronized boolean isRequested(String role) { return all || requested.contains(role); }
    synchronized void finish() { finished = true; entries.clear(); }
}
