package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
@Timeout(10)
class AdbClientShellQuotingTest {
    @TempDir Path temp;
    private AdbClient adb;
    private String printer;
    private Path marker;
    private List<String> values;

    @BeforeEach
    void fixture() throws Exception {
        // Match adb's second parsing boundary: join argv, then execute it through a real shell.
        Path fake = executable("fake adb", """
            #!/bin/sh
            while :; do
              case "$1" in
                -s|-t|-H|-P|-L|--one-device) shift 2 ;;
                -a|-d|-e|--exit-on-write-error) shift ;;
                *) break ;;
              esac
            done
            operation=$1
            shift
            case "$operation" in
              shell)
                while :; do
                  case "$1" in
                    -Tn|-n|-t|-tt|-T|-x|-e~) shift ;;
                    -e) shift 2 ;;
                    --) shift; break ;;
                    *) break ;;
                  esac
                done
                exec /bin/sh -c "$*" ;;
              exec-out) exec "$@" ;;
              *) printf '%s\\000' "$operation" "$@" ;;
            esac
            """);
        printer = executable("argv printer", "#!/bin/sh\nprintf '%s\\000' \"$@\"\n").toString();
        adb = new AdbClient(fake.toString());
        marker = temp.resolve("injected");
        values = List.of("first", "", "two words", "apostrophe'and\"quote", "a&b;c|d<e>f(g)*?[x]~$HOME\\",
            "$(touch " + marker + ")", "`touch " + marker + "`", "tab\tand\r\nnewlines", "नमस्ते😀");
    }

    @Test
    void runPreservesEveryDeviceArgument() throws Exception {
        var result = adb.run(arguments("-s", "serial with spaces", "shell"));
        assertEquals(0, result.exitCode(), result.stderr());
        assertLiteral(result.stdout());
    }

    @Test
    void runWithoutSerialAndWithHostOptionsQuotesOnlyTheRemoteCommand() throws Exception {
        var result = adb.run(arguments("-H", "host", "-P", "5037", "-d", "shell"));
        assertEquals(0, result.exitCode(), result.stderr());
        assertLiteral(result.stdout());
    }

    @Test
    void shellTransportOptionsStayOutsideTheDeviceCommand() throws Exception {
        var result = adb.run(arguments("-s", "TEST", "shell", "-Tn", "-e", "none", "--"));
        assertEquals(0, result.exitCode(), result.stderr());
        assertLiteral(result.stdout());
    }

    @Test
    void startPreservesEveryDeviceArgument() throws Exception {
        Process process = adb.start(arguments("shell"));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertLiteral(output);
    }

    @Test
    void execOutLeavesArgumentsForAdbsOwnEscaping() throws Exception {
        List<String> command = new ArrayList<>(List.of("/bin/sh"));
        command.addAll(List.of(deviceCommand()));
        assertLiteral(new String(adb.execOut("TEST", command.toArray(String[]::new)), StandardCharsets.UTF_8));
    }

    @Test
    void streamPreservesEveryDeviceArgument() throws Exception {
        Process process = adb.stream("TEST", deviceCommand());
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertLiteral(output);
    }

    @Test
    void streamToFileAndAppendPreserveEveryDeviceArgument() throws Exception {
        Path target = temp.resolve("output/stream.txt");
        Process first = adb.streamToFile("TEST", target, deviceCommand());
        assertTrue(first.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, first.exitValue());
        assertLiteral(Files.readString(target));
        Process second = adb.streamToFile("TEST", target, true, deviceCommand());
        assertTrue(second.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, second.exitValue());
        assertEquals(encodedValues().repeat(2), Files.readString(target));
        assertFalse(Files.exists(marker));
    }

    @Test
    void hostCommandsRetainLiteralPathsAndArgumentsNamedShell() throws Exception {
        List<String> original = List.of("push", "shell", "local file's $name & stuff", "exec-out");
        var result = adb.run(original);
        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(String.join("\0", original) + "\0", result.stdout());
        // A serial named shell is also a host argument, not the command.
        assertEquals("install\0my app's.apk\0",
            adb.run(List.of("-s", "shell", "install", "my app's.apk")).stdout());
    }

    @Test
    void environmentAssignmentsUseAnExplicitEnvCommand() throws Exception {
        var result = adb.run(List.of("-s", "TEST", "shell", "env", "CLASSPATH=a path's $value",
            "/bin/sh", "-c", "printf '%s' \"$CLASSPATH\""));
        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("a path's $value", result.stdout());
    }

    private List<String> arguments(String... prefix) {
        List<String> result = new ArrayList<>(List.of(prefix));
        result.addAll(List.of(deviceCommand()));
        return result;
    }

    private String[] deviceCommand() {
        List<String> result = new ArrayList<>(List.of(printer));
        result.addAll(values);
        return result.toArray(String[]::new);
    }

    private String encodedValues() { return String.join("\0", values) + "\0"; }

    private void assertLiteral(String output) {
        assertEquals(encodedValues(), output);
        assertFalse(Files.exists(marker), "shell substitutions must never execute");
    }

    private Path executable(String name, String script) throws Exception {
        Path path = Files.writeString(temp.resolve(name), script);
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }
}
