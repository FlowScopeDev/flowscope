package dev.flowscope.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.dp
import dev.flowscope.host.AdbClient
import dev.flowscope.host.ExecutionCancellation
import dev.flowscope.host.ExecutionPlan
import dev.flowscope.host.ExecutionPlanRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.nio.file.Path

@Composable
internal fun DesktopWorkspaceApp(
    theme: ThemeMode,
    window: Frame?,
    shortcuts: MutableState<(KeyEvent) -> Boolean>,
    closeRequest: MutableState<() -> Unit>,
    onTheme: (ThemeMode) -> Unit,
    onExit: () -> Unit,
) {
    val workspace = remember { DesktopWorkspace() }
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var closeTarget by remember { mutableStateOf<Pair<DesktopProject, DesktopSession?>?>(null) }
    var exitRequested by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var deviceMenu by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(emptyList<dev.flowscope.host.Device>()) }

    LaunchedEffect(Unit) {
        val rows = withContext(Dispatchers.IO) { loadWorkspaceLocations() }
        if (rows.isNotEmpty()) {
            workspace.projects.clear()
            rows.groupBy { it.root }.forEach { (root, locations) ->
                val project = DesktopProject(root)
                project.sessions.clear()
                locations.forEach { location ->
                    val session = DesktopSession(root, serial = location.serial, roleName = location.role, autoSelect = location.serial != null)
                    session.jobs.addAll(location.jobs)
                    session.continueOnFailure = location.continueOnFailure
                    project.sessions.add(session)
                    if (location.active) { project.selected = session.id; workspace.selected = project.id }
                }
                if (project.sessions.none { it.id == project.selected }) project.selected = project.sessions.first().id
                workspace.projects.add(project)
            }
            if (workspace.projects.none { it.id == workspace.selected }) workspace.selected = workspace.projects.first().id
        }
        if (rows.isEmpty()) {
            val saved = withContext(Dispatchers.IO) { java.nio.file.Files.exists(FlowScopeHome.resolve("desktop-workspaces.txt")) }
            if (saved) {
                val project = DesktopProject(null)
                workspace.projects.clear(); workspace.projects.add(project); workspace.selected = project.id
            }
            val recent = withContext(Dispatchers.IO) { Preferences.loadRecentProjects().firstOrNull() }
            if (!saved && recent != null) {
                val project = DesktopProject(recent.root)
                workspace.projects.clear(); workspace.projects.add(project); workspace.selected = project.id
            }
        }
        loaded = true
        while (true) {
            available = withContext(Dispatchers.IO) { runCatching { AdbClient().devices().filter { it.state() == "device" } }.getOrDefault(emptyList()) }
            delay(3_000)
        }
    }
    val locations = workspace.projects.flatMap { project -> project.root?.let { root ->
        project.sessions.map { session -> WorkspaceLocation(root, session.serial,
            workspace.selected == project.id && project.selected == session.id, session.role,
            session.jobs.toList(), session.continueOnFailure) }
    }.orEmpty() }
    LaunchedEffect(loaded, locations, workspace.sessions.map { it.project.restoring }) {
        if (loaded && workspace.sessions.none { it.project.restoring }) {
            delay(400)
            withContext(Dispatchers.IO) { runCatching { saveWorkspaceLocations(locations) } }
        }
    }
    SideEffect {
        shortcuts.value = { event ->
            if (!workspace.assignmentsVisible) workspace.current.current.handleKey(event) else false
        }
        closeRequest.value = {
            if (workspace.sessions.any { it.busy || it.dirty } || workspace.running || workspace.preparing) exitRequested = true
            else scope.launch { withContext(Dispatchers.IO) { runCatching { saveWorkspaceLocations(locations) } }; onExit() }
        }
    }

    fun loadPlan(path: Path) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ExecutionPlan.read(path) } }.onSuccess { plan ->
                plan.projects().forEach { (alias, source) ->
                    val existing = workspace.projects.firstOrNull { it.root == source.root() }
                    val project = workspace.open(source.root())
                    if (existing == null) project.sessions.clear()
                    plan.queues().filter { it.project() == alias }.forEach { queue ->
                        val existingSession = project.sessions.firstOrNull { it.role == queue.device() }
                        val role = if (existingSession == null && workspace.sessions.any { it.role == queue.device() })
                            queue.device().take(55) + "-" + java.util.UUID.randomUUID().toString().take(8) else queue.device()
                        val session = existingSession
                            ?: DesktopSession(project.root, roleName = role, autoSelect = false).also { project.sessions.add(it) }
                        session.jobs.clear()
                        session.jobs.addAll(queue.jobs().map { it.file() })
                        session.continueOnFailure = queue.continueOnFailure()
                        project.selected = session.id
                    }
                }
                workspace.assignmentsChanged()
                workspace.planFile = path
                workspace.status = "Plan loaded. Choose a device for each queue before running."
                workspace.assignmentsVisible = true
            }.onFailure { workspace.status = it.message ?: "Could not load plan" }
        }
    }
    fun savePlan(run: Boolean) {
        scope.launch {
            if (workspace.locked) return@launch
            val assigned = workspace.sessions.filter { it.jobs.isNotEmpty() }
            if (assigned.isEmpty()) { workspace.status = "Assign at least one test or suite"; return@launch }
            if (assigned.any { it.dirty }) {
                workspace.status = "Save the edited files in assigned sessions before running or saving the plan"
                return@launch
            }
            if (run && assigned.any { it.busy || workspace.blocked(it) }) {
                workspace.status = "An assigned device is busy. Stop its current work first."
                return@launch
            }
            val target = if (run) FlowScopeHome.resolve("plans/desktop.runplan.json") else {
                chooseSavePlan(window) ?: return@launch
            }
            workspace.preparing = true
            val plan = runCatching { withContext(Dispatchers.IO) { workspace.savePlan(target) } }
                .getOrElse { workspace.preparing = false; workspace.status = it.message ?: "Could not save execution plan"; return@launch }
            workspace.preparing = false
            workspace.planFile = target
            if (!run) { workspace.status = "Saved $target"; return@launch }
            val bindings = runCatching { plan.bindings(assigned.map { "${it.role}=${requireNotNull(it.serial) { "Choose a device for every queue" }}" }) }
                .getOrElse { workspace.status = it.message ?: "Check device assignments"; return@launch }
            val roots = workspace.projects.mapNotNull { project -> project.root?.let { it to project } }.toMap()
            val secrets = plan.projects().mapNotNull { (alias, source) ->
                roots[source.root()]?.let { Preferences.loadSecretsFile(source.root()) }?.let { alias to it }
            }.toMap()
            val cancellation = ExecutionCancellation()
            workspace.cancellation = cancellation
            workspace.executionSerials = bindings.values.toSet()
            workspace.running = true
            workspace.stopping = false
            workspace.report = null
            workspace.progress.clear()
            workspace.status = "Running assignments"
            try {
                val result = withContext(Dispatchers.IO) {
                    ExecutionPlanRunner.run(plan, bindings, secrets, null, plan.maxParallel(), false, AdbClient(), cancellation) { progress ->
                        scope.launch { workspace.updateProgress(progress) }
                    }
                }
                workspace.report = result.report()
                workspace.status = if (workspace.stopping) "Execution stopped; cleanup and reports complete" else if (result.passed()) "All assigned jobs passed" else "Execution finished with failed or skipped jobs"
                DesktopNotifications.show(regressionCompletionNotice("Assigned tests", result.passed(),
                    workspace.progress.size, workspace.progress.count { it.status() == "passed" }, workspace.progress.count { it.status() == "failed" }))
            } catch (failure: Exception) {
                workspace.status = "Execution could not complete. Check device availability and plan inputs."
            } finally {
                workspace.running = false
                workspace.stopping = false
                workspace.executionSerials = emptySet()
                workspace.cancellation = null
            }
        }
    }
    fun stopAll() {
        workspace.cancellation?.requestAll()
        workspace.stopping = true
        workspace.status = "Stopping; waiting for cleanup and reports"
    }
    fun closeOwners(save: Boolean) {
        if (closing) return
        closing = true
        scope.launch {
            val owners = if (exitRequested) workspace.sessions else closeTarget?.let { (project, session) -> session?.let(::listOf) ?: project.sessions.toList() }.orEmpty()
            do {
                if (exitRequested && workspace.running && !workspace.stopping) stopAll()
                owners.forEach { owner ->
                    owner.authoring.stopRun()
                    owner.recording.recording?.let { recording ->
                        withContext(Dispatchers.IO) { runCatching { recording.close() } }
                        owner.recording.recording = null
                    }
                }
                if (owners.any { it.busy } || (exitRequested && (workspace.running || workspace.preparing))) delay(100)
            } while (owners.any { it.busy } || (exitRequested && (workspace.running || workspace.preparing)))
            if (save) {
                for (owner in owners.filter { it.dirty }) {
                    if (runCatching { owner.save?.invoke() }.getOrDefault(false) != true) {
                        workspace.status = "Could not save an edited file; the session remains open"
                        closing = false
                        return@launch
                    }
                }
            }
            if (exitRequested) {
                withContext(Dispatchers.IO) { runCatching { saveWorkspaceLocations(locations) } }
                onExit()
            } else closeTarget?.let { (project, session) -> workspace.remove(project, session) }
            closeTarget = null
            closing = false
        }
    }

    Column(Modifier.fillMaxSize().background(AppBackground)) {
        Row(Modifier.fillMaxWidth().height(36.dp).background(Ink100)
            .padding(start = if (IsMac) 80.dp else 8.dp, end = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                workspace.projects.forEach { project ->
                    WorkspaceTab(project.label + if (project.sessions.any { it.busy }) " · working" else "",
                        "Project ${project.label}", workspace.selected == project.id, !workspace.preparing,
                        onSelect = { workspace.selected = project.id; workspace.assignmentsVisible = false },
                        onClose = { closeTarget = project to null })
                }
            }
            TextButton(onClick = { chooseDirectory(window, "Open FlowScope project")?.let(workspace::open) }) { Text("Open project…") }
            TextButton(onClick = { workspace.assignmentsVisible = !workspace.assignmentsVisible }) {
                Text("Assignments${if (workspace.running) " · running" else ""}", color = if (workspace.assignmentsVisible) SignalIce else TextMid)
            }
        }
        HorizontalDivider(color = Border)
        if (loaded) {
            val project = workspace.current
            Row(Modifier.fillMaxWidth().height(36.dp).background(Ink100).padding(horizontal = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    project.sessions.forEach { session ->
                        val label = session.device.selected?.displayName() ?: session.requestedSerial ?: "Choose device"
                        WorkspaceTab(label + when { session.recording.isRecording -> " · recording"; session.authoring.running -> " · running"; session.dirty -> " *"; else -> "" },
                            "Device ${session.label}", project.selected == session.id, !workspace.preparing,
                            onSelect = { project.selected = session.id; workspace.assignmentsVisible = false },
                            onClose = { closeTarget = project to session })
                    }
                }
                Box {
                    TextButton(onClick = { deviceMenu = true }, enabled = project.root != null && !workspace.locked) { Text("Add device…") }
                    DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                        if (available.isEmpty()) DropdownMenuItem(text = { Text("No connected devices") }, onClick = {}, enabled = false)
                        available.forEach { device -> DropdownMenuItem(text = { Text("${device.displayName()} (${device.serial()})") }, onClick = { workspace.addDevice(project, device); deviceMenu = false }) }
                    }
                }
            }
            HorizontalDivider(color = Border)
        }
        if (workspace.status.isNotBlank()) Text(workspace.status, color = TextMid, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        Box(Modifier.weight(1f)) {
            if (loaded) workspace.projects.toList().forEach { project ->
                project.sessions.toList().forEach { session -> key(session.id) {
                    FlowScopeApp(theme, window, shortcuts, onTheme, session,
                        visible = !workspace.assignmentsVisible && workspace.selected == project.id && project.selected == session.id,
                        blocked = workspace.blocked(session),
                        onOpenProject = { workspace.open(it) },
                        onProjectOpened = { project.root = it },
                        onCloseProject = { closeTarget = project to null },
                        onSelectDevice = { workspace.selectDevice(project, session, it) }, discoveredDevices = available)
                } }
            }
            if (loaded && workspace.assignmentsVisible) AssignmentSurface(workspace,
                onSave = { savePlan(false) }, onLoad = { chooseFile(window, "Open execution plan")?.let(::loadPlan) },
                onRun = { savePlan(true) }, onStop = ::stopAll,
                onStopQueue = { workspace.cancellation?.request(it.role) },
                onReport = { workspace.report?.let { path -> openWithDefaultApplication(path.resolveSibling("execution-report.html")) } },
                onInspect = { event ->
                    workspace.sessions.firstOrNull { it.role == event.role() }?.let { session ->
                        workspace.projects.firstOrNull { session in it.sessions }?.let { project ->
                            workspace.selected = project.id; project.selected = session.id
                            session.shell.selectRun(event.reportDirectory()); workspace.assignmentsVisible = false
                        }
                    }
                })
        }
    }
    if (closeTarget != null || exitRequested) {
        val owners = if (exitRequested) workspace.sessions else closeTarget?.let { (project, session) -> session?.let(::listOf) ?: project.sessions.toList() }.orEmpty()
        val reserved = !exitRequested && (workspace.preparing || owners.any { it.serial in workspace.executionSerials })
        AlertDialog(onDismissRequest = { if (!closing) { closeTarget = null; exitRequested = false } },
            title = { Text(if (exitRequested) "Close FlowScope?" else "Close workspace?") },
            text = { Text(when { reserved -> "This device belongs to the running assignment plan. Stop that queue and wait for execution to finish before closing it."
                closing -> "Waiting for work, cleanup and reports to finish…"
                else -> "Active work will stop and cleanup will finish before closing. Save edited tests, or discard their unsaved changes." }) },
            confirmButton = { TextButton(onClick = { closeOwners(true) }, enabled = !closing && !reserved) { Text("Save and close") } },
            dismissButton = { Row {
                TextButton(onClick = { closeOwners(false) }, enabled = !closing && !reserved) { Text("Discard and close") }
                TextButton(onClick = { closeTarget = null; exitRequested = false }, enabled = !closing) { Text("Cancel") }
            } })
    }
}
