package dev.flowscope.ide

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellStateTest {

    @Test
    fun `settings toggle restores the previously active session surface`() {
        val state = ShellState().apply { surface = WorkSurface.Evidence }

        state.toggleSettings()
        assertEquals(WorkSurface.Settings, state.surface)

        state.toggleSettings()
        assertEquals(WorkSurface.Evidence, state.surface)
    }

    @Test
    fun `settings remembers the latest surface each time it opens`() {
        val state = ShellState()

        state.toggleSettings()
        state.toggleSettings()
        state.surface = WorkSurface.Perf
        state.toggleSettings()
        state.toggleSettings()

        assertEquals(WorkSurface.Perf, state.surface)
    }
}
