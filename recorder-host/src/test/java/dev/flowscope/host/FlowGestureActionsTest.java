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
        ScriptedAdb adb = new ScriptedAdb(screen(node("Item 1", 0, 0, 1000, 100, "")));
        FlowTestRunner runner = runner(adb, "step scroll-to text=\"Missing\" max-swipes=10");
        assertFalse(runner.run());
        assertEquals(1, adb.swipes, "an unchanged hierarchy after one swipe means the end");
        assertTrue(runner.failureMessage().contains("Reached the end of the content"), runner.failureMessage());
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
            if (List.of(command).contains("cat")) {
                return screens.get(Math.min(swipes, screens.size() - 1)).getBytes(StandardCharsets.UTF_8);
            }
            return new byte[0];
        }
    }
}
