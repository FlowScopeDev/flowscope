package dev.flowscope.ide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.file.Path
import java.nio.file.Files

@Composable
internal fun SettingsSurface(
    showDisplay: Boolean,
    onShowDisplayChanged: (Boolean) -> Unit,
    mode: ThemeMode,
    onModeChanged: (ThemeMode) -> Unit,
    secretsFile: Path?,
    onChooseSecretsFile: () -> Unit,
    onClearSecretsFile: () -> Unit,
    about: AboutPanel,
    commands: List<Command> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Ink100)) {
        Column(
            Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 34.dp),
        ) {
            Text("Settings", color = TextHi, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Appearance, secrets, device display, keyboard shortcuts and version for this " +
                    "computer.",
                color = TextMid,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(34.dp))
            Text(
                "APPEARANCE",
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Ink150)
                    .border(1.dp, Ink300, RoundedCornerShape(8.dp)),
            ) {
                ThemeMode.entries.forEachIndexed { index, candidate ->
                    ThemeModeRow(
                        candidate = candidate,
                        selected = candidate == mode,
                        onClick = { onModeChanged(candidate) },
                    )
                    if (index != ThemeMode.entries.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink300))
                    }
                }
            }

            Spacer(Modifier.height(34.dp))
            Text(
                "DEVICE",
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Ink150)
                    .border(1.dp, Ink300, RoundedCornerShape(8.dp)),
            ) {
                DisplayToggleRow(showDisplay, onShowDisplayChanged)
            }

            Spacer(Modifier.height(34.dp))
            Text(
                "SECRETS",
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Ink150)
                    .border(1.dp, Ink300, RoundedCornerShape(8.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Local secrets file", color = TextHi, fontSize = 13.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        secretsFile?.fileName?.toString() ?: "Not configured",
                        color = TextMid,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            secretsFile == null -> "Choose an owner-only dotenv file for local runs."
                            Files.isRegularFile(secretsFile) && Files.isReadable(secretsFile) -> "Ready for local runs"
                            else -> "File unavailable — choose a replacement"
                        },
                        color = if (secretsFile != null &&
                            !(Files.isRegularFile(secretsFile) && Files.isReadable(secretsFile))
                        ) SignalRed else TextMid,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Values stay in memory and are never shown or written to reports.",
                        color = TextMid,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onChooseSecretsFile) {
                    Text(if (secretsFile == null) "Choose" else "Replace", fontSize = 12.sp)
                }
                if (secretsFile != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onClearSecretsFile) { Text("Clear", fontSize = 12.sp) }
                }
            }

            Spacer(Modifier.height(34.dp))
            Text(
                "ABOUT",
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Ink150)
                    .border(1.dp, Ink300, RoundedCornerShape(8.dp)),
            ) {
                AboutRow(about)
            }

            // design.md §6.1 — the same command table drives the keymap and the palette, so this
            // list cannot drift out of date the way a hand-written cheat sheet would.
            val shortcuts = commands.filter { it.shortcut.isNotBlank() }
            if (shortcuts.isNotEmpty()) {
                Spacer(Modifier.height(34.dp))
                Text(
                    "KEYBOARD",
                    color = TextMid,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Press \u2318K for the command palette, which also runs the actions that have no " +
                        "shortcut.",
                    color = TextMid,
                    fontSize = 11.5.sp,
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Ink150)
                        .border(1.dp, Ink300, RoundedCornerShape(8.dp)),
                ) {
                    shortcuts.forEachIndexed { index, command ->
                        ShortcutRow(command)
                        if (index != shortcuts.lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink300))
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * Version, and an explicitly requested update check. Nothing here contacts the network until the
 * button is pressed: FlowScope has no telemetry and no background check to opt out of.
 */
@Composable
private fun AboutRow(about: AboutPanel) {
    val message = describeUpdate(about.status)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("FlowScope", color = TextHi, fontSize = 13.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                about.version,
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                message.text,
                color = when (message.tone) {
                    UpdateTone.Good -> SignalGreen
                    UpdateTone.Alert -> SignalAmber
                    UpdateTone.Neutral -> TextMid
                },
                fontSize = 11.sp,
            )
            message.releaseUrl?.let { url ->
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { about.onOpenRelease(url) }) {
                    Text("Open release notes", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            onClick = about.onCheck,
            enabled = about.status != UpdateStatus.Checking,
        ) {
            Text("Check for updates", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ShortcutRow(command: Command) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            command.label,
            color = if (command.enabled) TextHi else TextMid,
            fontSize = 12.5.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            command.shortcut,
            color = TextMid,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Ink250)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ThemeModeRow(candidate: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    val indicator = SignalIce
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) SignalIceDim else Ink150)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
                contentDescription = "${candidate.label} theme"
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Canvas(Modifier.size(16.dp)) {
            drawCircle(indicator, style = Stroke(width = 1.5.dp.toPx()))
            if (selected) drawCircle(indicator, radius = 4.dp.toPx())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                candidate.label,
                color = TextHi,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            Spacer(Modifier.height(3.dp))
            Text(candidate.detail, color = TextMid, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DisplayToggleRow(show: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Ink150)
            .clickable(role = Role.Switch, onClick = { onChange(!show) })
            .semantics {
                role = Role.Switch
                selected = show
                contentDescription = "Show device display"
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Show device display", color = TextHi, fontSize = 13.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                "Turn off to stop the live stream and screenshots and save CPU. Recording, the " +
                    "Tree and test runs keep working through ADB.",
                color = TextMid,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            if (show) "ON" else "OFF",
            color = if (show) SignalIce else TextMid,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Ink250)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
