package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The CLI's `RUN` row is rewritten in place rather than reprinted, so a long step reports for
 * seconds without filling the scrollback. That only works if every row is exactly the same width:
 * a shorter one has to cover the longer one it replaces, and a longer one must not wrap, because
 * a carriage return only takes back the last physical row.
 */
class MainProgressLineTest {
    private static final FlowTestRunner.StepProgress STEP =
        new FlowTestRunner.StepProgress(3, 34, "before", "api-request", 7, "running",
            "Check the QA token is valid");

    @Test
    void aStepWithNoFrameYetReadsAsItAlwaysDid() {
        String line = Main.inPlaceLine(STEP, null, 78);
        assertTrue(line.startsWith("RUN  BEFORE 03/34  Check the QA token is valid (line 7)"), line);
        assertEquals(78, line.length());
    }

    @Test
    void aFrameIsAppendedToTheRow() {
        String line = Main.inPlaceLine(STEP,
            new RunActivity.Snapshot("before", 3, 34, "awaiting-response", 12_480, 30_000, 1, 0), 78);
        assertTrue(line.contains("awaiting response"), line);
        assertTrue(line.contains("12s of 30s"), line);
        assertEquals(78, line.length());
    }

    @Test
    void everyRowIsExactlyTheWidthSoAShorterOneCoversALongerOne() {
        String longer = Main.inPlaceLine(STEP,
            new RunActivity.Snapshot("before", 3, 34, "awaiting-response", 12_480, 30_000, 4, 9), 78);
        String shorter = Main.inPlaceLine(STEP, null, 78);
        assertEquals(longer.length(), shorter.length());
        assertEquals(78, shorter.length());
    }

    @Test
    void aLongNameGivesWayBeforeTheElapsedTimeDoes() {
        // The name is already on screen from the moment the step started; the elapsed time and the
        // budget are what a reader is actually waiting on, so they survive the squeeze.
        FlowTestRunner.StepProgress verbose = new FlowTestRunner.StepProgress(3, 34, "before",
            "api-request", 7, "running",
            "Check the QA token is valid against the staging authority before anything else runs");
        String line = Main.inPlaceLine(verbose,
            new RunActivity.Snapshot("before", 3, 34, "awaiting-response", 12_480, 30_000, 1, 0), 78);
        assertEquals(78, line.length());
        assertTrue(line.contains("…"), line);
        assertTrue(line.contains("12s of 30s"), line);
        assertTrue(line.contains("awaiting response"), line);
        // The name still names something rather than collapsing to a few characters.
        assertTrue(line.startsWith("RUN  BEFORE 03/34  Check the QA token is "), line);
    }

    @Test
    void anImpossiblyNarrowTerminalStillNamesSomething() {
        String line = Main.inPlaceLine(STEP,
            new RunActivity.Snapshot("before", 3, 34, "awaiting-response", 12_480, 30_000, 1, 0), 40);
        assertEquals(40, line.length());
    }

    @Test
    void theRowIsTakenBackBeforeTheCompletionLineLandsOnIt() throws Exception {
        // The runner reports a step's terminal status before printing its PASS/FAIL line, which is
        // the only moment the row can be cleared. Without this the completion line would be
        // appended to whatever the last frame drew.
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true,
                java.nio.charset.StandardCharsets.UTF_8));
            Main.printStepProgress(STEP);
            Main.printActivity(
                new RunActivity.Snapshot("before", 3, 34, "awaiting-response", 12_480, 30_000, 1, 0));
            Main.printStepProgress(new FlowTestRunner.StepProgress(3, 34, "before", "api-request", 7,
                "passed", "Check the QA token is valid"));
        } finally {
            System.setOut(original);
        }

        String written = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        // Every row starts by returning to column one, and none of them ends a line: the PASS line
        // the runner prints next is what finally does.
        assertFalse(written.contains("\n"), "an in-place row ended its line");
        assertTrue(written.startsWith("\r"), written);
        assertTrue(written.contains("awaiting response"), written);
        // The last thing written is a full-width wipe followed by another return, so the row is
        // blank and the cursor is back at column one.
        assertTrue(written.endsWith("\r" + " ".repeat(78) + "\r"), "row was not cleared");
    }

    @Test
    void theDefaultStageAddsNothingToTheRow() {
        // `running` says nothing a reader cannot already see, so only elapsed is appended.
        String line = Main.inPlaceLine(STEP,
            new RunActivity.Snapshot("before", 3, 34, "running", 2_000, 0, 0, 0), 78);
        assertTrue(line.contains("(line 7) · 2.0s"), line);
        assertFalse(line.contains("running ·"), line);
    }
}
