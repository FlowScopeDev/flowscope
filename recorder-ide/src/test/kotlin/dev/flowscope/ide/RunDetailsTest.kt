package dev.flowscope.ide

import dev.flowscope.host.RunActivity
import dev.flowscope.host.RunProgress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunDetailsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `clicking a run opens its drawer without changing the editor surface`() {
        val shell = ShellState().apply {
            surface = WorkSurface.Flow
            drawerOpen = false
            selection = Selection.Step(2, "step launch")
        }
        shell.selectRun(directory)
        assertTrue(shell.drawerOpen)
        assertEquals(Selection.Run(directory), shell.selection)
        assertEquals(WorkSurface.Flow, shell.surface)

        val other = directory.resolve("other-run")
        shell.selectRun(other)
        assertEquals(Selection.Run(other), shell.selection)
    }

    @Test
    fun `selected test updates through progress completion and later HTML creation`() = runBlocking {
        start("test", 2)
        val snapshots = withTimeout(10_000) {
            watchRunDetails(directory, 10).distinctUntilChanged().onEach { details ->
                val report = assertNotNull(details.report)
                when {
                    report.isRunning && report.activity() == null -> append(
                        """{"event":"step","number":2,"total":2,"phase":"test","action":"tap","line":8,"status":"running"}""",
                    )
                    report.isRunning -> {
                        Files.writeString(directory.resolve("report.json"), """
                            {"name":"Example","status":"passed","steps":[{"status":"passed"},{"status":"passed"}]}
                        """.trimIndent())
                        Files.delete(directory.resolve(RunProgress.FILE))
                    }
                    details.htmlReport == null -> Files.writeString(directory.resolve("report.html"), "report")
                }
            }.take(4).toList()
        }
        assertEquals("step 2/2 · tap", snapshots[1].report!!.activity())
        assertTrue(snapshots[2].report!!.isPassed)
        assertEquals(2, snapshots[2].report!!.passed())
        assertNull(snapshots[2].htmlReport)
        assertEquals(directory.resolve("report.html"), snapshots[3].htmlReport)
    }

    @Test
    fun `suite details show child test and step then the final suite result`() = runBlocking {
        start("suite", 3)
        append("""{"event":"test","number":2,"total":3,"reference":"tests/checkout.flow","status":"running"}""")
        append("""{"event":"step","number":5,"total":12,"phase":"test","action":"assert-visible","line":10,"status":"running"}""")
        val snapshots = withTimeout(10_000) {
            watchRunDetails(directory, 10).distinctUntilChanged().onEach { details ->
                if (details.report?.isRunning == true) {
                    Files.writeString(directory.resolve("suite-report.json"), """
                        {"name":"Example","status":"failed","totals":{"total":3,"passed":1,"failed":1,"skipped":1}}
                    """.trimIndent())
                    Files.writeString(directory.resolve("suite-report.html"), "report")
                    Files.delete(directory.resolve(RunProgress.FILE))
                }
            }.take(2).toList()
        }
        val live = assertNotNull(snapshots.first().report)
        assertTrue(live.isSuite)
        assertEquals("test 2/3 · tests/checkout.flow · step 5/12 · assert-visible", live.activity())
        val finished = assertNotNull(snapshots.last().report)
        assertEquals("failed", finished.status())
        assertFalse(finished.isRunning)
        assertEquals(1, finished.passed())
        assertEquals(directory.resolve("suite-report.html"), snapshots.last().htmlReport)
    }

    @Test
    fun `missing or interrupted runs remain inspectable without a finished report`() {
        assertNull(RunDetails.read(directory).report)
        start("test", 2)
        append("""{"event":"end","status":"interrupted"}""")
        val interrupted = RunDetails.read(directory)
        assertTrue(assertNotNull(interrupted.report).isInterrupted)
        assertNull(interrupted.htmlReport)
    }

    @Test
    fun `details include named passed current and future steps from the run plan`() {
        start("test", 3)
        Files.writeString(directory.resolve("run-plan.json"), """
            {"groups":[{"id":"test","name":"Example","report":"report.json","steps":[
            {"number":1,"line":3,"phase":"before","action":"set-value"},
            {"number":2,"line":4,"phase":"test","action":"tap"},
            {"number":3,"line":5,"phase":"after","action":"force-stop"}]}]}
        """.trimIndent())
        append("""{"event":"step","number":1,"total":3,"phase":"before","action":"set-value","line":3,"status":"passed"}""")
        append("""{"event":"step","number":2,"total":3,"phase":"test","action":"tap","line":4,"status":"running"}""")
        val details = RunDetails.read(directory)
        assertTrue(details.steps.planned())
        val steps = details.steps.groups().single().steps()
        assertEquals(listOf("passed", "running", "pending"), steps.map { it.status() })
        assertEquals(listOf("set-value", "tap", "force-stop"), steps.map { it.action() })
        assertEquals(listOf(3, 4, 5), steps.map { it.line() })
    }

    @Test
    fun `a ticking elapsed value is a new RunDetails, so the panel keeps redrawing`() {
        // The watcher polls into a mutableStateOf and Compose compares structurally, so an
        // equal-but-new value would not recompose and the counter would appear frozen. Elapsed
        // changing on every frame is what keeps it alive — rather than a UI-side ticker, which
        // would keep counting after the writer died.
        planned()
        activity(elapsed = 1200)
        val first = RunDetails.read(directory)
        activity(elapsed = 2200)
        val second = RunDetails.read(directory)

        assertNotNull(first.activity)
        assertNotEquals(first, second)
        assertEquals("awaiting response · 1.2s of 30s", first.activity?.describe())
        assertEquals("awaiting response · 2.2s of 30s", second.activity?.describe())
    }

    @Test
    fun `a frame for a step that is no longer running is dropped`() {
        // The heartbeat sleeps through a step boundary, so a frame can outlive its step by a tick.
        planned()
        activity(number = 1, phase = "before", elapsed = 900)
        assertNull(RunDetails.read(directory).activity)
    }

    @Test
    fun `a frame left behind by a dead writer is not shown as live`() {
        Files.writeString(directory.resolve(RunProgress.FILE),
            """{"event":"start","kind":"test","name":"Example","total":3,"pid":2,"processStartedAt":"1999-01-01T00:00:00Z","startedAt":"2026-10-27T10:00:00Z"}""" + "\n")
        append("""{"event":"step","number":2,"total":3,"phase":"test","action":"tap","line":4,"status":"running"}""")
        activity(elapsed = 9000)
        val details = RunDetails.read(directory)
        assertTrue(details.report?.isInterrupted == true)
        assertNull(details.activity)
    }

    /** A run stopped on step 2, which is the step a frame has to name to count. */
    private fun planned() {
        start("test", 3)
        Files.writeString(directory.resolve("run-plan.json"), """
            {"groups":[{"id":"test","name":"Example","report":"report.json","steps":[
            {"number":1,"line":3,"phase":"before","action":"api-request"},
            {"number":2,"line":4,"phase":"test","action":"api-request"},
            {"number":3,"line":5,"phase":"after","action":"force-stop"}]}]}
        """.trimIndent())
        append("""{"event":"step","number":2,"total":3,"phase":"test","action":"api-request","line":4,"status":"running"}""")
    }

    private fun activity(number: Int = 2, phase: String = "test", elapsed: Long) {
        Files.writeString(directory.resolve(RunActivity.FILE),
            """{"schemaVersion":1,"phase":"$phase","number":$number,"total":3,""" +
                """"stage":"awaiting-response","elapsedMillis":$elapsed,"budgetMillis":30000,""" +
                """"attempt":1,"attemptLimit":0,"updatedAt":"2026-10-27T10:00:01Z"}""")
    }

    private fun start(kind: String, total: Int) {
        Files.writeString(directory.resolve(RunProgress.FILE),
            """{"event":"start","kind":"$kind","name":"Example","total":$total,"pid":${ProcessHandle.current().pid()},"startedAt":"2026-10-27T10:00:00Z"}""" + "\n")
    }

    private fun append(event: String) {
        Files.writeString(directory.resolve(RunProgress.FILE), "$event\n", APPEND)
    }
}
