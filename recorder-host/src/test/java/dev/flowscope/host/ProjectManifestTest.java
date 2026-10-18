package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectManifestTest {
    @Test
    void parsesMinimalManifestAndAppliesDirectoryDefaults() {
        ProjectManifest manifest = parse("""
            {"schemaVersion": 1, "name": "Example app"}
            """);

        assertEquals(1, manifest.schemaVersion());
        assertEquals("Example app", manifest.name());
        assertEquals("flows", manifest.flowsDir());
        assertEquals("reports", manifest.reportsDir());
        assertNull(manifest.defaultSuite());
        assertEquals(ProjectManifest.Defaults.NONE, manifest.defaults());
    }

    @Test
    void parsesEveryField() {
        ProjectManifest manifest = parse("""
            {
              "schemaVersion": 1,
              "name": "Example customer app",
              "flowsDir": "automation/flows",
              "reportsDir": "out/reports",
              "defaults": {
                "package": "com.example.customer.debug",
                "mode": "instrumented",
                "timeout": 15000,
                "serial": "TESTSERIAL01"
              },
              "suite": "nightly.suite"
            }
            """);

        assertEquals("automation/flows", manifest.flowsDir());
        assertEquals("out/reports", manifest.reportsDir());
        assertEquals("com.example.customer.debug", manifest.defaults().packageName());
        assertEquals(CaptureMode.INSTRUMENTED, manifest.defaults().mode());
        assertEquals(15000L, manifest.defaults().timeoutMillis());
        assertEquals("TESTSERIAL01", manifest.defaults().serial());
        assertEquals("nightly.suite", manifest.defaultSuite());
    }

    @Test
    void explicitNullDefaultsAreTreatedAsUnset() {
        ProjectManifest manifest = parse("""
            {"schemaVersion": 1, "name": "x",
             "defaults": {"package": null, "mode": null, "timeout": null, "serial": null}}
            """);
        assertEquals(ProjectManifest.Defaults.NONE, manifest.defaults());
    }

    @Test
    void roundTripsThroughJson() {
        ProjectManifest original = new ProjectManifest(1, "Round \"trip\"", "flows", "reports",
            new ProjectManifest.Defaults("com.example.debug", CaptureMode.ZERO_CODE, 10000L, "ABC"),
            "nightly.suite");
        assertEquals(original, ProjectManifest.parse(original.toJson(), "flowscope.json"));
    }

    @Test
    void roundTripsWhenEverythingOptionalIsUnset() {
        ProjectManifest original = ProjectManifest.defaults("Scaffolded");
        assertEquals(original, ProjectManifest.parse(original.toJson(), "flowscope.json"));
    }

    @Test
    void rejectsMissingAndBlankName() {
        assertTrue(detail("{\"schemaVersion\": 1}").contains("name: is required"));
        assertTrue(detail("{\"schemaVersion\": 1, \"name\": \"  \"}")
            .contains("name must not be blank"));
    }

    @Test
    void rejectsMissingAndNewerSchemaVersion() {
        assertTrue(detail("{\"name\": \"x\"}").contains("schemaVersion is required"));
        assertEquals("schemaVersion 2 is newer than this FlowScope build",
            detail("{\"schemaVersion\": 2, \"name\": \"x\"}"));
    }

    @Test
    void rejectsUnknownKeysAtBothLevels() {
        assertTrue(detail("{\"schemaVersion\": 1, \"name\": \"x\", \"flowDir\": \"flows\"}")
            .contains("flowDir: unknown key"));
        assertTrue(detail(
            "{\"schemaVersion\": 1, \"name\": \"x\", \"defaults\": {\"packages\": \"a\"}}")
            .contains("defaults.packages: unknown key"));
    }

    @Test
    void rejectsDirectoriesThatEscapeTheProject() {
        assertTrue(detail("{\"schemaVersion\": 1, \"name\": \"x\", \"flowsDir\": \"/etc\"}")
            .contains("flowsDir must be relative"));
        assertTrue(detail("{\"schemaVersion\": 1, \"name\": \"x\", \"flowsDir\": \"../flows\"}")
            .contains("flowsDir must not contain '..'"));
        assertTrue(detail("{\"schemaVersion\": 1, \"name\": \"x\", \"reportsDir\": \"C:/out\"}")
            .contains("reportsDir must be relative"));
        assertTrue(detail("{\"schemaVersion\": 1, \"name\": \"x\", \"flowsDir\": \"a\\\\b\"}")
            .contains("flowsDir must use forward slashes"));
        assertTrue(detail("{\"schemaVersion\": 1, \"name\": \"x\", \"reportsDir\": \"\"}")
            .contains("reportsDir must not be blank"));
    }

    @Test
    void rejectsInvalidDefaults() {
        assertTrue(detail(
            "{\"schemaVersion\": 1, \"name\": \"x\", \"defaults\": {\"mode\": \"zero_code\"}}")
            .contains("defaults.mode: Unknown mode: zero_code"));
        assertTrue(detail(
            "{\"schemaVersion\": 1, \"name\": \"x\", \"defaults\": {\"timeout\": 0}}")
            .contains("defaults.timeout must be positive"));
        assertTrue(detail(
            "{\"schemaVersion\": 1, \"name\": \"x\", \"defaults\": {\"timeout\": \"10000\"}}")
            .contains("defaults.timeout: must be an integer"));
        assertTrue(detail(
            "{\"schemaVersion\": 1, \"name\": \"x\", \"defaults\": {\"package\": \" \"}}")
            .contains("defaults.package must not be blank"));
    }

    @Test
    void wrapsMalformedJsonAsAManifestError() {
        ProjectManifest.ManifestError failure = assertThrows(ProjectManifest.ManifestError.class,
            () -> ProjectManifest.parse("{\"name\": \"x\",}", "flowscope.json"));
        assertEquals("flowscope.json", failure.origin());
        assertTrue(failure.detail().contains("trailing comma"));
        assertTrue(failure.getMessage().startsWith("flowscope.json: "));
    }

    private static ProjectManifest parse(String json) {
        return ProjectManifest.parse(json, "flowscope.json");
    }

    private static String detail(String json) {
        return assertThrows(ProjectManifest.ManifestError.class, () -> parse(json)).detail();
    }
}
