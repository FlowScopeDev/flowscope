package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
}
