package dev.flowscope.ide

import dev.flowscope.host.AdbClient
import kotlin.test.*
import org.junit.jupiter.api.Test

class PerformanceCollectionTest {
    private val target = PerformanceTarget("device", "com.example")
    private class Adb(var pid: String = "123", val memoryExit: Int = 0, val graphicsExit: Int = 0,
                      val memory: String = "TOTAL PSS: 1024", val graphics: String = "Total frames rendered: 10",
                      val restart: Boolean = false) : AdbClient() {
        var pidCalls = 0
        override fun run(args: List<String>, timeout: Long): CommandResult = when {
            "pidof" in args -> CommandResult(0, if (restart && pidCalls++ > 0) "456" else pid, "")
            "meminfo" in args -> CommandResult(memoryExit, memory, "private error")
            else -> CommandResult(graphicsExit, graphics, "private error")
        }
    }
    @Test fun `complete and partial snapshots do not consume failed-command output`() {
        assertEquals(CollectionStatus.Complete, collectPerformanceSnapshot(Adb(), target).status)
        val partial = collectPerformanceSnapshot(Adb(memoryExit = 1), target)
        assertEquals(CollectionStatus.Partial, partial.status)
        assertNull(partial.snapshot?.totalPssKb)
        assertFalse(partial.snapshot!!.raw.contains("private error"))
    }
    @Test fun `empty failed and missing-process collections are distinct`() {
        assertEquals(CollectionStatus.Unavailable, collectPerformanceSnapshot(Adb(pid = ""), target).status)
        assertEquals(CollectionStatus.Unavailable, collectPerformanceSnapshot(Adb(memory = "no data", graphics = "no data"), target).status)
        assertEquals(CollectionStatus.Failed, collectPerformanceSnapshot(Adb(memoryExit = 1, graphicsExit = 1), target).status)
        assertEquals(CollectionStatus.Unavailable, collectPerformanceSnapshot(Adb(restart = true), target).status)
    }
    @Test fun `target switches reject delayed results and clear old metrics`() {
        val state = PerformanceState()
        state.select(target)
        val ticket = state.begin()
        state.select(target.copy(packageName = "other"))
        state.complete(ticket, collectPerformanceSnapshot(Adb(), target))
        assertNull(state.result)
        assertFalse(state.busy)
    }
    @Test fun `comparisons never cross process restarts or failed collections`() {
        val state = PerformanceState()
        state.select(target)
        fun complete(adb: Adb) { state.complete(state.begin(), collectPerformanceSnapshot(adb, target)) }
        complete(Adb())
        complete(Adb())
        assertNotNull(state.previous)
        complete(Adb(pid = "456"))
        assertNull(state.previous)
        complete(Adb(memoryExit = 1, graphicsExit = 1))
        assertNull(state.previous)
        assertNull(state.result?.snapshot)
    }
}
