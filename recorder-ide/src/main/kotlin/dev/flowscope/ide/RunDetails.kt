package dev.flowscope.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.flowscope.host.RunReport
import dev.flowscope.host.RunSteps
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Observes the selected directory even if newer runs push it out of the rail's recent-run limit. */
internal data class RunDetails(val report: RunReport?, val htmlReport: Path?, val steps: RunSteps.Snapshot) {
    companion object {
        fun read(directory: Path): RunDetails {
            val report = RunReport.read(directory).orElse(null)
            val html = listOf("suite-report.html", "report.html")
                .map(directory::resolve).firstOrNull(Files::isRegularFile)
            return RunDetails(report, html, RunSteps.read(directory, report))
        }
    }
}

/** Keep observing through completion: report.json can arrive just before its HTML companion. */
internal fun watchRunDetails(directory: Path, pollMillis: Long = 1_000) = flow {
    while (true) {
        emit(RunDetails.read(directory))
        delay(pollMillis)
    }
}.flowOn(Dispatchers.IO)

@Composable
internal fun RunDetailContent(directory: Path) {
    var details by remember(directory) { mutableStateOf<RunDetails?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(directory) {
        watchRunDetails(directory).collect { details = it }
    }
    val report = details?.report
    if (report == null) {
        DrawerField("RUN", directory.fileName.toString())
        Text(
            if (details == null) "Loading run details…" else "Run details are unavailable. The run may have been removed or its report is still being written.",
            color = TextLow, fontSize = 12.sp, lineHeight = 18.sp,
        )
    } else {
        DrawerField(if (report.isSuite) "SUITE" else "TEST", report.name())
        DrawerField("STATUS", when {
            report.isRunning -> "Running"
            report.isInterrupted -> "Interrupted · no final report"
            report.isCancelled -> "Cancelled"
            report.isPassed -> "Passed"
            else -> "Failed"
        })
        report.activity()?.let { DrawerField("CURRENT ACTIVITY", it) }
        if (report.isRunning && report.activity() == null) {
            DrawerField("CURRENT ACTIVITY", "Waiting for the next progress update…")
        }
        val unit = if (report.isSuite) "tests" else "steps"
        DrawerField("PROGRESS", "${report.passed()} of ${report.total()} $unit passed")
        details?.steps?.let { snapshot ->
            RunStepList(snapshot)
            Spacer(Modifier.height(12.dp))
        }
        report.startedAt()?.let { DrawerField("STARTED", it) }
        if (report.isInterrupted) {
            Text("The runner stopped before writing its final report.", color = TextLow, fontSize = 12.sp)
        }
    }
    DrawerField("RUN FOLDER", displayPath(directory, null))
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            details?.htmlReport?.let { file ->
                scope.launch(Dispatchers.IO) { openWithDefaultApplication(file) }
            }
        },
        enabled = details?.htmlReport != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Open HTML report", fontSize = 12.sp) }
    if (report?.isRunning == true && details?.htmlReport == null) {
        Text("The HTML report will be available when the run finishes.", color = TextLow, fontSize = 11.sp)
    }
    OutlinedButton(
        onClick = { scope.launch(Dispatchers.IO) { revealInFileManager(directory) } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Reveal run folder", fontSize = 12.sp) }
}

/** The plan gives future steps their names; outcomes only change each row's status. */
@Composable
private fun RunStepList(snapshot: RunSteps.Snapshot) {
    val groups = snapshot.groups()
    if (groups.isEmpty()) {
        Text("Waiting for step details…", color = TextLow, fontSize = 12.sp)
        return
    }
    Text("STEPS", color = TextMid, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
    val listState = rememberLazyListState()
    var offset = 0
    var currentIndex: Int? = null
    var currentKey: String? = null
    groups.forEach { group ->
        offset++ // Group heading.
        group.steps().forEachIndexed { index, step ->
            if (step.status() == "running") {
                currentIndex = offset + index
                currentKey = "${group.id()}:${step.number()}"
            }
        }
        offset += group.steps().size
    }
    LaunchedEffect(currentKey) {
        currentIndex?.let { index ->
            if (!listState.isScrollInProgress && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index.coerceAtLeast(0))
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 440.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groups.forEach { group ->
            item(key = "${group.id()}:header") {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(group.name(), color = TextHi, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    if (group.reference().isNotBlank()) {
                        Text(group.reference(), color = TextLow, fontSize = 10.sp)
                    }
                }
            }
            items(group.steps(), key = { "${group.id()}:${it.number()}" }) { step ->
                val current = step.status() == "running"
                val color = when (step.status()) {
                    "passed" -> SignalGreen
                    "failed" -> SignalRed
                    "running", "interrupted" -> SignalAmber
                    else -> TextLow
                }
                Column(
                    Modifier.fillMaxWidth().background(if (current) Ink250 else Color.Transparent)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("#${step.number()}", color = TextLow, fontSize = 10.sp)
                        Text(when (step.status()) {
                            "running" -> "CURRENT"
                            "not-run" -> "NOT RUN"
                            else -> step.status().uppercase()
                        }, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(step.action().ifBlank { "Step details unavailable" }, color = TextHi, fontSize = 12.sp)
                    if (step.phase().isNotBlank() || step.line() > 0) {
                        Text(listOfNotNull(step.phase().takeIf { it.isNotBlank() }?.uppercase(),
                            step.line().takeIf { it > 0 }?.let { "line $it" }).joinToString(" · "),
                            color = TextLow, fontSize = 10.sp)
                    }
                }
            }
        }
    }
    if (!snapshot.planned()) {
        Text("This run has no saved step plan; only recorded step details are available.",
            color = TextLow, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
