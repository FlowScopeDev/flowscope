package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSecretsTest {
    @TempDir Path temp;
    FlowProject project(String name) throws Exception {
        Path root = Files.createDirectories(temp.resolve(name));
        Files.writeString(root.resolve("flowscope.json"), "{\"schemaVersion\":1,\"name\":\"" + name + "\",\"flowsDir\":\"custom-flows\"}");
        return FlowProject.open(root);
    }
    @Test void defaultsAreProjectScopedAndExplicitFileWins() throws Exception {
        var a = project("a"); var b = project("b");
        assertNull(ProjectSecrets.resolve(a, null));
        Path file = ProjectSecrets.defaultFile(a);
        ProjectSecrets.save(file, "{\"TOKEN\":\"file-value\"}", "missing", a.root());
        assertEquals(file, ProjectSecrets.resolve(a, null));
        assertNull(ProjectSecrets.resolve(b, null));
        Path explicit = temp.resolve("chosen.env");
        assertEquals(explicit, ProjectSecrets.resolve(a, explicit));
        assertNull(ProjectSecrets.resolve(null, null));
        assertEquals("environment", SecretFile.load(file, Map.of("TOKEN", "environment")).get("TOKEN"));
        assertTrue(Files.readString(a.root().resolve(".gitignore")).contains("/custom-flows/.secrets"));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }
    @Test void jsonRoundTripAndConcurrentEditProtection() throws Exception {
        Path file = temp.resolve(".secrets");
        ProjectSecrets.save(file, "{\"EMPTY\":\"\",\"TOKEN\":\"a b#c\\\"d\"}", "missing", null);
        var document = ProjectSecrets.read(file);
        assertEquals("a b#c\"d", SecretFile.load(file, Map.of()).get("TOKEN"));
        assertEquals(java.util.List.of("EMPTY", "TOKEN"), ProjectSecrets.names(file));
        ProjectSecrets.save(file, document.json(), document.revision(), null);
        Files.writeString(file, "TOKEN=changed\n");
        assertThrows(IllegalArgumentException.class, () -> ProjectSecrets.save(file, document.json(), document.revision(), null));
        assertEquals("changed", SecretFile.load(file, Map.of()).get("TOKEN"));
    }
    @Test void rejectsInvalidJsonWithoutEchoingValuesOrTouchingOriginal() throws Exception {
        Path file = temp.resolve(".secrets");
        ProjectSecrets.save(file, "{\"TOKEN\":\"original\"}", "missing", null);
        var before = ProjectSecrets.read(file);
        for (String input : java.util.List.of("{\"TOKEN\":private-value}", "{\"TOKEN\":123}", "{\"TOKEN\":\"${private-value}\"}", "{\"TOKEN\":\"private-value\\n\"}")) {
            var error = assertThrows(IllegalArgumentException.class, () -> ProjectSecrets.save(file, input, before.revision(), null));
            assertFalse(error.getMessage().contains("private-value"));
            assertEquals(before, ProjectSecrets.read(file));
        }
    }
    @Test void rejectsEscapingDefaultAndBroadPermissions() throws Exception {
        var project = project("p"); Path file = ProjectSecrets.defaultFile(project);
        Files.createDirectories(file.getParent());
        Path outside = temp.resolve("outside"); Files.writeString(outside, "TOKEN=hidden\n");
        Files.createSymbolicLink(file, outside);
        assertThrows(IllegalArgumentException.class, () -> ProjectSecrets.resolve(project, null));
        Files.delete(file);
        Files.writeString(file, "TOKEN=hidden\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalArgumentException.class, () -> ProjectSecrets.resolve(project, null));
    }
}
