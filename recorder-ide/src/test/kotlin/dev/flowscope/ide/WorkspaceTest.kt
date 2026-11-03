package dev.flowscope.ide

import dev.flowscope.host.Device
import dev.flowscope.host.ExecutionPlanRunner
import dev.flowscope.host.FlowProject
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class WorkspaceTest {
    @TempDir lateinit var temp: Path
    private fun project(name: String): Path {
        val root = temp.resolve(name)
        Files.createDirectories(root.resolve("flows/tests"))
        Files.writeString(root.resolve("flowscope.json"), """{"schemaVersion":1,"name":"$name"}""")
        Files.writeString(root.resolve("flows/tests/a.flow"), "flowscope 1\npackage com.example.debug\nstep screenshot name=a\n")
        Files.writeString(root.resolve("flows/tests/b.flow"), "flowscope 1\npackage com.example.debug\nstep screenshot name=b\n")
        return root
    }
    private fun device(serial: String) = Device(serial, "device", mapOf("model" to serial))

    @Test fun `opening another project retains independent editors and recordings`() {
        val model = DesktopWorkspace()
        val first = model.open(project("first"))
        first.current.authoring.source = "unsaved first"
        first.current.recording.status = "first capture"
        val second = model.open(project("second"))
        second.current.authoring.source = "unsaved second"
        assertSame(first, model.open(first.root!!))
        assertEquals("unsaved first", model.current.current.authoring.source)
        assertEquals("first capture", model.current.current.recording.status)
        assertEquals("unsaved second", second.current.authoring.source)
    }

    @Test fun `selecting another device creates an independent session and reselecting focuses it`() {
        val model = DesktopWorkspace()
        val project = model.open(project("app"))
        val original = project.current
        model.selectDevice(project, original, device("one"))
        original.authoring.source = "first buffer"
        model.selectDevice(project, original, device("two"))
        val second = project.current
        assertNotSame(original, second)
        assertEquals("", second.authoring.source)
        assertSame(original, model.addDevice(project, device("one")))
        assertEquals("first buffer", project.current.authoring.source)
        assertEquals(2, project.sessions.size)
    }

    @Test fun `device reservations span projects but do not block another serial`() {
        val model = DesktopWorkspace()
        val first = model.open(project("first")).current
        first.requestedSerial = "shared"
        first.authoring.running = true
        val second = model.open(project("second")).current
        second.requestedSerial = "shared"
        assertTrue(model.blocked(second))
        second.requestedSerial = "independent"
        assertFalse(model.blocked(second))
        model.executionSerials = setOf("independent")
        assertTrue(model.blocked(second))
        first.authoring.running = false
        model.executionSerials = emptySet()
        assertFalse(model.blocked(second))
    }

    @Test fun `save plan preserves separate queues projects and explicit repeated assignments`() {
        val model = DesktopWorkspace()
        val root = project("first")
        val first = model.open(root)
        first.current.jobs.add(root.resolve("flows/tests/a.flow"))
        val other = model.addDevice(first, device("second-device"))
        other.jobs.add(root.resolve("flows/tests/b.flow"))
        other.jobs.add(root.resolve("flows/tests/b.flow"))
        val secondRoot = project("second")
        model.open(secondRoot).current.jobs.add(secondRoot.resolve("flows/tests/a.flow"))
        val plan = model.savePlan(temp.resolve("shared/regression.runplan.json"))
        assertEquals(2, plan.projects().size)
        assertEquals(3, plan.queues().size)
        assertEquals(2, plan.queues()[1].jobs().size)
        val text = Files.readString(temp.resolve("shared/regression.runplan.json"))
        assertFalse(text.contains("second-device"))
        assertFalse(text.contains(root.toString()))
    }

    @Test fun `progress identity includes ordinal so duplicates remain separate`() {
        val model = DesktopWorkspace()
        model.updateProgress(ExecutionPlanRunner.JobProgress("app", "phone", 1, "a.flow", "running", temp))
        model.updateProgress(ExecutionPlanRunner.JobProgress("app", "phone", 2, "a.flow", "pending", temp))
        model.updateProgress(ExecutionPlanRunner.JobProgress("app", "phone", 1, "a.flow", "passed", temp))
        assertEquals(listOf("passed", "pending"), model.progress.map { it.status() })
    }

    @Test fun `local workspace state round trips device bindings assignment order and active selection`() {
        val root = temp.resolve("space and ünicode")
        val rows = listOf(WorkspaceLocation(root, "emulator-5554", true, "phone", listOf(root.resolve("a.flow"), root.resolve("b.flow")), true),
            WorkspaceLocation(root, null, false))
        assertEquals(rows, parseWorkspaceLocations(formatWorkspaceLocations(rows)))
        assertTrue(parseWorkspaceLocations("v1\nmalformed\n").isEmpty())
        assertTrue(parseWorkspaceLocations("v2\n").isEmpty())
    }

    @Test fun `editing assignments clears previous execution results`() {
        val model = DesktopWorkspace()
        model.updateProgress(ExecutionPlanRunner.JobProgress("app", "phone", 1, "a.flow", "passed", temp))
        model.report = temp.resolve("execution-report.json")
        model.assignmentsChanged()
        assertTrue(model.progress.isEmpty())
        assertNull(model.report)
    }

    @Test fun `new unsaved template requires save or explicit discard`() {
        val owner = DesktopSession(null)
        owner.authoring.adopt(null, "flowscope 1\npackage com.example.debug\n")
        assertTrue(owner.dirty)
        owner.authoring.markSaved(temp.resolve("new.flow"), owner.authoring.source)
        assertFalse(owner.dirty)
    }

    @Test fun `stale editors and new file collisions cannot overwrite a newer revision`() {
        val file = temp.resolve("a.flow")
        EditorWrites.write(file, "first\n", null)
        EditorWrites.write(file, "second\n", "first\n")
        assertThrows(IllegalStateException::class.java) { EditorWrites.write(file, "stale edit\n", "first\n") }
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { EditorWrites.write(file, "collision\n", null) }
        assertEquals("second\n", Files.readString(file))
    }
}
