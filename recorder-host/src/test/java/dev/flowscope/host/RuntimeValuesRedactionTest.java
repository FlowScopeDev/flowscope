package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeValuesRedactionTest {
    @TempDir Path temp;

    private RuntimeValues values(Map<String, String> environment, Map<String, String> secrets) {
        return new RuntimeValues(environment, secrets, Map.of(), "Test 1", "DEVICE-01",
            Instant.EPOCH, UUID.randomUUID(), Map.of());
    }

    @Test
    void unusedEnvironmentAndFileEntriesDoNotRewriteDiagnostics() {
        RuntimeValues values = values(Map.of("MallocNanoZone", "0", "SHLVL", "1",
            "UNRELATED", "item"), Map.of("UNUSED_SECRET", "id"));
        String diagnostic = "Element not found: [id=item_1] after 10 swipes";

        assertEquals(diagnostic, values.redact(diagnostic));
        assertEquals("Test 1 DEVICE-01 1", values.interpolate(
            "${test.name} ${device.serial} ${test.attempt}"));
        assertEquals(diagnostic, values.redact(diagnostic));
    }

    @Test
    void preflightProtectsOnlyReferencedSourcesBeforeAnyInterpolation() {
        RuntimeValues values = values(Map.of("TOKEN", "env-sentinel", "SHLVL", "1"),
            Map.of("FILE_TOKEN", "file-sentinel", "UNUSED", "0"));
        values.preflightReferences("Bearer ${env.TOKEN} ${secret.FILE_TOKEN}");

        assertEquals("{secret} {secret} id=item_10", values.redact(
            "env-sentinel file-sentinel id=item_10"));
    }

    @Test
    void interpolationProtectsShortSourcesWithoutNeedingPreflight() {
        RuntimeValues values = values(Map.of("PIN", "1"), Map.of("TOKEN", "x"));
        assertEquals("1 x", values.interpolate("${env.PIN} ${secret.TOKEN}"));
        assertEquals("pin={secret} token={secret}", values.redact("pin=1 token=x"));
    }

    @Test
    void requirementsAndSecretBindingsOptSourcesIntoRedaction() {
        RuntimeValues values = values(Map.of("REQUIRED", "env-sentinel"),
            Map.of("DECLARED", "declared-sentinel", "BOUND", "bound-sentinel"));
        values.require(new FlowTest.Requirement(1, "env", "REQUIRED"));
        values.require(new FlowTest.Requirement(2, "secret", "DECLARED"));
        values.bindSecret("token", "BOUND");

        assertEquals("{secret} {secret} {secret}", values.redact(
            "env-sentinel declared-sentinel bound-sentinel"));
    }

    @Test
    void environmentOverrideDoesNotActivateTheShadowedFileValue() {
        RuntimeValues values = values(Map.of("TOKEN", "effective-sentinel"),
            Map.of("TOKEN", "shadowed-sentinel"));
        values.bindSecret("alias", "TOKEN");
        assertEquals("effective-sentinel", values.interpolate("${secret.alias}"));
        assertEquals("{secret} shadowed-sentinel", values.redact(
            "effective-sentinel shadowed-sentinel"));
    }

    @Test
    void overlappingValuesAreMatchedLongestFirstRegardlessOfInsertionOrder() {
        RuntimeValues values = values(Map.of(), Map.of());
        values.put("short", "abc", false);
        values.put("long", "abcdef", true);
        assertEquals("{secret} {runtime}", values.redact("abcdef abc"));
    }

    @Test
    void replacementsAreLiteralAndNeverReprocessed() {
        RuntimeValues values = values(Map.of(), Map.of());
        values.put("token", "$a.b\\[x]", true);
        values.put("markerWord", "secret", false);
        values.put("otherMarkerWord", "runtime", true);

        assertEquals("{secret} {runtime} {secret}", values.redact("$a.b\\[x] secret runtime"));
    }

    @Test
    void overwrittenValuesRemainProtectedAndSecretTaintWinsForEqualValues() {
        RuntimeValues values = values(Map.of(), Map.of("FIRST", "first-sentinel",
            "SECOND", "second-sentinel"));
        values.bindSecret("token", "FIRST");
        values.bindSecret("token", "SECOND");
        values.put("id", "old-id", true);
        values.put("id", "new-id", false);
        values.put("duplicate", "old-id", false);

        assertEquals("{secret} {secret} {secret} {runtime}", values.redact(
            "first-sentinel second-sentinel old-id new-id"));
    }

    @Test
    void generatedDatesAndTransformedSecretsRemainProtected() throws Exception {
        RuntimeValues values = values(Map.of(), Map.of("TOKEN", "sentinel-value"));
        HostRuntime runtime = new HostRuntime(values, (m, u, h, b, t) ->
            new FlowHttpClient.Response(200, "{}"), temp);
        runtime.execute("set-value", Map.of("name", "id", "generate", "uuid"), 1000);
        runtime.execute("set-date", Map.of("name", "day", "value", "2026-10-19",
            "format", "yyyy-MM-dd"), 1000);
        runtime.execute("transform", Map.of("name", "encoded", "from", "${secret.TOKEN}",
            "operation", "base64-encode"), 1000);

        String id = values.variable("id").text();
        String encoded = Base64.getEncoder().encodeToString("sentinel-value".getBytes(
            java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(values.variable("encoded").secret());
        assertEquals("{runtime} {runtime} {secret} {secret}", values.redact(
            id + " 2026-10-19 sentinel-value " + encoded));
    }

    @Test
    void suiteRedactionHistoryIsSharedWithoutSharingTestVariables() {
        RuntimeValues suite = values(Map.of("TOKEN", "suite-sentinel"), Map.of());
        suite.preflightReferences("${env.TOKEN}");
        suite.put("fixture", "fixture-sentinel", false);
        RuntimeValues child = new RuntimeValues(Map.of(), Map.of(), suite.variables(), "Child",
            "DEVICE", Instant.EPOCH, UUID.randomUUID(), Map.of(), suite.redactor());
        child.put("local", "child-sentinel", true);
        child.put("local", "new-sentinel", false);

        assertFalse(suite.hasVariable("local"));
        assertEquals("fixture-sentinel", child.interpolate("${suite.var.fixture}"));
        String message = "suite-sentinel fixture-sentinel child-sentinel new-sentinel";
        assertEquals("{secret} {runtime} {secret} {runtime}", child.redact(message));
        assertEquals(child.redact(message), suite.redact(message));
    }

    @Test
    void equalityGuardsProtectConsumedEnvironmentButPresenceChecksDoNot() {
        RuntimeValues values = values(Map.of("TOKEN", "sentinel", "SHLVL", "1"), Map.of());
        assertTrue(values.conditionsPass(Map.of("if-env", "SHLVL")));
        assertTrue(values.conditionsPass(Map.of("if-env.TOKEN", "sentinel")));
        assertEquals("{secret} item_1", values.redact("sentinel item_1"));
    }

    @Test
    void emptyAndMissingValuesAreSafeAndWhitespaceSecretsAreStillProtected() {
        RuntimeValues values = values(Map.of("EMPTY", "", "SPACE", " "), Map.of());
        values.preflightReferences("${env.EMPTY} ${env.SPACE}");
        assertThrows(IllegalArgumentException.class,
            () -> values.preflightReferences("${env.MISSING}"));
        assertEquals("", values.redact(null));
        assertEquals("a{secret}b", values.redact("a b"));
    }
}
