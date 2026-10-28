package dev.flowscope.ide

import dev.flowscope.host.RunCancellation
import kotlin.test.*

class RunStopTest {
    @Test fun `running control invokes stop even when starting a run is disabled`() {
        var started = 0
        var stopped = 0
        val control = RunControl(false, true, EditorKind.Suite, "", { started++ }, onStop = { stopped++ })
        assertEquals("Stop run", control.label)
        control.activate()
        assertEquals(0, started)
        assertEquals(1, stopped)
        val stopping = control.copy(stopping = true)
        assertFalse(stopping.actionable)
        stopping.activate()
        assertEquals(1, stopped)
    }
    @Test fun `stop state waits for cleanup and never cancels an external run`() {
        val state = AuthoringState()
        state.stopRun()
        assertFalse(state.stopping)
        val signal = RunCancellation()
        state.cancellation = signal
        state.running = true
        state.stopRun()
        assertTrue(signal.isRequested)
        assertTrue(state.running)
        assertTrue(state.stopping)
        assertEquals("Stopping · running cleanup…", state.status)
        assertEquals("FlowScope · Test cancelled", cancelledNotice("test", false).title)
    }
}
