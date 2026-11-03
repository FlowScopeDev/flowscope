package dev.flowscope.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.flowscope.host.ExecutionCancellation
import dev.flowscope.host.ExecutionPlan
import dev.flowscope.host.ExecutionPlanRunner
import dev.flowscope.host.Device
import java.nio.file.Path
import java.util.UUID

/** Long-lived owners. Only the visible session emits UI; every owner's workers stay mounted. */
internal class DesktopSession(val initialRoot: Path?, val restoreRecent: Boolean = false, serial: String? = null, roleName: String? = null, val autoSelect: Boolean = true) {
    val id = UUID.randomUUID().toString()
    val role = roleName ?: ("device-" + id.take(8))
    val authoring = AuthoringState()
    val project = ProjectState()
    val device = DeviceState()
    val recording = SessionState()
    val timeline = TimelineState()
    val shell = ShellState()
    val performance = PerformanceState()
    var requestedSerial by mutableStateOf(serial)
    val jobs = mutableStateListOf<Path>()
    var continueOnFailure by mutableStateOf(false)
    var save: (suspend () -> Boolean)? = null
    var handleKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { false }
    val busy: Boolean get() = authoring.running || recording.isRecording || device.busy
    val dirty: Boolean get() = authoring.dirty && authoring.source.isNotBlank()
    val serial: String? get() = requestedSerial ?: device.serial
    val label: String get() = device.selected?.let { "${it.displayName()} (${it.serial()})" }
        ?: requestedSerial ?: "Choose device"
}

internal class DesktopProject(root: Path?, restoreRecent: Boolean = false) {
    val id = UUID.randomUUID().toString()
    var root by mutableStateOf(root?.toAbsolutePath()?.normalize())
    val sessions = mutableStateListOf(DesktopSession(root, restoreRecent))
    var selected by mutableStateOf(sessions.first().id)
    val current: DesktopSession get() = sessions.first { it.id == selected }
    val label: String get() = sessions.firstOrNull()?.project?.manifest?.name() ?: root?.fileName?.toString() ?: "Scratch"
}

internal class DesktopWorkspace {
    val projects = mutableStateListOf(DesktopProject(null, restoreRecent = true))
    var selected by mutableStateOf(projects.first().id)
    var assignmentsVisible by mutableStateOf(false)
    var status by mutableStateOf("")
    var preparing by mutableStateOf(false)
    val locked: Boolean get() = preparing || running
    var running by mutableStateOf(false)
    var stopping by mutableStateOf(false)
    var cancellation: ExecutionCancellation? = null
    val progress = mutableStateListOf<ExecutionPlanRunner.JobProgress>()
    var report by mutableStateOf<Path?>(null)
    var planFile by mutableStateOf<Path?>(null)
    var executionSerials by mutableStateOf<Set<String>>(emptySet())
    val current: DesktopProject get() = projects.first { it.id == selected }
    val sessions: List<DesktopSession> get() = projects.flatMap { it.sessions }

    fun open(root: Path): DesktopProject {
        val path = root.toAbsolutePath().normalize()
        val project = projects.firstOrNull { it.root == path } ?: DesktopProject(path).also { projects.add(it) }
        selected = project.id
        assignmentsVisible = false
        return project
    }
    fun selectDevice(project: DesktopProject, session: DesktopSession, device: Device) {
        val existing = project.sessions.firstOrNull { it.serial == device.serial() }
        if (existing != null) project.selected = existing.id
        else if (session.serial == null && !session.busy) {
            session.requestedSerial = device.serial()
            session.device.selected = device
        } else addDevice(project, device)
    }
    fun addDevice(project: DesktopProject, device: Device): DesktopSession {
        val existing = project.sessions.firstOrNull { it.serial == device.serial() }
        val session = existing ?: DesktopSession(project.root, serial = device.serial()).also { project.sessions.add(it) }
        project.selected = session.id
        return session
    }
    fun blocked(session: DesktopSession): Boolean {
        val serial = session.serial ?: return false
        return serial in executionSerials || sessions.any { it !== session && it.serial == serial && it.busy }
    }
    fun remove(project: DesktopProject, session: DesktopSession? = null) {
        if (session == null || project.sessions.size == 1) {
            projects.remove(project)
            if (projects.isEmpty()) projects.add(DesktopProject(null))
            if (projects.none { it.id == selected }) selected = projects.first().id
        } else {
            project.sessions.remove(session)
            if (project.sessions.none { it.id == project.selected }) project.selected = project.sessions.first().id
        }
    }
    fun assignmentsChanged() {
        if (running) return
        progress.clear()
        report = null
        status = "Assignments changed; run them to see results"
    }

    fun updateProgress(event: ExecutionPlanRunner.JobProgress) {
        val at = progress.indexOfFirst { it.role() == event.role() && it.ordinal() == event.ordinal() }
        if (at < 0) progress.add(event) else progress[at] = event
    }

    /** Paths stay relative to the saved plan; only role names enter the shared artifact. */
    fun savePlan(path: Path): ExecutionPlan {
        val assigned = projects.filter { project -> project.sessions.any { it.jobs.isNotEmpty() } }
        require(assigned.isNotEmpty()) { "Assign at least one test or suite to a device" }
        val roots = linkedMapOf<String, Path>()
        val queues = mutableListOf<ExecutionPlan.Assignment>()
        assigned.forEachIndexed { index, project ->
            val root = requireNotNull(project.root) { "Open a project before assigning tests" }
            val alias = "project-${index + 1}"
            roots[alias] = root
            project.sessions.filter { it.jobs.isNotEmpty() }.forEach { session ->
                val jobs = session.jobs.map { job ->
                    require(job.toAbsolutePath().normalize().startsWith(root)) { "Assigned file is outside its project" }
                    root.relativize(job.toAbsolutePath().normalize()).toString().replace('\\', '/')
                }
                queues.add(ExecutionPlan.Assignment(alias, session.role, jobs, session.continueOnFailure))
            }
        }
        ExecutionPlan.save(path, "Desktop regression", roots, queues, queues.size)
        return ExecutionPlan.read(path)
    }
}
