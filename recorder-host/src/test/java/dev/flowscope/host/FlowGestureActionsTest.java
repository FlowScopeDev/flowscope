package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowGestureActionsTest {
    @TempDir Path temp;
    private static final Device DEVICE = new Device("TEST", "device", Map.of());

    // ---------------------------------------------------------------- parser

    @Test
    void parsesGestureAndInputActions() {
        FlowTest test = parse("""
            step long-press text="Row" duration=900
            step swipe direction=left id=com.example:id/carousel distance=40 duration=250
            step swipe direction=up
            step scroll-to text="Order total" direction=up max-swipes=8
            step clear-text id=com.example:id/email
            step press-key key=enter
            step hide-keyboard
            step assert-visible id=com.example:id/accept checked=true enabled=true index=1
            """);
        assertEquals(8, test.steps().size());
        assertEquals("900", test.steps().getFirst().arguments().get("duration"));
        assertEquals("1", test.steps().get(7).arguments().get("index"));
    }

    @Test
    void rejectsInvalidGestureArguments() {
        assertRejected("step long-press duration=900", "long-press requires");
        assertRejected("step long-press text=\"Row\" duration=100", "duration must be between 500 and 10000");
        assertRejected("step swipe", "swipe direction must be");
        assertRejected("step swipe direction=sideways", "swipe direction must be");
        assertRejected("step swipe direction=up distance=5", "distance must be between 10 and 90");
        assertRejected("step swipe direction=up duration=50", "duration must be between 100 and 5000");
        assertRejected("step scroll-to direction=up", "scroll-to requires");
        assertRejected("step scroll-to text=\"x\" max-swipes=0", "max-swipes must be positive");
        assertRejected("step scroll-to text=\"x\" max-swipes=99", "max-swipes must be between 1 and 50");
        assertRejected("step scroll-to text=\"x\" direction=diagonal", "scroll-to direction must be");
        assertRejected("step clear-text", "clear-text requires");
        assertRejected("step press-key key=KEYCODE_POWER", "press-key requires key= one of");
        assertRejected("step press-key", "press-key requires key= one of");
    }

    @Test
    void rejectsInvalidSelectorFilters() {
        assertRejected("step tap text=\"x\" index=first", "index must be a zero-based number");
        assertRejected("step tap text=\"x\" index=-1", "index must be a zero-based number");
        assertRejected("step assert-visible text=\"x\" checked=maybe", "checked must be true or false");
        assertRejected("step tap enabled=true", "tap requires id=");
    }

    // ---------------------------------------------------------------- hierarchy

    @Test
    void indexAndStateFiltersNarrowTheMatch() throws Exception {
        List<UiHierarchy.UiNode> nodes = UiHierarchy.parse(screen(
            node("Save", 0, 0, 100, 50, "enabled=\"true\" checked=\"false\""),
            node("Save", 0, 60, 100, 110, "enabled=\"false\" checked=\"true\""),
            node("Save", 0, 120, 100, 170, "enabled=\"true\" checked=\"true\"")).getBytes(StandardCharsets.UTF_8));
        assertEquals(0, UiHierarchy.find(nodes, Map.of("text", "Save")).top());
        assertEquals(60, UiHierarchy.find(nodes, Map.of("text", "Save", "index", "1")).top());
        assertEquals(60, UiHierarchy.find(nodes, Map.of("text", "Save", "enabled", "false")).top());
        assertEquals(120, UiHierarchy.find(nodes, Map.of("text", "Save", "checked", "true", "enabled", "true")).top());
        assertEquals(120, UiHierarchy.find(nodes, Map.of("text", "Save", "checked", "true", "index", "1")).top());
        assertNull(UiHierarchy.find(nodes, Map.of("text", "Save", "index", "3")));
        assertNull(UiHierarchy.find(nodes, Map.of("text", "Save", "focused", "true")));
    }

    // ---------------------------------------------------------------- runner

    @Test
    void swipeWithoutSelectorIsCentredOnTheScreen() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(node("Item", 0, 0, 1000, 100, "")));
        assertTrue(run(adb, "step swipe direction=up"));
        assertTrue(adb.commands.contains("shell input swipe 500 1500 500 500 300"), adb.commands.toString());
    }

    @Test
    void swipeWithSelectorStartsAtTheElementAndStaysOnScreen() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(node("Card", 800, 400, 1000, 600, "")));
        assertTrue(run(adb, "step swipe direction=right text=Card distance=50 duration=200"));
        // Starts at the centre (900,500); +500 would leave the 1000px screen, so the end is clamped.
        assertTrue(adb.commands.contains("shell input swipe 900 500 998 500 200"), adb.commands.toString());
    }

    @Test
    void longPressHoldsAtTheElementCentre() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(node("Row", 100, 300, 300, 500, "")));
        assertTrue(run(adb, "step long-press text=Row"));
        assertTrue(adb.commands.contains("shell input swipe 200 400 200 400 800"), adb.commands.toString());
        adb.commands.clear();
        assertTrue(run(adb, "step long-press text=Row duration=1200"));
        assertTrue(adb.commands.contains("shell input swipe 200 400 200 400 1200"), adb.commands.toString());
    }

    @Test
    void scrollToSwipesUntilTheElementAppears() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(
            screen(node("Item 1", 0, 0, 1000, 100, "")),
            screen(node("Item 9", 0, 0, 1000, 100, "")),
            screen(node("Item 9", 0, 0, 1000, 100, ""), node("Order total", 0, 200, 1000, 300, "")));
        assertTrue(run(adb, "step scroll-to text=\"Order total\""));
        assertEquals(2, adb.swipes);
        assertTrue(Files.readString(lastReport()).contains("swipes"));
    }

    @Test
    void scrollToDoesNotSwipeWhenTheElementIsAlreadyVisible() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(node("Order total", 0, 200, 1000, 300, "")));
        assertTrue(run(adb, "step scroll-to text=\"Order total\""));
        assertEquals(0, adb.swipes);
    }

    @Test
    void scrollToStopsAtTheEndOfTheContent() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen("<node scrollable=\"true\" bounds=\"[0,0][1000,2000]\">"
            + node("Item 1", 0, 0, 1000, 100, "") + "</node>"));
        FlowTestRunner runner = runner(adb, "step scroll-to text=\"Missing\" max-swipes=10");
        assertFalse(runner.run());
        assertEquals(1, adb.swipes, "an unchanged hierarchy after one swipe means the end");
        assertTrue(runner.failureMessage().contains("Reached the end of the content"), runner.failureMessage());
    }

    @Test
    void scrollToStartsEachSwipeOnTheFromIdElement() throws Exception {
        String list = "<node resource-id=\"app:id/list\" text=\"\" content-desc=\"\" class=\"android.widget.ScrollView\" "
            + "bounds=\"[0,200][1000,1000]\" />";
        ScriptedAdb adb = new ScriptedAdb(
            screen(list, node("A", 0, 0, 1000, 100, "")), screen(list, node("Total", 0, 300, 1000, 400, "")));
        assertTrue(run(adb, "step scroll-to text=\"Total\" from-id=app:id/list"));
        assertTrue(adb.commands.contains("shell input swipe 500 600 500 1 1000"), adb.commands.toString());
    }

    @Test
    void scrollToFailsWhenTheFromIdElementIsMissing() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(node("A", 0, 0, 1000, 100, "")), screen(node("B", 0, 0, 1000, 100, "")));
        FlowTestRunner runner = runner(adb, "step scroll-to text=\"Total\" from-id=app:id/list");
        assertFalse(runner.run());
        assertEquals(0, adb.swipes);
        assertTrue(runner.failureMessage().contains("start element not found"), runner.failureMessage());
    }

    @Test
    void scrollToKeepsSwipingWhileTheElementIsUnderTheClearOfElement() throws Exception {
        String panel = "<node resource-id=\"app:id/panel\" text=\"\" content-desc=\"\" class=\"android.view.View\" "
            + "bounds=\"[0,1500][1000,2000]\" />";
        ScriptedAdb adb = new ScriptedAdb(
            screen(node("Total", 0, 1600, 1000, 1700, ""), panel),
            screen(node("Total", 0, 900, 1000, 1000, ""), panel));
        assertTrue(run(adb, "step scroll-to text=\"Total\" clear-of-id=app:id/panel distance=30 duration=1500"));
        assertEquals(1, adb.swipes);
        assertTrue(adb.commands.contains("shell input swipe 500 1300 500 700 1500"), adb.commands.toString());
    }

    @Test
    void scrollToChecksEveryClearOfElement() throws Exception {
        String panel = "<node resource-id=\"app:id/panel\" text=\"\" content-desc=\"\" class=\"android.view.View\" "
            + "bounds=\"[0,1500][1000,2000]\" />";
        String fab = "<node resource-id=\"app:id/fab\" text=\"\" content-desc=\"\" class=\"android.view.View\" "
            + "bounds=\"[600,800][1000,1000]\" />";
        ScriptedAdb adb = new ScriptedAdb(
            screen(node("Total", 0, 900, 1000, 950, ""), panel, fab),
            screen(node("Total", 0, 300, 1000, 350, ""), panel, fab));
        assertTrue(run(adb, "step scroll-to text=\"Total\" clear-of-id=app:id/panel,app:id/fab"));
        assertEquals(1, adb.swipes);
    }

    @Test
    void scrollToIgnoresAnElementWithNoArea() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(
            screen(node("Total", 0, 0, 0, 0, "")), screen(node("Total", 0, 300, 1000, 350, "")));
        assertTrue(run(adb, "step scroll-to text=\"Total\""));
        assertEquals(1, adb.swipes, "a [0,0][0,0] node is off-screen, not visible");
    }

    @Test
    void aDumpThatWroteNoFileIsRetried() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(node("Home", 0, 0, 1000, 100, "")));
        adb.unwrittenDumps = 2;
        assertTrue(run(adb, "step assert-visible text=\"Home\""));
    }

    @Test
    void fromIdIsOnlyAcceptedOnScrollTo() {
        assertRejected("step tap text=\"x\" from-id=app:id/list", "from-id is only valid on scroll-to");
        assertRejected("step scroll-to text=\"x\" from-id=\"\"", "from-id must name a resource id");
        assertRejected("step swipe direction=up clear-of-id=app:id/panel", "clear-of-id is only valid on scroll-to");
        assertRejected("step scroll-to text=\"x\" distance=5", "distance must be between");
    }

    @Test
    void scrollToGivesUpAtMaxSwipes() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(
            screen(node("A", 0, 0, 1000, 100, "")), screen(node("B", 0, 0, 1000, 100, "")),
            screen(node("C", 0, 0, 1000, 100, "")), screen(node("D", 0, 0, 1000, 100, "")));
        FlowTestRunner runner = runner(adb, "step scroll-to text=\"Missing\" max-swipes=2");
        assertFalse(runner.run());
        assertEquals(2, adb.swipes);
        assertTrue(runner.failureMessage().contains("not found after 2 swipes"), runner.failureMessage());
    }

    @Test
    void pressKeySendsOnlyTheMappedKeycode() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(node("Item", 0, 0, 1000, 100, "")));
        assertTrue(run(adb, "step press-key key=enter\nstep press-key key=volume-down"));
        assertTrue(adb.commands.contains("shell input keyevent KEYCODE_ENTER"));
        assertTrue(adb.commands.contains("shell input keyevent KEYCODE_VOLUME_DOWN"));
    }

    @Test
    void hideKeyboardPressesBackOnlyWhileTheKeyboardShows() throws Exception {
        ScriptedAdb shown = new ScriptedAdb(screen(node("Item", 0, 0, 1000, 100, "")));
        shown.inputMethod = "  mInputShown=true";
        assertTrue(run(shown, "step hide-keyboard"));
        assertTrue(shown.commands.contains("shell input keyevent KEYCODE_BACK"));

        ScriptedAdb hidden = new ScriptedAdb(screen(node("Item", 0, 0, 1000, 100, "")));
        hidden.inputMethod = "  mInputShown=false";
        assertTrue(run(hidden, "step hide-keyboard"));
        assertFalse(hidden.commands.stream().anyMatch(command -> command.contains("KEYCODE_BACK")));
    }

    @Test
    void clearTextTapsTheFieldAndDeletesItsLength() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(node("hello", 100, 100, 300, 200, "")));
        assertTrue(run(adb, "step clear-text text=hello"));
        assertTrue(adb.commands.contains("shell input tap 200 150"));
        assertTrue(adb.commands.contains("shell input keyevent KEYCODE_MOVE_END"));
        assertTrue(adb.commands.contains("shell input keyevent KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL"),
            adb.commands.toString());
    }

    @Test
    void selectorFiltersApplyToAssertionsThroughTheRunner() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(
            node("Accept", 0, 0, 1000, 100, "checked=\"false\""),
            node("Accept", 0, 200, 1000, 300, "checked=\"true\"")));
        assertTrue(run(adb, "step assert-visible text=Accept checked=true"));
        assertFalse(run(adb, "step assert-visible text=Accept checked=true focused=true\n"
            .replace("step assert-visible", "step assert-visible timeout=300")));
    }

    @Test
    void transientMalformedDumpsAreDiscardedUntilAValidObservation() throws Exception {
        for (String invalid : List.of("", "ERROR: null root node", "<hierarchy><node", "<not-a-hierarchy/>")) {
            ScriptedAdb adb = new ScriptedAdb(screen(node("Home", 0, 0, 1000, 100, "")));
            adb.invalidDumps = 1;
            adb.invalidXml = invalid;
            FlowTestRunner runner = runner(adb, "step wait-visible text=Home timeout=1000").keepHierarchy(true);
            assertTrue(runner.run(), runner.failureMessage());
            String report = Files.readString(runner.root().resolve("report.json"));
            assertTrue(report.contains("\"uiDumpErrors\": 1"), report);
            assertTrue(report.contains("uiDumpLastError"), report);
            assertEquals(invalid, Files.readString(runner.root().resolve("evidence/step-01-discarded.txt")));
        }
    }

    @Test
    void malformedDumpsFailAtThePollingDeadline() throws Exception {
        ScriptedAdb adb = new ScriptedAdb("<hierarchy>");
        FlowTestRunner runner = runner(adb, "step wait-visible text=Home timeout=450");
        long start = System.nanoTime();
        assertFalse(runner.run());
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 450);
        assertTrue(runner.failureMessage().contains("No valid UI dump in 450 ms"), runner.failureMessage());
        assertTrue(runner.failureMessage().contains("attempts; last error:"), runner.failureMessage());
        assertTrue(runner.failureMessage().contains("raw output starts:"), runner.failureMessage());
    }

    @Test
    void scrollToClearsNavBarAfterOneSwipe() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(decorScreen(1073, 1545, false), decorScreen(913, 1385, false));
        FlowTestRunner runner = runner(adb,
            "step scroll-to id=target from-id=list within-id=list distance=10 max-swipes=40").keepHierarchy(true);
        assertTrue(runner.run(), runner.failureMessage());
        assertEquals(1, adb.swipes);
        assertTrue(Files.exists(runner.root().resolve("evidence/step-01-swipe-00.xml")));
        assertTrue(Files.exists(runner.root().resolve("evidence/step-01-swipe-01.xml")));
    }

    @Test
    void scrollToStopsWhenACoveredTargetLeavesTheViewport() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(decorScreen(800, 1200, true),
            decorScreen(300, 700, true), decorScreen(0, 150, true));
        FlowTestRunner runner = runner(adb,
            "step scroll-to id=target from-id=list within-id=list max-swipes=40").keepHierarchy(true);
        assertFalse(runner.run());
        assertEquals(2, adb.swipes);
        assertTrue(runner.failureMessage().contains("covered by overlay"), runner.failureMessage());
        assertTrue(runner.failureMessage().contains("left the viewport after swipe 2"), runner.failureMessage());
        String report = Files.readString(runner.root().resolve("report.json"));
        assertTrue(report.contains("coveringNode"), report);
        assertTrue(report.contains("targetBounds"), report);
        assertTrue(report.contains("drawing-order path"), report);
    }

    @Test
    void everyPollingActionDiscardsMalformedObservations() throws Exception {
        for (String action : List.of("assert-visible text=Home", "assert-not-visible text=Missing",
            "tap-if-visible text=Home", "if-visible text=Home", "scroll-to text=Home")) {
            ScriptedAdb adb = new ScriptedAdb(screen(node("Home", 0, 0, 1000, 100, "")));
            adb.invalidDumps = 1;
            adb.invalidXml = "<hierarchy>";
            FlowTestRunner runner = runner(adb, "step " + action + " timeout=1000"
                + (action.startsWith("if-visible") ? "\nstep end" : ""));
            assertTrue(runner.run(), action + ": " + runner.failureMessage());
        }
    }

    @Test
    void optionalCheckDoesNotTreatAnInvalidObservationAsAbsence() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(node("Home", 0, 0, 1000, 100, "")));
        adb.invalidDumps = 1;
        adb.invalidXml = "broken";
        FlowTestRunner runner = runner(adb, "step if-visible text=Home\nstep screenshot name=found\nstep end");
        assertTrue(runner.run(), runner.failureMessage());
        assertTrue(Files.exists(runner.root().resolve("evidence/found.png")));
        FlowTestRunner invalid = runner(new ScriptedAdb("broken"),
            "step assert-not-visible text=Missing timeout=100");
        assertFalse(invalid.run());
        assertTrue(invalid.failureMessage().contains("No valid UI dump"));
    }

    @Test
    void settleRetriesMalformedDumpsAndDoesNotCacheThem() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(screen(node("Home", 0, 0, 1000, 100, "")));
        adb.invalidDumps = 1;
        adb.invalidXml = "<hierarchy>";
        FlowTestRunner runner = runner(adb, "step press-key key=enter\nstep wait-visible text=Home");
        assertTrue(runner.run(), runner.failureMessage());
        String report = Files.readString(runner.root().resolve("report.json"));
        assertTrue(report.contains("\"uiDumpErrors\": 1"));
        FlowTestRunner invalid = runner(new ScriptedAdb("broken"), "step press-key key=enter");
        assertFalse(invalid.run());
        assertTrue(invalid.failureMessage().contains("No valid UI dump in 1500 ms"));
    }

    @Test
    void rawMalformedEvidenceRedactsXmlEncodedRuntimeValues() throws Exception {
        ScriptedAdb adb = new ScriptedAdb("<hierarchy><node text=\"a&#38;b\"");
        FlowTestRunner runner = runner(adb,
            "step set-value name=token value=\"a&b\"\nstep wait-visible text=${var.token} timeout=100").keepHierarchy(true);
        assertFalse(runner.run());
        String raw = Files.readString(runner.root().resolve("evidence/step-02-discarded.txt"));
        assertFalse(raw.contains("a&#38;b"));
        assertFalse(raw.contains("a&b"));
        assertTrue(raw.contains("{runtime}"), raw);
        assertFalse(Files.readString(runner.root().resolve("report.json")).contains("a&b"));
    }

    @Test
    void scrollToAcceptsEveryTargetAVisibilityCheckAccepts() throws Exception {
        // A zero-area wrapper between the list and the card, and a larger unrelated scroll pane:
        // neither hides the card from `assert-visible`, so neither may hide it from `scroll-to`.
        String wrapped = """
            <hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][1000,2000]">
              <node resource-id="list" scrollable="true" bounds="[0,200][1000,1800]">
                <node class="android.view.ViewGroup" bounds="[0,0][0,0]">
                  <node resource-id="card" bounds="[30,300][960,700]"/>
                </node>
              </node>
            </node></hierarchy>
            """;
        String twoPanes = """
            <hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][1000,2000]">
              <node drawing-order="1" resource-id="list" scrollable="true" bounds="[0,100][1000,800]">
                <node resource-id="card" bounds="[30,200][960,400]"/>
              </node>
              <node drawing-order="2" resource-id="sheet" scrollable="true" bounds="[0,900][1000,2000]"/>
            </node></hierarchy>
            """;
        for (String hierarchy : List.of(wrapped, twoPanes)) {
            assertTrue(run(new ScriptedAdb(hierarchy), "step assert-visible id=card"), hierarchy);
            ScriptedAdb adb = new ScriptedAdb(hierarchy);
            FlowTestRunner runner = runner(adb, "step scroll-to id=card max-swipes=3");
            assertTrue(runner.run(), hierarchy + runner.failureMessage());
            assertEquals(0, adb.swipes, hierarchy);
        }
    }

    @Test
    void scrollToAcceptsATargetTooLargeToFitItsContainer() throws Exception {
        String tall = """
            <hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][1000,2000]">
              <node resource-id="list" scrollable="true" bounds="[0,200][1000,1000]">
                <node resource-id="card" bounds="[0,%d][1000,%d]"/>
              </node>
            </node></hierarchy>
            """;
        // Spanning the container is as visible as a card taller than it can ever be.
        ScriptedAdb spanning = new ScriptedAdb(tall.formatted(100, 1500));
        FlowTestRunner accepted = runner(spanning, "step scroll-to id=card max-swipes=3");
        assertTrue(accepted.run(), accepted.failureMessage());
        assertEquals(0, spanning.swipes);
        // One that has scrolled half out of the container still has to come back.
        ScriptedAdb partial = new ScriptedAdb(tall.formatted(500, 1900), tall.formatted(100, 1500));
        FlowTestRunner scrolled = runner(partial, "step scroll-to id=card max-swipes=3");
        assertTrue(scrolled.run(), scrolled.failureMessage());
        assertEquals(1, partial.swipes);
    }

    @Test
    void scrollToNamesWhyAMatchedTargetWasRejected() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(decorScreen(1073, 1545, false));
        FlowTestRunner runner = runner(adb, "step scroll-to id=target max-swipes=1").keepHierarchy(true);
        assertFalse(runner.run());
        assertTrue(runner.failureMessage().contains("Target matched on swipes 0\u20131 but was rejected: "
            + "partly outside the container [0,170][720,1510]"), runner.failureMessage());
        assertFalse(runner.failureMessage().contains("Element not found"));
        assertTrue(Files.readString(runner.root().resolve("report.json")).contains("targetRejection"));
        for (String swipe : List.of("step-01-swipe-00.xml", "step-01-swipe-01.xml")) {
            assertTrue(Files.exists(runner.root().resolve("evidence/" + swipe)), swipe);
        }
    }

    @Test
    void scrollToStopsWhenARejectedTargetScrollsPastTheContainer() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(decorScreen(1073, 1545, false), decorScreen(0, 100, false));
        FlowTestRunner runner = runner(adb, "step scroll-to id=target max-swipes=40");
        assertFalse(runner.run());
        assertEquals(1, adb.swipes);
        assertTrue(runner.failureMessage().contains("partly outside the container"), runner.failureMessage());
        assertTrue(runner.failureMessage().contains("left the viewport after swipe 1"), runner.failureMessage());
    }

    @Test
    void aPermanentlyCoveredTargetReportsTheOverlayAtTheSwipeLimit() throws Exception {
        ScriptedAdb adb = new ScriptedAdb(decorScreen(900, 1200, true), decorScreen(500, 800, true));
        FlowTestRunner runner = runner(adb, "step scroll-to id=target max-swipes=1");
        assertFalse(runner.run());
        assertTrue(runner.failureMessage().contains("covered by overlay"), runner.failureMessage());
        assertFalse(runner.failureMessage().contains("Element not found"));
    }

    private static String decorScreen(int top, int bottom, boolean overlay) {
        return """
            <hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][720,1600]">
              <node drawing-order="1" class="android.view.View" bounds="[0,0][720,1600]"/>
              <node drawing-order="2" class="android.widget.LinearLayout" bounds="[0,0][720,1600]">
                <node resource-id="list" scrollable="true" bounds="[0,170][720,1510]">
                  <node resource-id="target" bounds="[30,%d][660,%d]"/>
                </node>
              </node>
              <node drawing-order="3" class="android.widget.FrameLayout" bounds="[0,0][720,1600]">
                <node class="android.view.View" bounds="[0,0][720,64]"/>
                <node class="android.view.View" bounds="[0,1510][720,1600]"/>
              </node>
              <node drawing-order="4" resource-id="android:id/navigationBarBackground" bounds="[0,1510][720,1600]"/>
              %s
            </node></hierarchy>
            """.formatted(top, bottom, overlay
                ? "<node resource-id=\"overlay\" class=\"android.widget.FrameLayout\" clickable=\"true\" drawing-order=\"5\" bounds=\"[0,0][720,1600]\"><node bounds=\"[0,0][10,10]\"/></node>" : "");
    }

    // ---------------------------------------------------------------- helpers

    private Path lastReport;

    private Path lastReport() { return lastReport; }

    private boolean run(ScriptedAdb adb, String steps) throws Exception {
        FlowTestRunner runner = runner(adb, steps);
        boolean passed = runner.run();
        lastReport = runner.root().resolve("report.json");
        return passed;
    }

    private FlowTestRunner runner(ScriptedAdb adb, String steps) throws Exception {
        return new FlowTestRunner(adb, DEVICE, parse(steps), temp, "run-" + System.nanoTime(),
            FlowHttpClient.platform(), Map.of());
    }

    private static FlowTest parse(String steps) {
        return FlowTestParser.parseSource("flowscope 1\npackage com.example.debug\n" + steps, "gesture.flow");
    }

    private static void assertRejected(String step, String expected) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> parse(step), step);
        assertTrue(error.getMessage().contains(expected), step + " -> " + error.getMessage());
    }

    private static String node(String text, int left, int top, int right, int bottom, String extra) {
        return "<node resource-id=\"\" text=\"" + text + "\" content-desc=\"\" class=\"android.widget.TextView\" "
            + "bounds=\"[" + left + "," + top + "][" + right + "," + bottom + "]\" " + extra + "/>";
    }

    private static String screen(String... nodes) {
        return "<?xml version=\"1.0\"?><hierarchy rotation=\"0\">" + String.join("", nodes) + "</hierarchy>";
    }

    /** Serves a 1000x2000 display and a hierarchy that advances one screen per `input swipe`. */
    private static final class ScriptedAdb extends AdbClient {
        final List<String> commands = new ArrayList<>();
        private final List<String> screens;
        int swipes;
        int unwrittenDumps;
        int invalidDumps;
        String invalidXml;
        String inputMethod = "";

        ScriptedAdb(String... screens) {
            super("unused");
            this.screens = List.of(screens);
        }

        @Override
        public CommandResult run(List<String> arguments, long timeoutMillis) {
            String joined = String.join(" ", arguments);
            if (joined.startsWith("-s TEST ")) joined = joined.substring("-s TEST ".length());
            commands.add(joined);
            if (joined.equals("shell wm size")) return new CommandResult(0, "Physical size: 1000x2000\n", "");
            if (joined.equals("shell dumpsys input_method")) return new CommandResult(0, inputMethod, "");
            if (joined.startsWith("shell input swipe")) swipes++;
            if (joined.contains("pidof")) return new CommandResult(1, "", "");
            return new CommandResult(0, "ok", "");
        }

        @Override
        public byte[] execOut(String serial, String... command) {
            if (List.of(command).contains("cat") && invalidDumps-- > 0) return invalidXml.getBytes(StandardCharsets.UTF_8);
            if (List.of(command).contains("cat") && unwrittenDumps > 0) {
                unwrittenDumps--;
                return "cat: /data/local/tmp/flowscope-test-window.xml: No such file or directory".getBytes(StandardCharsets.UTF_8);
            }
            if (List.of(command).contains("cat")) {
                return screens.get(Math.min(swipes, screens.size() - 1)).getBytes(StandardCharsets.UTF_8);
            }
            return new byte[0];
        }
    }
}
