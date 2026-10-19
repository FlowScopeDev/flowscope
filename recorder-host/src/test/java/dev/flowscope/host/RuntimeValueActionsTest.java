package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeValueActionsTest {
    @TempDir Path temp;

    private RuntimeValues values(Map<String, String> environment, Map<String, String> secrets) {
        return new RuntimeValues(environment, secrets, Map.of(), "Dates", "SERIAL",
            Instant.parse("2026-10-19T12:34:56Z"), UUID.fromString("00000000-0000-4000-8000-000000000001"),
            Map.of());
    }

    @Test
    void computesDatesGeneratedValuesAndTransformsWithoutReturningValuesInMetadata() throws Exception {
        RuntimeValues values = values(Map.of(), Map.of());
        HostRuntime runtime = new HostRuntime(values, emptyHttp(), temp,
            Clock.fixed(Instant.parse("2026-10-19T12:34:56Z"), ZoneOffset.UTC), new SecureRandom());

        Map<String, String> date = runtime.execute("set-date", Map.of(
            "name", "start", "value", "now", "offset", "-7d", "start-of", "day",
            "zone", "UTC", "format", "yyyy-MM-dd"), 1000);
        assertEquals("2026-10-12", values.interpolate("${var.start}"));
        assertEquals("hidden", date.get("value"));

        runtime.execute("set-value", Map.of("name", "id", "generate", "uuid"), 1000);
        assertDoesNotThrow(() -> UUID.fromString(values.interpolate("${var.id}")));
        runtime.execute("transform", Map.of("name", "upper", "from", " hello ",
            "operation", "trim"), 1000);
        assertEquals("hello", values.interpolate("${var.upper}"));
    }

    @Test
    void secretsStayTaintedThroughAliasesAndTransforms() throws Exception {
        RuntimeValues values = values(Map.of(), Map.of("API_JWT", "sentinel-jwt"));
        HostRuntime runtime = new HostRuntime(values, emptyHttp(), temp);
        runtime.execute("secret", Map.of("name", "jwt", "source", "API_JWT"), 1000);
        runtime.execute("transform", Map.of("name", "encoded", "from", "${secret.jwt}",
            "operation", "base64-encode"), 1000);

        assertTrue(values.variable("encoded").secret());
        assertFalse(values.redact("token=sentinel-jwt").contains("sentinel-jwt"));
    }

    @Test
    void apiEncodesQueriesAssertsHeadersAndCapturesHeaderAndJsonValues() throws Exception {
        RuntimeValues values = values(Map.of(), Map.of());
        java.util.List<URI> uris = new java.util.ArrayList<>();
        FlowHttpClient http = (method, uri, headers, body, timeout) -> {
            uris.add(uri);
            return new FlowHttpClient.Response(200,
                "{\"data\":{\"items\":[1,2],\"count\":2,\"reference\":\"TEST-1\"}}",
                Map.of("X-Session-Token", List.of("private-token"),
                    "Content-Type", List.of("application/json")), 42);
        };
        HostRuntime runtime = new HostRuntime(values, http, temp);
        Map<String, String> details = runtime.execute("api-request", Map.ofEntries(
            Map.entry("method", "GET"), Map.entry("url", "https://example.com/items"),
            Map.entry("query.search", "hello world"), Map.entry("expect-status", "200"),
            Map.entry("expect-header.Content-Type", "application/json"),
            Map.entry("expect-json-length.data.items", "2"),
            Map.entry("expect-json-gte.data.count", "2"),
            Map.entry("expect-json-prefix.data.reference", "TEST-"),
            Map.entry("capture.id", "data.count"),
            Map.entry("capture-secret-header.token", "X-Session-Token")), 1000);

        assertEquals("search={redacted}", details.get("url").split("\\?", 2)[1]);
        assertTrue(uris.getFirst().toString().contains("search=hello+world"));
        assertEquals("2", values.interpolate("${var.id}"));
        assertTrue(values.variable("token").secret());
    }

    @Test
    void numericAssertionsAcceptDecimalAndExponentJsonNumbers() throws Exception {
        RuntimeValues values = values(Map.of(), Map.of());
        FlowHttpClient http = (method, uri, headers, body, timeout) ->
            new FlowHttpClient.Response(200, "{\"balance\":10.5,\"duration\":1e2}", Map.of(), 4);
        HostRuntime runtime = new HostRuntime(values, http, temp);

        runtime.execute("api-request", Map.of(
            "method", "GET",
            "url", "https://example.test/values",
            "expect-json-gt.balance", "10.4",
            "expect-json-lte.duration", "100"
        ), 1_000);
    }

    @Test
    void conditionsSupportPresenceAbsenceAndEquality() {
        RuntimeValues values = values(Map.of("REGION", "india"), Map.of("TOKEN", "x"));
        values.put("environment", "staging", false);
        assertTrue(values.conditionsPass(Map.of("if-var", "environment",
            "if-var.environment", "staging", "if-env.REGION", "india", "if-secret", "TOKEN")));
        assertFalse(values.conditionsPass(Map.of("unless-var", "environment")));
    }

    private static FlowHttpClient emptyHttp() {
        return (method, uri, headers, body, timeout) -> new FlowHttpClient.Response(200, "{}");
    }
}
