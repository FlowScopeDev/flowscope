package dev.flowscope.ide

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class RunInspectionTest {
    @TempDir lateinit var root: Path
    private fun write(name: String, text: String): Path = root.resolve(name).also {
        Files.createDirectories(it.parent); Files.writeString(it, text)
    }
    private fun testReport(status: String = "passed") = """{"name":"Demo","status":"$status","mode":"zero-code","steps":[
        {"number":1,"line":5,"phase":"step","action":"tap","status":"passed","details":{}},
        {"number":2,"line":6,"phase":"after","action":"force-stop","status":"$status","details":{}}
    ],"performance":{"totalMillis":20,"uiDumps":1,"uiDumpMillis":10,"slowestSteps":[{"number":1,"action":"tap","durationMillis":20}]}}"""

    @Test fun `completed run classifies steps and keeps stable identities on refresh`() {
        write("report.json", testReport())
        val first = readRunInspection(root)
        assertEquals(listOf(Channel.Ui, Channel.Lifecycle), first.events.map { it.channel })
        assertEquals(first, readRunInspection(root))
        assertTrue(first.timings.first().contains("20 ms"))
        assertTrue(first.events.all { it.time == "—" })
    }
    @Test fun `live suite and cancelled child become authoritative without duplicating steps`() {
        val plan = """{"groups":[{"id":"before","name":"Suite setup","steps":[{"number":1,"line":3,"phase":"before","action":"api-request"}]},
          {"id":"test:1","name":"Demo","report":"child/report.json","steps":[{"number":1,"line":5,"phase":"step","action":"tap"},{"number":2,"line":6,"phase":"after","action":"force-stop"}]},
          {"id":"after","name":"Suite cleanup","steps":[{"number":2,"line":9,"phase":"after","action":"api-request"}]}]}"""
        write("run-plan.json", plan)
        val self = ProcessHandle.current()
        write("progress.jsonl", """{"event":"start","kind":"suite","name":"Suite","total":1,"pid":${self.pid()},"processStartedAt":"${self.info().startInstant().get()}","startedAt":"2026-10-28T00:00:00Z"}
{"event":"hook","number":1,"phase":"before","action":"api-request","status":"passed"}
{"event":"test","number":1,"total":1,"reference":"child.flow","status":"running"}
{"event":"step","number":1,"line":5,"phase":"step","action":"tap","status":"running"}
{"torn":""" )
        val live = readRunInspection(root)
        assertTrue(live.running)
        assertEquals(4, live.events.size)
        assertTrue(live.events.any { "running" in it.detail })
        write("child/report.json", testReport("cancelled"))
        write("suite-report.json", """{"name":"Suite","status":"cancelled","totals":{"total":1},"tests":[{"number":1,"name":"Demo","report":"child/report.json","status":"cancelled"}],"hooks":[]} """)
        Files.delete(root.resolve("progress.jsonl"))
        val ended = readRunInspection(root)
        assertFalse(ended.running)
        assertEquals(4, ended.events.size)
        assertTrue(ended.events.any { "cancelled" in it.detail })
        assertEquals(live.events.map { it.id }, ended.events.map { it.id })
    }
    @Test fun `interrupted work is not shown as passed`() {
        write("progress.jsonl", """{"event":"start","kind":"test","name":"Interrupted","total":1,"pid":999999999,"startedAt":"2026-10-28T00:00:00Z"}
{"event":"step","number":1,"total":1,"line":5,"phase":"step","action":"tap","status":"running"}
""")
        assertTrue(readRunInspection(root).events.single().detail.contains("interrupted"))
    }
    @Test fun `loads screenshots and only explicitly associated hierarchies`() {
        write("report.json", """{"name":"Evidence","status":"failed","steps":[
          {"number":1,"line":1,"phase":"step","action":"screenshot","status":"passed","details":{"evidence":"evidence/custom.png","hierarchy":"evidence/step-01.xml"}},
          {"number":2,"line":2,"phase":"step","action":"tap","status":"failed","details":{}}]}""")
        listOf("custom.png", "step-01.xml", "failure-02.png", "failure-02.xml", "unrelated.png", "unrelated.xml").forEach { write("evidence/$it", "dummy") }
        val data = readRunInspection(root)
        assertEquals(4, data.artifacts.size)
        assertEquals("step-01.xml", data.artifacts.first().hierarchy?.fileName.toString())
        assertEquals(1, data.artifacts.count { it.screenshot == null })
        assertEquals(1, data.artifacts.count { it.hierarchy == null })
    }
    @Test fun `refuses metadata and artifact symlinks escaping their owning run`() {
        val outside = Files.createTempDirectory("inspection-outside")
        try {
            val private = Files.writeString(outside.resolve("secret.json"), """{"name":"PRIVATE","status":"passed","steps":[]}""")
            Files.createSymbolicLink(root.resolve("report.json"), private)
            assertTrue(readRunInspection(root).notes.any { "Unsafe" in it })
            Files.delete(root.resolve("report.json"))
            write("report.json", testReport())
            Files.createDirectories(root.resolve("evidence"))
            Files.createSymbolicLink(root.resolve("evidence/leak.png"), private)
            assertTrue(readRunInspection(root).artifacts.isEmpty())
            assertNull(containedFile(root, "../secret.json"))
            assertNull(containedFile(root, private.toString()))
        } finally { outside.toFile().deleteRecursively() }
    }
    @Test fun `child artifact cannot escape to a suite sibling`() {
        write("run-plan.json", """{"groups":[{"id":"test:1","name":"Child","report":"child/report.json","steps":[]}]}""")
        write("child/report.json", """{"name":"Child","status":"passed","steps":[{"number":1,"details":{"evidence":"../other.png"}}]}""")
        write("other.png", "private")
        assertTrue(readRunInspection(root).artifacts.isEmpty())
    }
    @Test fun `agent summaries retain their category and test grouping`() {
        write("report.json", """{"name":"Demo","status":"passed","steps":[],"agentEvents":{"captured":true,"events":[
          {"offsetMillis":42,"kind":"network.request","summary":"GET /items/{id}"},
          {"kind":"background.work","summary":"sync done"}]}}""")
        val result = readRunInspection(root)
        assertEquals(listOf(Channel.Network, Channel.Background), result.events.map { it.channel })
        assertEquals("+42ms", result.events.first().time)
        assertTrue(result.events.first().detail.startsWith("Demo"))
    }
    @Test fun `malformed metadata gives an explicit message`() {
        write("report.json", "{partial")
        assertTrue(readRunInspection(root).notes.any { "unreadable" in it })
    }
    @Test fun `source survives details selection and session switching`() {
        val shell = ShellState()
        shell.selectRun(root)
        shell.selection = Selection.Event(CapturedEvent(1, "—", 0, "", "step", ""))
        assertEquals(root, shell.inspectionRun)
        assertTrue(shell.inspectRun)
        shell.inspectRun = false
        assertEquals(root, shell.inspectionRun)
    }
    @Test fun `empty guidance explains capture capabilities`() {
        assertTrue(channelGuidance(Channel.Logs, true, false, false).contains("not collected"))
        assertTrue(channelGuidance(Channel.Network, true, false, false).contains("Host API"))
        assertTrue(channelGuidance(Channel.Background, false, true, true).contains("hooks"))
    }
}
