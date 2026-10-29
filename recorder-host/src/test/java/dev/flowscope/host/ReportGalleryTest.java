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
 * The report gallery: every screenshot a run captured, labelled, in one place. The per-step
 * evidence links stay where they were — the gallery is an addition, not a replacement.
 */
class ReportGalleryTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    @Test
    void theGalleryLabelsEveryScreenshotWithTheStepThatTookIt() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step screenshot name=welcome
            step screenshot name=checkout
            """, "shots.flow");
        FlowTestRunner runner = new FlowTestRunner(new ShotAdb(), DEVICE, test, temp, "shots",
            new NoHttp(), Map.of());

        assertTrue(runner.run());
        String html = Files.readString(runner.root().resolve("report.html"));
        assertTrue(html.contains("<h2>Screenshots</h2>"), html);
        assertTrue(html.contains("2 captures"), html);
        assertTrue(html.contains("<b>welcome</b>"), html);
        assertTrue(html.contains("Step 1 · screenshot"), html);
        assertTrue(html.contains("<b>checkout</b>"), html);
        // The gallery links the same evidence file the step row already links.
        assertTrue(html.contains("src=\"evidence/welcome.png\""), html);
        assertTrue(html.contains("<a href=\"evidence/welcome.png\"><code>evidence</code></a>"), html);
    }

    @Test
    void aFailingStepsScreenshotIsLabelledWithThatStepAndLinkedFromItsRow() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            timeout 200
            step assert-visible text=Missing
            """, "fails.flow");
        FlowTestRunner runner = new FlowTestRunner(new ShotAdb(), DEVICE, test, temp, "fails",
            new NoHttp(), Map.of());

        assertFalse(runner.run());
        String html = Files.readString(runner.root().resolve("report.html"));
        assertTrue(html.contains("<b>failure-01</b>"), html);
        assertTrue(html.contains("Step 1 failed · assert-visible"), html);
        // A failing step's screenshot is written straight to disk, so its row would otherwise be
        // the one row with nothing to click.
        assertTrue(html.contains("<a href=\"evidence/failure-01.png\"><code>screenshot</code></a>"), html);
        // It is a link in the page only: report.json still records what the step itself returned.
        assertFalse(Files.readString(runner.root().resolve("report.json")).contains("failure-01.png"));
    }

    @Test
    void aRunWithoutScreenshotsStillCarriesTheSectionAndSaysSo() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step force-stop
            """, "quiet.flow");
        FlowTestRunner runner = new FlowTestRunner(new ShotAdb(), DEVICE, test, temp, "quiet",
            new NoHttp(), Map.of());

        assertTrue(runner.run());
        String html = Files.readString(runner.root().resolve("report.html"));
        assertTrue(html.contains("This run captured no screenshots."), html);
        assertFalse(html.contains("<img"), html);
    }

    @Test
    void thePageIsSelfContainedAndThemeAware() throws Exception {
        FlowTest test = FlowTestParser.parseSource("""
            flowscope 1
            package com.example.debug
            step force-stop
            """, "shell.flow");
        FlowTestRunner runner = new FlowTestRunner(new ShotAdb(), DEVICE, test, temp, "shell",
            new NoHttp(), Map.of());

        assertTrue(runner.run());
        String html = Files.readString(runner.root().resolve("report.html"));
        // A report is read from a CI artifact with no network: nothing may be fetched.
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("http://"), html);
        assertFalse(html.contains("https://"), html);
        assertTrue(html.contains("prefers-color-scheme:dark"), html);
    }

    @Test
    void galleryMarkupEscapesLabelsAndPairsEachThumbnailWithItsOverlay() {
        String html = ReportHtml.gallery("screenshots", List.of(
            new ReportHtml.Shot("evidence/a.png", "a<b>", "Step 1 · tap \"Go\"", "passed")));
        assertTrue(html.contains("a&lt;b&gt;"), html);
        assertFalse(html.contains("a<b>"), html);
        assertTrue(html.contains("href=\"#screenshots-1\""), html);
        assertTrue(html.contains("<div class=\"lb\" id=\"screenshots-1\">"), html);
        // Closing the overlay returns to the section rather than reopening it.
        assertTrue(html.contains("class=\"close\" href=\"#screenshots\""), html);
    }

    private static final class NoHttp implements FlowHttpClient {
        @Override public Response send(String method, java.net.URI uri, Map<String, String> headers,
            String body, long timeoutMillis) {
            return new Response(200, "{}");
        }
    }

    /** Answers `screencap` with a one-pixel PNG and every hierarchy dump with an empty screen. */
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
