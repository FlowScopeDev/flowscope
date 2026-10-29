package dev.flowscope.ide

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun InspectionSourceBar(run: Boolean, runName: String, hasRun: Boolean, onSession: () -> Unit, onRun: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        TextButton(onClick = onSession, enabled = run) { Text(if (run) "Session" else "✓ Session") }
        TextButton(onClick = onRun, enabled = hasRun && !run) { Text(if (run) "✓ Selected run" else "Selected run") }
        Text(if (run) runName else "Recording and device events", color = TextMid, fontSize = 11.sp,
            maxLines = 1, modifier = Modifier.weight(1f).padding(12.dp))
    }
}
