package dev.flowscope.ide

import kotlin.test.assertNull

import androidx.compose.ui.input.key.Key

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandsTest {

    private fun command(label: String) = Command(label, label, "", true) {}

    @Test
    fun `empty query returns everything in order`() {
        val all = listOf(command("Record"), command("Save flow"), command("Run on device"))
        assertEquals(all, filterCommands(all, "   "))
    }

    @Test
    fun `matches anywhere in the label`() {
        val all = listOf(command("Record"), command("Save flow"), command("Run on device"))
        assertEquals(listOf("Save flow", "Run on device"), filterCommands(all, "o").map { it.label }
            .filter { it != "Record" })
    }

    @Test
    fun `prefix matches rank first`() {
        val all = listOf(command("Open session folder"), command("Run on device"))
        assertEquals("Run on device", filterCommands(all, "run").first().label)
    }

    @Test
    fun `is case insensitive`() {
        val all = listOf(command("Capture checkpoint"))
        assertTrue(filterCommands(all, "CHECKPOINT").isNotEmpty())
    }

    @Test
    fun `no match yields an empty list`() {
        assertTrue(filterCommands(listOf(command("Record")), "zzz").isEmpty())
    }
}

class KeymapTest {
    @Test
    fun `keeps every binding that existed before projects`() {
        assertEquals("palette", commandIdForKey(Key.K, shift = false))
        assertEquals("record", commandIdForKey(Key.R, shift = false))
        assertEquals("checkpoint", commandIdForKey(Key.C, shift = true))
        assertNull(commandIdForKey(Key.C, shift = false))
        assertEquals("interaction", commandIdForKey(Key.M, shift = false))
        // D, F and [ were bound to commands that never existed; they are deliberately unbound.
        assertNull(commandIdForKey(Key.D, shift = false))
        assertNull(commandIdForKey(Key.F, shift = false))
        assertNull(commandIdForKey(Key.LeftBracket, shift = false))
        assertEquals("surface.timeline", commandIdForKey(Key.One, shift = false))
        assertEquals("surface.flow", commandIdForKey(Key.Two, shift = false))
        assertEquals("surface.evidence", commandIdForKey(Key.Three, shift = false))
        assertEquals("surface.tree", commandIdForKey(Key.Four, shift = false))
        assertEquals("surface.perf", commandIdForKey(Key.Five, shift = false))
        assertEquals("collapse.drawer", commandIdForKey(Key.RightBracket, shift = false))
        assertNull(commandIdForKey(Key.Z, shift = false))
    }

    @Test
    fun `shift turns save into save as and run into run suite`() {
        assertEquals("save", commandIdForKey(Key.S, shift = false))
        assertEquals("file.saveAs", commandIdForKey(Key.S, shift = true))
        assertEquals("run", commandIdForKey(Key.Enter, shift = false))
        assertEquals("run.suite", commandIdForKey(Key.Enter, shift = true))
        assertEquals("run", commandIdForKey(Key.NumPadEnter, shift = false))
        assertEquals("run.suite", commandIdForKey(Key.NumPadEnter, shift = true))
    }

    @Test
    fun `binds the project and file commands`() {
        assertEquals("project.open", commandIdForKey(Key.O, shift = true))
        assertNull(commandIdForKey(Key.O, shift = false))
        assertEquals("project.settings", commandIdForKey(Key.Comma, shift = false))
        assertEquals("file.new", commandIdForKey(Key.N, shift = false))
        assertEquals("file.newSuite", commandIdForKey(Key.N, shift = true))
        assertEquals("rail.toggle", commandIdForKey(Key.E, shift = true))
        assertNull(commandIdForKey(Key.E, shift = false))
    }

    @Test
    fun `every mapped id is a real command id`() {
        // Guards against a keymap entry that dispatch() would silently drop.
        val known = setOf(
            "palette", "record", "checkpoint", "save", "file.saveAs", "run", "run.suite",
            "device", "interaction", "search", "file.new", "file.newSuite", "project.open",
            "project.settings", "rail.toggle", "surface.timeline", "surface.flow",
            "surface.evidence", "surface.tree", "surface.perf", "collapse.drawer",
        )
        val keys = listOf(
            Key.K, Key.R, Key.C, Key.S, Key.Enter, Key.NumPadEnter, Key.M,
            Key.N, Key.O, Key.E, Key.Comma, Key.One, Key.Two, Key.Three, Key.Four, Key.Five,
            Key.RightBracket,
        )
        for (key in keys) {
            for (shift in listOf(true, false)) {
                val id = commandIdForKey(key, shift) ?: continue
                assertTrue(id in known, "unmapped command id: $id")
            }
        }
    }
}

class KeyBindingTableTest {
    @Test
    fun `no key and modifier pair is bound twice`() {
        val seen = mutableSetOf<Pair<Key, Boolean>>()
        for (binding in KeyBindings) {
            assertTrue(seen.add(binding.key to binding.shift),
                "duplicate binding for ${binding.key} shift=${binding.shift}")
        }
    }

    @Test
    fun `every binding prints a label and every label starts with command`() {
        for (binding in KeyBindings) {
            assertTrue(binding.label.isNotBlank(), "${binding.id} has no printed label")
            assertTrue(binding.label.startsWith("⌘"), "${binding.id} label is ${binding.label}")
        }
    }

    @Test
    fun `a printed shortcut always names a key that really runs the command`() {
        // The Settings screen shows these as documentation, so a label that claims a key the
        // keymap does not honour would be a visible lie.
        for (binding in KeyBindings) {
            assertEquals(binding.label, shortcutFor(binding.id))
            assertEquals(binding.id, commandIdForKey(binding.key, binding.shift))
        }
    }

    @Test
    fun `a shift label is printed if and only if the binding needs shift`() {
        for (binding in KeyBindings) {
            assertEquals(binding.shift, binding.label.contains("⇧"),
                "${binding.id} label ${binding.label} disagrees with shift=${binding.shift}")
        }
    }

    @Test
    fun `an unbound command has no printed shortcut`() {
        assertEquals("", shortcutFor("project.reveal"))
        assertEquals("", shortcutFor("export.file"))
        assertEquals("", shortcutFor("nonexistent"))
    }

    @Test
    fun `commands bound to two keys print one label`() {
        // Enter and NumPadEnter both run it; the cheat sheet must not show the shortcut twice.
        assertEquals("⌘↵", shortcutFor("run"))
        assertEquals("⌘⇧↵", shortcutFor("run.suite"))
    }

    @Test
    fun `numpad digits reach the same surfaces as the number row`() {
        // Proven against a real window: a numpad 2 arrives as a different Key from the number
        // row's 2, exactly as NumPadEnter differs from Enter.
        assertEquals("surface.timeline", commandIdForKey(Key.NumPad1, shift = false))
        assertEquals("surface.flow", commandIdForKey(Key.NumPad2, shift = false))
        assertEquals("surface.evidence", commandIdForKey(Key.NumPad3, shift = false))
        assertEquals("surface.tree", commandIdForKey(Key.NumPad4, shift = false))
        assertEquals("surface.perf", commandIdForKey(Key.NumPad5, shift = false))
    }

    @Test
    fun `a surface bound to two keys still prints one label`() {
        assertEquals("⌘2", shortcutFor("surface.flow"))
        assertEquals("⌘5", shortcutFor("surface.perf"))
    }
}
