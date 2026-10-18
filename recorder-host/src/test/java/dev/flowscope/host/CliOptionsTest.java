package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CliOptionsTest {
    private static final Set<String> VALUES = Set.of("--serial", "--output", "--mode", "--timeout");
    private static final Set<String> BOOLEANS = Set.of("--read-only");

    @Test
    void readsValuesAndBooleanFlags() {
        CliOptions options = parse("--serial", "ABC", "--read-only", "--mode", "instrumented");

        assertEquals("ABC", options.value("--serial"));
        assertTrue(options.flag("--read-only"));
        assertEquals(CaptureMode.INSTRUMENTED, options.mode("--mode", CaptureMode.ZERO_CODE));
        assertNull(options.value("--output"));
        assertFalse(options.flag("--absent"));
    }

    @Test
    void collectsPositionalsBeforeAndAfterFlags() {
        CliOptions options = parse("run", "--serial", "ABC", "login.flow");
        assertEquals(List.of("run", "login.flow"), options.positional());
        assertEquals("run", options.positional(0));
        assertEquals("login.flow", options.positional(1));
        assertNull(options.positional(2));
    }

    @Test
    void doubleDashTerminatesFlagParsing() {
        CliOptions options = parse("--serial", "ABC", "--", "--weird-name.flow");
        assertEquals(List.of("--weird-name.flow"), options.positional());
        assertEquals("ABC", options.value("--serial"));
    }

    @Test
    void reportsUnknownAndMissingValuesExactlyAsBefore() {
        assertEquals("Unknown option: --nope", error("--nope"));
        assertEquals("Missing value for --serial", error("--serial"));
    }

    @Test
    void rejectsARepeatedOption() {
        assertEquals("Repeated option: --serial", error("--serial", "A", "--serial", "B"));
        assertEquals("Repeated option: --read-only", error("--read-only", "--read-only"));
    }

    @Test
    void rejectsAnUnexpectedPositional() {
        CliOptions options = parse("info", "extra");
        assertEquals("Unexpected argument for project info: extra",
            assertThrows(IllegalArgumentException.class,
                () -> options.requireNoExtraPositionals(1, "project info")).getMessage());
    }

    @Test
    void accessorsFallBackWhenAbsent() {
        CliOptions options = parse();
        assertEquals(Path.of("test-runs"), options.path("--output", Path.of("test-runs")));
        assertEquals(10_000L, options.positiveLong("--timeout", 10_000L));
        assertEquals(CaptureMode.ZERO_CODE, options.mode("--mode", CaptureMode.ZERO_CODE));
        assertEquals("fallback", options.value("--serial", "fallback"));
    }

    @Test
    void accessorsParseWhenPresent() {
        CliOptions options = parse("--output", "/tmp/out", "--timeout", "250");
        assertEquals(Path.of("/tmp/out"), options.path("--output", Path.of("test-runs")));
        assertEquals(250L, options.positiveLong("--timeout", 10_000L));
    }

    @Test
    void rejectsANonPositiveOrNonNumericTimeout() {
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> parse("--timeout", "0").positiveLong("--timeout", 1))
            .getMessage().contains("--timeout must be positive"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> parse("--timeout", "soon").positiveLong("--timeout", 1))
            .getMessage().contains("--timeout must be a number"));
    }

    @Test
    void aBareHyphenIsPositionalSoStdinStyleArgumentsSurvive() {
        assertEquals(List.of("-"), parse("-").positional());
    }

    private static CliOptions parse(String... args) {
        return CliOptions.parse(List.of(args), VALUES, BOOLEANS);
    }

    private static String error(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> parse(args)).getMessage();
    }
}
