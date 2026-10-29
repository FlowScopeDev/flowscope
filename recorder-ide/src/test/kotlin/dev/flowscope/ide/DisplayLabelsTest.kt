package dev.flowscope.ide

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DisplayLabelsTest {
    @TempDir lateinit var root: Path
    @Test fun `editor cards respect label without treating screenshot name as one`() {
        assertEquals("Screenshot of Home Taken", describeStep("step screenshot name=home label=\"Screenshot of Home Taken\""))
        assertEquals("Capture a screenshot", describeStep("step screenshot name=home"))
        assertEquals("Tapped View More", describeStep("step tap id=more label=\"Tapped View More\""))
    }
    @Test fun `timeline evidence and timing prefer labels but preserve action categories`() {
        Files.createDirectories(root.resolve("evidence"))
        Files.writeString(root.resolve("evidence/home.png"), "fixture")
        Files.writeString(root.resolve("report.json"), """{"name":"Demo","status":"passed","steps":[
          {"number":1,"line":5,"phase":"test","action":"screenshot","label":"Screenshot of Home Taken","status":"passed","details":{"evidence":"evidence/home.png"}}],
          "performance":{"totalMillis":20,"slowestSteps":[{"number":1,"action":"screenshot","label":"Screenshot of Home Taken","durationMillis":20}]}}""")
        val result = readRunInspection(root)
        assertEquals("Runner · Screenshot of Home Taken", result.events.single().title)
        assertEquals(Channel.Ui, result.events.single().channel)
        assertTrue(result.artifacts.single().reason.contains("Screenshot of Home Taken"))
        assertTrue(result.timings.last().contains("Screenshot of Home Taken"))
    }
}
