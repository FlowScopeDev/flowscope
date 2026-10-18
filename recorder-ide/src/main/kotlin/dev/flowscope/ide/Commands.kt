package dev.flowscope.ide

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * design.md §7 — when the OS asks for reduced motion, the record pulse becomes a static dot and
 * the tap ripple / swipe trail stop animating. Defaults to false; resolved best-effort at startup.
 */
internal val LocalReduceMotion = compositionLocalOf { false }

/** Reads the macOS "Reduce motion" accessibility preference. Never throws; false when unknown. */
internal fun detectReduceMotion(): Boolean {
    if (!IsMac) return false
    return runCatching {
        val process = ProcessBuilder(
            "defaults", "read", "com.apple.universalaccess", "reduceMotion",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output == "1"
    }.getOrDefault(false)
}

/** One addressable action, surfaced by both the keymap and the command palette (design.md §6.1). */
internal data class Command(
    val id: String,
    val label: String,
    val shortcut: String,
    val enabled: Boolean,
    val run: () -> Unit,
)

/**
 * Matches a key event against the documented keymap. Returns the command id to run, or null.
 * Only guards the event's shape; [commandIdForKey] holds the mapping itself.
 */
internal fun commandIdFor(event: KeyEvent): String? {
    if (event.type != KeyEventType.KeyDown) return null
    if (!event.isMetaPressed) return null
    return commandIdForKey(event.key, event.isShiftPressed)
}

/**
 * One binding: the key that runs a command and the label shown for it. Both the keymap and every
 * printed shortcut are derived from this list, so a displayed shortcut cannot claim a key that does
 * not actually run the command — the Settings screen presents these as documentation.
 */
internal data class KeyBinding(val key: Key, val shift: Boolean, val id: String, val label: String)

// Every id here must also exist in the command table built by FlowScopeApp; a binding with no
// command silently does nothing. `device`, `search`, and `collapse.device` were bound but never
// implemented, and were removed rather than left advertising keys that do nothing.
internal val KeyBindings = listOf(
    KeyBinding(Key.K, false, "palette", "⌘K"),
    KeyBinding(Key.R, false, "record", "⌘R"),
    KeyBinding(Key.C, true, "checkpoint", "⌘⇧C"),
    KeyBinding(Key.S, false, "save", "⌘S"),
    KeyBinding(Key.S, true, "file.saveAs", "⌘⇧S"),
    KeyBinding(Key.Enter, false, "run", "⌘↵"),
    KeyBinding(Key.NumPadEnter, false, "run", "⌘↵"),
    KeyBinding(Key.Enter, true, "run.suite", "⌘⇧↵"),
    KeyBinding(Key.NumPadEnter, true, "run.suite", "⌘⇧↵"),
    KeyBinding(Key.M, false, "interaction", "⌘M"),
    KeyBinding(Key.N, false, "file.new", "⌘N"),
    KeyBinding(Key.N, true, "file.newSuite", "⌘⇧N"),
    KeyBinding(Key.O, true, "project.open", "⌘⇧O"),
    KeyBinding(Key.E, true, "rail.toggle", "⌘⇧E"),
    KeyBinding(Key.Comma, false, "project.settings", "⌘,"),
    KeyBinding(Key.One, false, "surface.timeline", "⌘1"),
    KeyBinding(Key.Two, false, "surface.flow", "⌘2"),
    KeyBinding(Key.Three, false, "surface.evidence", "⌘3"),
    KeyBinding(Key.Four, false, "surface.tree", "⌘4"),
    KeyBinding(Key.Five, false, "surface.perf", "⌘5"),
    // A numpad digit is a different Key from the number row, the same way NumPadEnter is from
    // Enter. Both reach the same surface; the printed label stays "⌘1".
    KeyBinding(Key.NumPad1, false, "surface.timeline", "⌘1"),
    KeyBinding(Key.NumPad2, false, "surface.flow", "⌘2"),
    KeyBinding(Key.NumPad3, false, "surface.evidence", "⌘3"),
    KeyBinding(Key.NumPad4, false, "surface.tree", "⌘4"),
    KeyBinding(Key.NumPad5, false, "surface.perf", "⌘5"),
    KeyBinding(Key.RightBracket, false, "collapse.drawer", "⌘]"),
)

/**
 * The keymap itself. Split out from [commandIdFor] so it is directly unit-testable: building a
 * Compose [KeyEvent] needs an AWT event source, which is not available in a headless test run.
 */
internal fun commandIdForKey(key: Key, shift: Boolean): String? =
    KeyBindings.firstOrNull { it.key == key && it.shift == shift }?.id

/** The label to print for a command, or "" when it has no key. Never hand-write these. */
internal fun shortcutFor(id: String): String =
    KeyBindings.firstOrNull { it.id == id }?.label.orEmpty()

/** Filters commands for the palette: substring match on the label, case-insensitive. */
internal fun filterCommands(commands: List<Command>, query: String): List<Command> {
    if (query.isBlank()) return commands
    val q = query.trim().lowercase()
    return commands.filter { it.label.lowercase().contains(q) }
        .sortedBy { if (it.label.lowercase().startsWith(q)) 0 else 1 }
}


/** Puts text on the host clipboard. Used for selectors and timeline exports. */
internal fun copyToClipboard(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }
}
