package dev.flowscope.ide

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.flowscope.host.ProjectSecrets
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Raw values enter Compose state only after an explicit Reveal; hiding clears the editor buffer. */
@Composable
internal fun SecretsEditor(file: Path?, projectRoot: Path?) {
    key(file, projectRoot) {
        var names by remember { mutableStateOf<List<String>>(emptyList()) }
        var json by remember { mutableStateOf<String?>(null) }
        var revision by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(file) {
            if (file != null) runCatching { withContext(Dispatchers.IO) { ProjectSecrets.names(file) } }
                .onSuccess { names = it }
                .onFailure { message = it.message ?: "Cannot read secrets file" }
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            if (names.isNotEmpty()) Text(names.joinToString("\n") { "$it = ••••••••" },
                color = TextMid, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            if (json != null) {
                Text("JSON values are visible below. Saving replaces the file's entries; comments are not retained.",
                    color = TextMid, fontSize = 11.sp)
                OutlinedTextField(
                    value = json!!, onValueChange = { json = it }, enabled = !busy,
                    label = { Text("Secrets JSON") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 360.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = file != null && !busy, onClick = {
                    if (json != null) { json = null; revision = null; message = null }
                    else if (file != null) scope.launch {
                        busy = true
                        runCatching { withContext(Dispatchers.IO) { ProjectSecrets.read(file) } }
                            .onSuccess { json = it.json(); revision = it.revision(); message = null }
                            .onFailure { message = it.message ?: "Cannot read secrets file" }
                        busy = false
                    }
                }) { Text(if (json == null) "Reveal and edit JSON" else "Hide and discard edits") }
                if (json != null) OutlinedButton(enabled = !busy, onClick = {
                    val content = json ?: return@OutlinedButton
                    val original = revision ?: return@OutlinedButton
                    if (file != null) scope.launch {
                        busy = true
                        runCatching { withContext(Dispatchers.IO) {
                            ProjectSecrets.save(file, content, original, projectRoot)
                            ProjectSecrets.names(file)
                        } }.onSuccess {
                            names = it; json = null; revision = null; message = "Secrets saved"
                        }.onFailure { message = it.message ?: "Could not save secrets" }
                        busy = false
                    }
                }) { Text("Save secrets") }
            }
            message?.let { Text(it, color = TextMid, fontSize = 11.sp) }
        }
    }
}
