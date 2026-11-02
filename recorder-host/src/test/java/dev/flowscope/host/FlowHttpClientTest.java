package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FlowHttpClientTest {
    @Test
    void platformClientSupportsEveryDocumentedMethodHeadersAndBodies() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(exchange.getRequestMethod() + ":" + exchange.getRequestHeaders().getFirst("X-Test")
                + ":" + body);
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, exchange.getRequestMethod().equals("HEAD") ? -1 : response.length);
            if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
            for (String method : new String[] {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"}) {
                String body = method.equals("GET") || method.equals("HEAD") ? null : "{\"x\":1}";
                FlowHttpClient.Response response = FlowHttpClient.platform().send(method, uri,
                    Map.of("X-Test", "yes"), body, 2_000);
                assertEquals(200, response.status());
                assertEquals(method + ":yes:" + (body == null ? "" : body), seen.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRequestReportsTheOnlyTwoBoundariesItCanActuallySee() throws Exception {
        // There is no connect or TLS hook in java.net.http, so everything before the response
        // headers is one stage. Reporting a `connecting` stage would be inventing a measurement.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
            List<FlowHttpClient.HttpStage> stages = new CopyOnWriteArrayList<>();
            FlowHttpClient.Response response = FlowHttpClient.platform().send("GET", uri, Map.of(),
                null, 2_000, stages::add);

            assertEquals(200, response.status());
            assertEquals(FlowHttpClient.HttpStage.AWAITING_RESPONSE, stages.getFirst());
            assertTrue(stages.contains(FlowHttpClient.HttpStage.RECEIVING_BODY), stages.toString());
            // Once per response, not once per chunk: a 2 MiB body arrives in well over a hundred.
            assertEquals(1, stages.stream()
                .filter(FlowHttpClient.HttpStage.RECEIVING_BODY::equals).count(), stages.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theRequestWithoutAStageSinkStillWorks() throws Exception {
        // Ten or so test fakes are plain five-argument lambdas; the reporting overload is defaulted
        // so none of them has to know stages exist.
        FlowHttpClient lambda = (method, uri, headers, body, timeout) ->
            new FlowHttpClient.Response(204, "");
        assertEquals(204, lambda.send("GET", URI.create("http://127.0.0.1/x"), Map.of(), null, 10)
            .status());
        assertEquals(204, lambda.send("GET", URI.create("http://127.0.0.1/x"), Map.of(), null, 10,
            FlowHttpClient.IGNORED).status());
    }
}
