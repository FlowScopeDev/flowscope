package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the real AdbClient through adb's join-and-shell behavior, rather than mocking run(). */
@EnabledOnOs({OS.LINUX, OS.MAC})
@Timeout(20)
class FlowAdbShellQuotingTest {
    @TempDir Path temp;
    private AdbClient adb;
    private Path marker;
    private static final String PACKAGE = "com.example.debug";
    private static final String COMPONENT = PACKAGE + "/.Outer$DeepLinkActivity";

    @BeforeEach
    void fixture() throws Exception {
        Path bin = Files.createDirectories(temp.resolve("fake device"));
        Path fake = executable(bin, "adb", """
            #!/bin/sh
            directory=${0%/*}
            PATH="$directory:$PATH"
            export PATH
            if [ "$1" = "-s" ]; then shift 2; fi
            operation=$1
            shift
            case "$operation" in
              shell) exec /bin/sh -c "$*" ;;
              exec-out) exec "$@" ;;
              *) exit 1 ;;
            esac
            """);
        Files.writeString(bin.resolve("component"), COMPONENT + "\n");
        for (String tool : List.of("cmd", "am", "pm", "input", "pidof", "uiautomator", "cat", "dumpsys")) {
            executable(bin, tool, """
                #!/bin/sh
                directory=${0%/*}
                tool=${0##*/}
                printf '%s\\000' "$tool" "$@" >> "$directory/calls"
                printf '\\000' >> "$directory/calls"
                case "$tool" in
                  cmd) /bin/cat "$directory/component" ;;
                  cat) printf '<hierarchy rotation="0"/>' ;;
                  dumpsys) printf 'flags=[ DEBUGGABLE ]\\n' ;;
                esac
                exit 0
                """);
        }
        adb = new AdbClient(fake.toString());
        marker = temp.resolve("injected");
    }

    @Test
    void deepLinkQueriesAndLaunchKeepUriActionCategoriesAndInnerClassLiteral() throws Exception {
        String uri = "myapp://customer/42?scenario=S16&ui=v1&name=O'Reilly&pattern=*$HOME&(round)=yes;tail=data";
        String action = "custom.ACTION'; touch " + marker + "; echo '";
        String category = "custom.CATEGORY$(touch " + marker + ")";
        FlowTestRunner runner = runner("""
            step assert-deep-link-handler uri="${env.URI}" action="${env.ACTION}" category="${env.CATEGORY},android.intent.category.DEFAULT"
            step open-deep-link uri="${env.URI}" action="${env.ACTION}" category="${env.CATEGORY},android.intent.category.DEFAULT" activity=.Outer$DeepLinkActivity cold-start=true
            """, Map.of("URI", uri, "ACTION", action, "CATEGORY", category));

        assertTrue(runner.run(), runner.failureMessage());
        List<String> query = List.of("cmd", "package", "query-activities", "--brief", "-a", action,
            "-c", category, "-c", "android.intent.category.DEFAULT", "-d", uri, PACKAGE);
        assertEquals(2, commands().stream().filter(query::equals).count());
        assertTrue(commands().contains(List.of("am", "force-stop", PACKAGE)));
        assertTrue(commands().contains(List.of("am", "start", "-W", "-a", action, "-c", category,
            "-c", "android.intent.category.DEFAULT", "-d", uri, "-n", COMPONENT)));
        assertFalse(Files.exists(marker));
    }

    @ParameterizedTest
    @ValueSource(strings = {"grant", "revoke"})
    void permissionValuesCannotExecuteShellCommands(String action) throws Exception {
        String permission = "android.permission.CAMERA'; touch " + marker + "; echo '$(touch " + marker + ")";
        FlowTestRunner runner = runner("step " + action + "-permission permission=\"${env.PERMISSION}\"\n",
            Map.of("PERMISSION", permission));

        assertTrue(runner.run(), runner.failureMessage());
        assertTrue(commands().contains(List.of("pm", action, PACKAGE, permission)));
        assertFalse(Files.exists(marker));
    }

    @Test
    void launcherComponentKeepsTheInnerClassDollarSign() throws Exception {
        FlowTestRunner runner = runner("step launch\n", Map.of());
        assertTrue(runner.run(), runner.failureMessage());
        assertTrue(commands().contains(List.of("cmd", "package", "resolve-activity", "--brief", PACKAGE)));
        assertTrue(commands().contains(List.of("am", "start", "-W", "-n", COMPONENT)));
    }

    @Test
    void packageQueriesTreatUiSuppliedPackageNamesAsLiteralArguments() throws Exception {
        String packageName = "com.example.debug; touch " + marker + "; #";
        assertTrue(adb.isDebuggable("TEST", packageName));
        assertNull(adb.pidOf("TEST", packageName));
        assertTrue(commands().contains(List.of("dumpsys", "package", packageName)));
        assertTrue(commands().contains(List.of("pidof", packageName)));
        assertFalse(Files.exists(marker));
    }

    @Test
    void nativeTypeKeepsItsExistingEncodingAndCharacterLimit() throws Exception {
        FlowTestRunner supported = runner("step type value=\"qa+test@example.com 42\"\n", Map.of());
        assertTrue(supported.run(), supported.failureMessage());
        assertTrue(commands().contains(List.of("input", "text", "qa+test@example.com%s42")));
        FlowTestRunner unsupported = runner("step type value=\"A&B\"\n", Map.of());
        assertFalse(unsupported.run());
        assertTrue(unsupported.failureMessage().contains("type currently supports"));
        assertFalse(commands().stream().anyMatch(command -> command.contains("A&B")));
    }

    private FlowTestRunner runner(String steps, Map<String, String> environment) throws Exception {
        FlowTest flow = FlowTestParser.parseSource("flowscope 1\npackage " + PACKAGE + "\n" + steps,
            "quoting.flow");
        return new FlowTestRunner(adb, new Device("TEST", "device", Map.of()), flow, temp,
            "quoting", FlowHttpClient.platform(), environment);
    }

    private List<List<String>> commands() throws Exception {
        Path calls = temp.resolve("fake device/calls");
        if (!Files.exists(calls)) return List.of();
        List<List<String>> result = new ArrayList<>();
        for (String command : Files.readString(calls).split("\0\0")) {
            result.add(Arrays.asList(command.split("\0")));
        }
        return result;
    }

    private static Path executable(Path directory, String name, String script) throws Exception {
        Path path = Files.writeString(directory.resolve(name), script);
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }
}
