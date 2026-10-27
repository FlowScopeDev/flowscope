package dev.flowscope.ide

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
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Observes the selected directory even if newer runs push it out of the rail's recent-run limit. */
internal data class RunDetails(val report: RunReport?, val htmlReport: Path?) {
    companion object {
        fun read(directory: Path): RunDetails {
            val report = RunReport.read(directory).orElse(null)
            val html = listOf("suite-report.html", "report.html")
                .map(directory::resolve).firstOrNull(Files::isRegularFile)
            return RunDetails(report, html)
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
            report.isPassed -> "Passed"
            else -> "Failed"
        })
        report.activity()?.let { DrawerField("CURRENT ACTIVITY", it) }
        if (report.isRunning && report.activity() == null) {
            DrawerField("CURRENT ACTIVITY", "Waiting for the next progress update…")
        }
        val unit = if (report.isSuite) "tests" else "steps"
        DrawerField("PROGRESS", "${report.passed()} of ${report.total()} $unit passed")
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
