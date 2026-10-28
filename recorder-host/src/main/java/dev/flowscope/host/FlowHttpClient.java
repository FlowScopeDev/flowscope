package dev.flowscope.host;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;

/** Small injectable boundary around Java's host-side HTTP client. */
interface FlowHttpClient {
    int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    Response send(String method, URI uri, Map<String, String> headers, String body,
                  long timeoutMillis) throws Exception;

    record Response(int status, String body, Map<String, List<String>> headers,
                    long durationMillis) {
        Response(int status, String body) { this(status, body, Map.of(), 0); }
    }

    static FlowHttpClient platform() {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        return (method, uri, headers, body, timeoutMillis) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMillis));
            headers.forEach(request::header);
            if (body != null && headers.keySet().stream()
                .noneMatch(name -> name.equalsIgnoreCase("Content-Type"))) {
                request.header("Content-Type", "application/json");
            }
            HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
            request.method(method, publisher);
            long started = System.nanoTime();
            var pending = client.sendAsync(request.build(), info -> new LimitedBody());
            HttpResponse<byte[]> response;
            try {
                response = pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | TimeoutException stopped) {
                pending.cancel(true);
                if (stopped instanceof InterruptedException interrupted) throw interrupted;
                throw new java.net.http.HttpTimeoutException("API request timed out");
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof Exception cause) throw cause;
                throw failed;
            }
            byte[] bytes = response.body();
            return new Response(response.statusCode(), new String(bytes,
                java.nio.charset.StandardCharsets.UTF_8), response.headers().map(),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        };
    }

    /** Bound memory while waiting interruptibly for the complete body, including slow servers. */
    final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        private boolean done;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (ByteBuffer buffer : buffers) size += buffer.remaining();
            if (size > MAX_RESPONSE_BYTES) {
                done = true;
                subscription.cancel();
                delegate.onError(new IllegalStateException("API response exceeded 2 MiB"));
            } else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!done) { done = true; delegate.onError(error); } }
        public void onComplete() { if (!done) { done = true; delegate.onComplete(); } }
    }
}
