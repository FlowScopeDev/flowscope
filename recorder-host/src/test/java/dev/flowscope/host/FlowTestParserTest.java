package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowTestParserTest {
    @TempDir Path directory;

    @Test
    void parsesQuotedSelectorsAndMode() throws Exception {
        Path testFile = directory.resolve("login.flow");
        Files.writeString(testFile, """
            flowscope 1
            name "Login smoke"
            package com.example.debug
            mode instrumented
            timeout 12000
            step launch
            step tap-if-visible text="Dismiss" timeout=3000
            step tap text="Continue now"
            step tap text-contains="Terms & Policies"
            step type id=com.example.debug:id/email value="qa@example.com"
            """);

        FlowTest test = FlowTestParser.parse(testFile);

        assertEquals("Login smoke", test.name());
        assertEquals(CaptureMode.INSTRUMENTED, test.mode());
        assertEquals(12_000, test.timeoutMillis());
        assertEquals("Dismiss", test.steps().get(1).arguments().get("text"));
        assertEquals("Continue now", test.steps().get(2).arguments().get("text"));
        assertEquals("Terms & Policies", test.steps().get(3).arguments().get("text-contains"));
        assertEquals(5, test.steps().size());
    }

    @Test
    void rejectsTapWithoutSelector() throws Exception {
        Path testFile = directory.resolve("invalid.flow");
        Files.writeString(testFile, """
            flowscope 1
            package com.example.debug
            step tap
            """);

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class, () -> FlowTestParser.parse(testFile));

        assertEquals(
            "Line 3: tap requires id=, text=, text-contains=, desc=, or class=",
            error.getMessage());
    }

    @Test
    void rejectsOptionalTapWithoutSelector() throws Exception {
        Path testFile = directory.resolve("invalid-optional-tap.flow");
        Files.writeString(testFile, """
            flowscope 1
            package com.example.debug
            step tap-if-visible
            """);

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class, () -> FlowTestParser.parse(testFile));

        assertEquals(
            "Line 3: tap-if-visible requires id=, text=, text-contains=, desc=, or class=",
            error.getMessage());
    }

    @Test
    void rejectsUnknownCaptureMode() throws Exception {
        Path testFile = directory.resolve("invalid-mode.flow");
        Files.writeString(testFile, """
            flowscope 1
            package com.example.debug
            mode automatic
            step launch
            """);

        assertThrows(IllegalArgumentException.class, () -> FlowTestParser.parse(testFile));
    }

    @Test
    void parsesLifecycleApiDeviceActionsAndSingleQuotedJson() {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            before clear-app-data
            before api-request method=POST url=\"${env.BASE_URL}/fixtures\" \\
              body='{"kind":"smoke"}' expect-status=201 capture.id=data.id
            step launch
            after api-request method=DELETE url=\"${env.BASE_URL}/fixtures/${var.id}\" \\
              expect-status=200,204 if-var=id
            after reset-app-permissions
            """, "lifecycle.flow");

        assertEquals(FlowTest.Phase.BEFORE, test.steps().get(0).phase());
        assertEquals("{\"kind\":\"smoke\"}", test.steps().get(1).arguments().get("body"));
        assertEquals(FlowTest.Phase.TEST, test.steps().get(2).phase());
        assertEquals(FlowTest.Phase.AFTER, test.steps().get(3).phase());
        assertEquals(5, test.steps().size());
    }

    @Test
    void rejectsLifecycleStatementsOutOfOrder() {
        assertThrows(FlowTestParser.ParseError.class, () -> FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step launch
            before clear-app-data
            """, "bad.flow"));
    }

    @Test
    void requiresAMainStep() {
        assertThrows(FlowTestParser.ParseError.class, () -> FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            before clear-app-data
            after force-stop
            """, "bad.flow"));
    }
}
