package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * `report.pdf`: the artifact that gets attached to a ticket. The screenshots have to be inside it,
 * the verdict has to read the same as everywhere else, and writing it must never be able to change
 * what the run decided.
 */
class ReportPdfTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    @Test
    void aPassingRunWritesAReadablePdfCarryingItsScreenshots() throws Exception {
        FlowTestRunner runner = run("""
            flowscope 1
            package com.example.debug
            step screenshot name=welcome
            step screenshot name=checkout
            """, "shots");
        assertTrue(runner.run());

        byte[] pdf = Files.readAllBytes(runner.root().resolve("report.pdf"));
        // A reader has to accept it before anything else matters.
        assertEquals("%PDF-1.7", new String(pdf, 0, 8, StandardCharsets.ISO_8859_1));
        assertTrue(new String(pdf, StandardCharsets.ISO_8859_1).trim().endsWith("%%EOF"), "no trailer");

        String text = PdfProbe.text(pdf);
        assertTrue(text.contains("shots.flow"), text);
        assertTrue(text.contains("Flow test Passed"), text);
        assertTrue(text.contains("com.example.debug"), text);
        // Every step is named, and every capture gets a plate with the step that took it.
        assertTrue(text.contains("screenshot"), text);
        assertTrue(text.contains("welcome"), text);
        assertTrue(text.contains("checkout"), text);
        assertTrue(text.contains("Step 1"), text);
        // The pixels travel with the document: two captures, two image XObjects.
        assertEquals(2, PdfProbe.images(pdf), "screenshots are not embedded");
    }

    @Test
    void theVerdictNamesThePhaseAndKeepsCleanupApartFromTheAssertion() throws Exception {
        FlowTestRunner runner = run("""
            flowscope 1
            package com.example.debug
            timeout 200
            step assert-visible text=Missing
            after assert-installed version-name="9.9.9"
            """, "fails");
        assertFalse(runner.run());

        String text = PdfProbe.text(Files.readAllBytes(runner.root().resolve("report.pdf")));
        assertTrue(text.contains("Flow test Failed"), text);
        // A cleanup failure means a dirty device, not a broken assertion, so it is set apart rather
        // than folded into the verdict as if the test body had done it.
        assertTrue(text.contains("Cleanup failures: the device or its fixtures may be dirty"), text);
        assertTrue(text.contains("Element did not become visible: [text=Missing]"), text);
    }

    @Test
    void aRunWithoutScreenshotsStillRendersAndCarriesNoPlates() throws Exception {
        FlowTestRunner runner = run("""
            flowscope 1
            package com.example.debug
            step force-stop
            """, "bare");
        assertTrue(runner.run());

        byte[] pdf = Files.readAllBytes(runner.root().resolve("report.pdf"));
        assertEquals(0, PdfProbe.images(pdf));
        assertTrue(PdfProbe.text(pdf).contains("force-stop"));
    }

    @Test
    void aLongErrorWrapsInsteadOfRunningOffThePage() {
        String sentence = "Element did not become visible within the configured timeout, and the "
            + "hierarchy that was dumped named none of the selectors the step asked for anywhere.";
        List<String> lines = Pdf.wrap(sentence, Pdf.Font.REGULAR, 9, 240);
        assertTrue(lines.size() > 1, "did not wrap");
        for (String line : lines) assertTrue(Pdf.Font.REGULAR.width(line, 9) <= 240, line);
        assertEquals(sentence, String.join(" ", lines));
    }

    @Test
    void aWordWiderThanTheMeasureIsBrokenRatherThanOverrunning() {
        String id = "com.example.debug:id/an_extremely_long_resource_identifier_with_no_spaces";
        List<String> lines = Pdf.wrap(id, Pdf.Font.MONO, 8, 120);
        assertTrue(lines.size() > 1, "did not break");
        for (String line : lines) assertTrue(Pdf.Font.MONO.width(line, 8) <= 120, line);
        assertEquals(id, String.join("", lines));
    }

    @Test
    void textOutsideWinAnsiBecomesAStandInRatherThanAWrongGlyph() {
        // A step label may be written in any language; the base-14 fonts cover one encoding.
        assertEquals("Check the price is Rs 500", Pdf.winAnsi("Check the price is Rs 500"));
        assertEquals("'quoted' and \"quoted\" and -- and ...",
            Pdf.winAnsi("‘quoted’ and “quoted” and — and …"));
        assertEquals("Prufung ok", Pdf.winAnsi("Prüfung ok").replace("ü", "u"));
        assertEquals("??", Pdf.winAnsi("你好"));
    }

    @Test
    void aScreenshotThatWillNotDecodeIsSkippedRatherThanFailingTheRun() throws Exception {
        FlowTestRunner runner = run("""
            flowscope 1
            package com.example.debug
            step screenshot name=welcome
            """, "broken");
        assertTrue(runner.run());
        // Corrupt the capture after the run, then re-render: a report is written from what is on
        // disk, and what is on disk is not always readable.
        Files.writeString(runner.root().resolve("evidence/welcome.png"), "not a png");
        byte[] pdf = ReportPdf.render(
            new ReportPdf.Doc("broken.flow", "Flow test", "passed", null, List.of(), List.of(),
                List.of(), "Steps", null, List.of(),
                List.of(new ReportPdf.Plate("evidence/welcome.png", "welcome", "Step 1",
                    "screenshot", "passed")),
                null, List.of(), null, null, List.of()),
            new ReportImages(runner.root()));
        assertEquals(0, PdfProbe.images(pdf));
        assertTrue(PdfProbe.text(pdf).contains("broken.flow"));
    }

    private FlowTestRunner run(String source, String name) throws Exception {
        return new FlowTestRunner(new ShotAdb(), DEVICE,
            FlowTestParser.parseSource(source, name + ".flow"), temp, name, new NoHttp(), Map.of());
    }

    private static final class NoHttp implements FlowHttpClient {
        @Override public Response send(String method, java.net.URI uri, Map<String, String> headers,
            String body, long timeoutMillis) {
            return new Response(200, "{}");
        }
    }

    /** Answers `screencap` with a real decodable PNG and every dump with an empty screen. */
    private static final class ShotAdb extends AdbClient {
        @Override public CommandResult run(List<String> arguments, long timeoutMillis) {
            return new CommandResult(0, "Success", "");
        }
        @Override public byte[] execOut(String serial, String... command) {
            if (command.length > 0 && command[0].equals("screencap")) return PNG;
            return "<?xml version=\"1.0\"?><hierarchy rotation=\"0\"/>".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
}
