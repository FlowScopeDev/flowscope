package dev.flowscope.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import dev.flowscope.host.ExecutionPlanRunner
import java.nio.file.Path

@Composable
internal fun AssignmentSurface(
    workspace: DesktopWorkspace,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onStopQueue: (DesktopSession) -> Unit,
    onReport: () -> Unit,
    onInspect: (ExecutionPlanRunner.JobProgress) -> Unit,
) {
    var bindingMenu by remember { mutableStateOf<String?>(null) }
    val selection = remember { mutableStateMapOf<String, Set<Path>>() }
    Surface(Modifier.fillMaxSize(), color = AppBackground, contentColor = TextHi) {
    Column(Modifier.fillMaxSize().background(AppBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Test assignments", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onLoad, enabled = !workspace.locked) { Text("Load plan…") }
            OutlinedButton(onClick = onSave, enabled = !workspace.locked) { Text("Save plan…") }
            Button(onClick = onRun, enabled = !workspace.locked) { Text("Run assignments") }
            OutlinedButton(onClick = onStop, enabled = workspace.running && !workspace.stopping) { Text("Stop all") }
            OutlinedButton(onClick = onReport, enabled = workspace.report != null) { Text("Open report") }
        }
        Text("Select tests or complete suites, then add them to a device queue. Queues run in parallel; each device runs its jobs in order.", color = TextMid)
        if (!workspace.running && workspace.progress.isNotEmpty()) Text("Results are from the last execution. Files may have changed since that run.", color = TextMid)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(0.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                workspace.projects.forEach { project ->
                    item(key = "title-${project.id}") { Text(project.label, style = MaterialTheme.typography.titleMedium) }
                    val files = (project.sessions.firstOrNull { it.project.isOpen }?.project?.let { it.tests + it.suites }).orEmpty()
                    if (files.isEmpty()) item { Text("Open a project with saved tests to assign work.", color = TextMid) }
                    items(files, key = { "${project.id}:${it.path}" }) { file ->
                        Row {
                            Checkbox(modifier = Modifier.semantics { contentDescription = "Select ${project.label}: ${file.label}" }, checked = file.path in selection[project.id].orEmpty(), enabled = !workspace.locked,
                                onCheckedChange = { checked ->
                                    val selected = selection[project.id].orEmpty()
                                    selection[project.id] = if (checked) selected.plusElement(file.path) else selected.minusElement(file.path)
                                })
                            Text(file.label + if (file.path.toString().endsWith(".suite")) " (suite)" else "", modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(0.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                workspace.projects.forEach { project ->
                    project.sessions.forEach { session ->
                        item(key = session.id) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${project.label} / ${session.label}", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box {
                                        TextButton(onClick = { bindingMenu = session.id }, enabled = !workspace.locked && !session.busy) { Text("Choose device…") }
                                        DropdownMenu(expanded = bindingMenu == session.id, onDismissRequest = { bindingMenu = null }) {
                                            val devices = workspace.sessions.flatMap { it.device.devices }.distinctBy { it.serial() }
                                            if (devices.isEmpty()) DropdownMenuItem(text = { Text("No connected devices") }, onClick = {}, enabled = false)
                                            devices.forEach { device -> DropdownMenuItem(text = { Text("${device.displayName()} (${device.serial()})") }, onClick = {
                                                session.requestedSerial = device.serial(); session.device.selected = device; bindingMenu = null; workspace.assignmentsChanged()
                                            }) }
                                        }
                                    }
                                    Button(onClick = {
                                        val selected = selection[project.id].orEmpty()
                                        session.jobs.addAll(selected.sortedBy { it.toString() }); workspace.assignmentsChanged()
                                    }, enabled = !workspace.locked && selection[project.id].orEmpty().isNotEmpty()) { Text("Add selected") }
                                    TextButton(onClick = { session.jobs.clear(); workspace.assignmentsChanged() }, enabled = !workspace.locked && session.jobs.isNotEmpty()) { Text("Clear queue") }
                                    TextButton(onClick = { onStopQueue(session) }, enabled = workspace.running && session.jobs.isNotEmpty()) { Text("Stop queue") }
                                }
                                Row {
                                    Checkbox(modifier = Modifier.semantics { contentDescription = "Continue after failure on ${session.label}" }, checked = session.continueOnFailure, enabled = !workspace.locked, onCheckedChange = { session.continueOnFailure = it; workspace.assignmentsChanged() })
                                    Text("Continue after a failed job", modifier = Modifier.padding(top = 12.dp))
                                }
                                if (session.jobs.isEmpty()) Text("No assigned tests", color = TextMid)
                                session.jobs.forEachIndexed { index, job ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val progress = workspace.progress.firstOrNull { it.role() == session.role && it.ordinal() == index + 1 }
                                        Text("${index + 1}. ${job.fileName} ${progress?.status().orEmpty()}", modifier = Modifier.weight(1f).padding(top = 12.dp))
                                        if (progress != null) TextButton(onClick = { onInspect(progress) }) { Text("Details") }
                                        TextButton(onClick = {
                                            if (index > 0) { val previous = session.jobs[index - 1]; session.jobs[index - 1] = job; session.jobs[index] = previous; workspace.assignmentsChanged() }
                                        }, enabled = !workspace.locked && index > 0) { Text("Up") }
                                        TextButton(onClick = {
                                            if (index < session.jobs.lastIndex) { val next = session.jobs[index + 1]; session.jobs[index + 1] = job; session.jobs[index] = next; workspace.assignmentsChanged() }
                                        }, enabled = !workspace.locked && index < session.jobs.lastIndex) { Text("Down") }
                                        TextButton(onClick = { session.jobs.removeAt(index); workspace.assignmentsChanged() }, enabled = !workspace.locked) { Text("Remove") }
                                    }
                                }
                                HorizontalDivider(color = Border)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
