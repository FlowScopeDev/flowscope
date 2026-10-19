package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretFileTest {
    @TempDir Path temp;

    @Test
    void parsesStrictValuesAndLetsEnvironmentOverrideTheFile() throws Exception {
        Path file = write("# local\nAPI_JWT='from-file'\nCLIENT_SECRET=plain\n");
        Map<String, String> values = SecretFile.load(file, Map.of("API_JWT", "from-env"));
        assertEquals("from-env", values.get("API_JWT"));
        assertEquals("plain", values.get("CLIENT_SECRET"));
    }

    @Test
    void rejectsDuplicatesInterpolationAndExport() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SecretFile.load(
            write("A=one\nA=two\n"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SecretFile.load(
            write("A=${OTHER}\n"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SecretFile.load(
            write("export A=one\n"), Map.of()));
    }

    @Test
    void rejectsBroadPosixPermissionsWhenSupported() throws Exception {
        Path file = write("A=one\n");
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
        } catch (UnsupportedOperationException ignored) {
            return;
        }
        assertThrows(IllegalArgumentException.class, () -> SecretFile.load(file, Map.of()));
    }

    private Path write(String source) throws Exception {
        Path file = temp.resolve("secrets.env");
        Files.writeString(file, source);
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {}
        return file;
    }
}
