package dev.flowscope.host;

import java.io.PrintStream;
import java.util.concurrent.Callable;

/** Per-worker attribution without replacing process-wide stdout or altering legacy output. */
final class RunOutput {
    private static final ThreadLocal<String> PREFIX = new ThreadLocal<>();
    private RunOutput() {}
    static <T> T attributed(String prefix, Callable<T> work) throws Exception {
        String old = PREFIX.get();
        PREFIX.set(prefix);
        try { return work.call(); }
        finally { if (old == null) PREFIX.remove(); else PREFIX.set(old); }
    }
    static void out(String format, Object... args) { write(System.out, format, args); }
    static void err(String format, Object... args) { write(System.err, format, args); }
    private static void write(PrintStream stream, String format, Object... args) {
        String prefix = PREFIX.get();
        String message = String.format(format, args);
        stream.print(prefix == null ? message : prefix + " " + message);
    }
}
