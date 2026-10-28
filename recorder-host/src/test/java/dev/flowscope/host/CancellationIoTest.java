package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class CancellationIoTest {
    @TempDir Path temp;
    @Test void slowResponseBodyIsInterruptibleAndCannotRetry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch bodyStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('x'); exchange.getResponseBody().flush();
                bodyStarted.countDown(); release.await(5, TimeUnit.SECONDS);
            } catch (Exception ignored) { } finally { exchange.close(); }
        });
        server.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            RunCancellation signal = new RunCancellation();
            var future = executor.submit(() -> signal.execute(() -> FlowHttpClient.platform().send("GET",
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/slow"), Map.of(), null, 60_000)));
            assertTrue(bodyStarted.await(3, TimeUnit.SECONDS)); signal.request();
            assertInstanceOf(RunCancellation.Cancelled.class,
                assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS)).getCause());
            assertFalse(executor.submit(() -> Thread.currentThread().isInterrupted()).get());
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void boundedBodyRejectsOversizeResponses() {
        var body = new FlowHttpClient.LimitedBody();
        boolean[] cancelled = {false};
        body.onSubscribe(new Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { cancelled[0] = true; }
        });
        body.onNext(List.of(java.nio.ByteBuffer.allocate(FlowHttpClient.MAX_RESPONSE_BYTES + 1)));
        assertTrue(cancelled[0]);
        assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    }
    @Test void stalledExecOutIsKilledOnCancellation() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path pid = temp.resolve("pid"), executable = temp.resolve("adb");
        Files.writeString(executable, "#!/bin/sh\necho $$ > '" + pid + "'\nwhile :; do :; done\n");
        assertTrue(executable.toFile().setExecutable(true));
        RunCancellation signal = new RunCancellation();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> signal.execute(() -> new AdbClient(executable.toString()).execOut("TEST", "cat", "dump")));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(pid) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pid));
            long child = Long.parseLong(Files.readString(pid).strip()); signal.request();
            assertInstanceOf(RunCancellation.Cancelled.class,
                assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS)).getCause());
            ProcessHandle.of(child).ifPresent(process -> assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> process.onExit().get()));
        }
    }
}
