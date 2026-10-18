package dev.flowscope.host;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Small injectable boundary around Java's host-side HTTP client. */
interface FlowHttpClient {
    int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    Response send(String method, URI uri, Map<String, String> headers, String body,
                  long timeoutMillis) throws Exception;

    record Response(int status, String body) {}

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
            HttpResponse<InputStream> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("API response exceeded 2 MiB");
            }
            return new Response(response.statusCode(), new String(bytes,
                java.nio.charset.StandardCharsets.UTF_8));
        };
    }
}
