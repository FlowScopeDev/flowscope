package dev.flowscope.ide

import dev.flowscope.host.ProjectManifest
import dev.flowscope.host.RunReport
import java.nio.file.Path
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path as GraphicsPath
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.flowscope.host.CaptureMode
import dev.flowscope.host.Device
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// design.md §4.1 / §5.1 — the session bar is the top of the window. One `Ink150` surface, no word
// mark, no bordered form controls, traffic lights inset on macOS.
@Composable
internal fun SessionBar(
    devices: List<Device>,
    selected: Device?,
    onDeviceSelected: (Device) -> Unit,
    onRefresh: () -> Unit,
    avds: List<AvdEntry>,
    emulatorAvailable: Boolean,
    onStartAvd: (name: String, headless: Boolean) -> Unit,
    onStopAvd: (serial: String) -> Unit,
    packageName: String,
    onPackageNameChanged: (String) -> Unit,
    installedPackages: List<String>,
    favourites: Set<String>,
    onToggleFavourite: (String) -> Unit,
    captureMode: CaptureMode,
    onCaptureModeChanged: (CaptureMode) -> Unit,
    recording: Boolean,
    recordingSeconds: Long,
    busy: Boolean,
    health: Map<Subsystem, HealthEntry>,
    packageInstalled: Boolean,
    checkpointCount: Int,
    settingsSelected: Boolean,
    project: ProjectChrome,
    onRecord: () -> Unit,
    onCheckpoint: () -> Unit,
    onSettings: () -> Unit,
) {
    var deviceMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var healthMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(Ink150)) {
      // Drag strip; the traffic lights float here on macOS. Zero height elsewhere.
      Spacer(Modifier.fillMaxWidth().height(TitleStripHeight))
      Row(
        Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box {
            HealthDots(health) { healthMenu = true }
            DropdownMenu(expanded = healthMenu, onDismissRequest = { healthMenu = false }) {
                Subsystem.entries.forEach { subsystem ->
                    val entry = health[subsystem] ?: return@forEach
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(entry.level.color()))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        subsystem.label,
                                        color = TextMid,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        letterSpacing = 1.2.sp,
                                    )
                                    Text(
                                        entry.detail,
                                        color = TextHi,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 360.dp),
                                    )
                                    entry.actionLabel?.let { label ->
                                        Text(label, color = SignalIce, fontSize = 11.sp)
                                    }
                                }
                            }
                        },
                        onClick = {
                            healthMenu = false
                            entry.action?.invoke()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(1.dp).height(20.dp).background(Ink300))
        Spacer(Modifier.width(14.dp))

        // Project — the outermost context, so it sits left of the device.
        ProjectChip(project)

        Spacer(Modifier.width(10.dp))

        // Device — a chip, not a pill button with a typed-in caret.
        Box {
            ChromeChip(
                label = selected?.let { "${it.displayName()} · ${shortSerial(it.serial())}" } ?: "No device",
                enabled = !recording,
                leading = { Box(Modifier.size(6.dp).clip(CircleShape).background(if (selected != null) SignalGreen else SignalRed)) },
                onClick = { deviceMenu = true },
            )
            DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Rescan devices", color = TextHi, fontSize = 12.sp) },
                    enabled = !busy,
                    onClick = { onRefresh(); deviceMenu = false },
                )
                HorizontalDivider(color = Ink300)
                if (devices.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (avds.isEmpty()) "No devices found" else "No devices. Start an emulator below",
                                color = TextMid,
                                fontSize = 12.sp,
                            )
                        },
                        onClick = { deviceMenu = false },
                    )
                } else {
                    devices.forEach { device ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(device.displayName(), color = TextHi, fontSize = 12.sp)
                                    Text(
                                        device.serial(),
                                        color = TextMid,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    )
                                }
                            },
                            onClick = { onDeviceSelected(device); deviceMenu = false },
                        )
                    }
                }
                if (!emulatorAvailable) {
                    HorizontalDivider(color = Ink300)
                    DropdownMenuItem(
                        text = {
                            Text(
                                "No emulator found. Install the SDK emulator or set ANDROID_HOME",
                                color = TextMid,
                                fontSize = 11.sp,
                                modifier = Modifier.widthIn(max = 300.dp),
                            )
                        },
                        enabled = false,
                        onClick = {},
                    )
                } else if (avds.isNotEmpty()) {
                    HorizontalDivider(color = Ink300)
                    Text(
                        "EMULATORS",
                        color = TextMid,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    avds.forEach { avd -> AvdRow(avd, onStartAvd, onStopAvd) }
                }
            }
        }

        Spacer(Modifier.width(10.dp))

        // Application ID — a search over what is actually installed on the selected device.
        var packageFocused by remember { mutableStateOf(false) }
        var fieldSize by remember { mutableStateOf(IntSize.Zero) }
        val packageInteraction = remember { MutableInteractionSource() }
        val packageHovered by packageInteraction.collectIsHoveredAsState()
        val density = LocalDensity.current

        val suggestions = remember(packageName, installedPackages, favourites) {
            rankPackages(installedPackages, packageName, favourites)
        }
        // Focused with an empty query lists everything installed, scrollable.
        val showSuggestions = packageFocused && !recording && suggestions.isNotEmpty() &&
            suggestions.singleOrNull() != packageName.trim()

        Box(Modifier.weight(1f).widthIn(min = 160.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    .background(
                        when {
                            packageFocused -> Ink200
                            packageHovered -> Ink200.copy(alpha = .6f)
                            else -> Color.Transparent
                        },
                    )
                    .then(
                        if (packageFocused) Modifier.border(1.dp, Ink400, RoundedCornerShape(4.dp))
                        else Modifier,
                    )
                    .hoverable(packageInteraction)
                    .onSizeChanged { fieldSize = it }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchIcon(if (packageFocused) TextMid else TextLow)
                Spacer(Modifier.width(7.dp))
                BasicTextField(
                    value = packageName,
                    onValueChange = onPackageNameChanged,
                    enabled = !recording,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = when {
                            !packageInstalled -> SignalRed
                            recording -> TextMid
                            else -> TextHi
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                    ),
                    cursorBrush = SolidColor(SignalIce),
                    // The placeholder goes inside the field's own text slot, so the caret and the
                    // hint share one baseline instead of being two independently positioned nodes.
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (packageName.isEmpty()) {
                                Text(
                                    if (installedPackages.isEmpty()) "Application ID"
                                    else "Search installed packages",
                                    color = TextLow,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f)
                        .onFocusChanged { packageFocused = it.isFocused },
                )
                if (!packageInstalled) {
                    Text(
                        "NOT INSTALLED",
                        color = SignalRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = .8.sp,
                    )
                } else if (packageName.isNotEmpty() && !recording) {
                    Text(
                        "${suggestions.size}",
                        color = TextLow,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }

            // design.md §5.9 — floating layer. Non-focusable so typing is never interrupted, and
            // lazy because a real device reports several hundred packages.
            if (showSuggestions) {
                Popup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(0, with(density) { 4.dp.roundToPx() }),
                    onDismissRequest = { packageFocused = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    Box(
                        Modifier
                            .width(with(density) { fieldSize.width.toDp() }.coerceAtLeast(240.dp))
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Ink150)
                            .border(1.dp, Ink400, RoundedCornerShape(8.dp)),
                    ) {
                        LazyColumn(Modifier.padding(vertical = 4.dp)) {
                            items(suggestions, key = { it }) { candidate ->
                                val favourite = candidate in favourites
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            onPackageNameChanged(candidate)
                                            packageFocused = false
                                        }
                                        .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        candidate,
                                        color = TextHi,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                                    )
                                    Box(
                                        Modifier.size(26.dp).clip(RoundedCornerShape(5.dp))
                                            .clickable { onToggleFavourite(candidate) }
                                            .semantics {
                                                contentDescription = if (favourite) {
                                                    "Remove $candidate from favourites"
                                                } else "Add $candidate to favourites"
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        StarIcon(favourite)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(10.dp))

        Box {
            ChromeChip(
                label = if (captureMode == CaptureMode.ZERO_CODE) "ZERO-CODE" else "INSTRUMENTED",
                enabled = !recording,
                mono = true,
                onClick = { modeMenu = true },
            )
            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Zero-code · any debuggable APK", color = TextHi, fontSize = 12.sp) },
                    onClick = { onCaptureModeChanged(CaptureMode.ZERO_CODE); modeMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("Instrumented · debug agent", color = TextHi, fontSize = 12.sp) },
                    onClick = { onCaptureModeChanged(CaptureMode.INSTRUMENTED); modeMenu = false },
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        // Transport.
        CheckpointButton(enabled = recording, count = checkpointCount, onClick = onCheckpoint)
        Spacer(Modifier.width(8.dp))
        SettingsButton(selected = settingsSelected, onClick = onSettings)
        Spacer(Modifier.width(8.dp))
        RecordButton(
            recording = recording,
            armed = selected != null && packageName.isNotBlank(),
            busy = busy,
            seconds = recordingSeconds,
            onClick = onRecord,
        )
      }
    }
}
@Composable
internal fun HealthDots(health: Map<Subsystem, HealthEntry>, onClick: () -> Unit) {
    HoverTip(
        Subsystem.entries.joinToString("  ") { subsystem ->
            "${subsystem.label} ${(health[subsystem]?.level ?: HealthLevel.NotApplicable).name.lowercase()}"
        },
    ) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Subsystem.entries.forEach { subsystem ->
            val entry = health[subsystem]
            val level = entry?.level ?: HealthLevel.NotApplicable
            Box(
                Modifier.size(6.dp).clip(CircleShape).background(level.color())
                    .semantics {
                        contentDescription = "${subsystem.label}: ${entry?.detail ?: "not applicable"}"
                    },
            )
        }
    }
    }
}

@Composable
internal fun ChromeChip(
    label: String,
    enabled: Boolean,
    mono: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val arrowTint = TextMid
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).background(Ink200)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(7.dp))
        }
        Text(
            label,
            color = if (enabled) TextHi else TextMid,
            fontSize = if (mono) 10.sp else 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            letterSpacing = if (mono) 1.sp else 0.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(7.dp)) {
            val path = GraphicsPath().apply {
                moveTo(0f, size.height * .3f)
                lineTo(size.width / 2f, size.height * .72f)
                lineTo(size.width, size.height * .3f)
            }
            drawPath(path, arrowTint, style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

// design.md §5.1 — the only filled-colour control in the interface.
@Composable
internal fun RecordButton(
    recording: Boolean,
    armed: Boolean,
    busy: Boolean,
    seconds: Long,
    onClick: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val pulse = rememberInfiniteTransition(label = "record")
    val animatedAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = .45f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot",
    )
    // design.md §7 — reduced motion keeps the indicator, drops the animation.
    val dotAlpha = if (reduceMotion) .7f else animatedAlpha

    val container = when {
        recording -> SignalRed
        armed -> SignalAmberDim
        else -> Ink200
    }
    val content = when {
        recording -> Ink050
        armed -> SignalAmber
        else -> TextLow
    }
    val label = when {
        recording -> "STOP · ${formatDuration(seconds)}"
        busy -> "WORKING…"
        else -> "RECORD"
    }

    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(container)
            .then(if (armed && !recording) Modifier.border(1.dp, SignalAmber, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(enabled = recording || (armed && !busy), onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (recording) {
            Box(
                Modifier.size(9.dp).clip(RoundedCornerShape(2.dp))
                    .alpha(dotAlpha).background(content),
            )
        } else {
            Box(Modifier.size(9.dp).clip(CircleShape).background(content))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = content,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = .8.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun CheckpointButton(enabled: Boolean, count: Int, onClick: () -> Unit) {
    var flash by remember { mutableStateOf(false) }
    var previous by remember { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count > previous) {
            flash = true
            delay(400)
            flash = false
        }
        previous = count
    }
    val tint = when {
        flash -> SignalGreen
        enabled -> TextHi
        else -> TextLow
    }
    HoverTip(
        if (enabled) "Capture checkpoint · $count captured" else "Recording required to capture",
        "\u2318\u21e7C",
    ) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Ink200)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Capture checkpoint ($count captured)" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = 1.4.dp.toPx()
            val bodyTop = 4.dp.toPx()
            drawRoundRect(
                color = tint,
                topLeft = Offset(1.dp.toPx(), bodyTop),
                size = Size(14.dp.toPx(), 10.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(width = stroke),
            )
            val housing = GraphicsPath().apply {
                moveTo(4.8.dp.toPx(), bodyTop)
                lineTo(6.2.dp.toPx(), 2.dp.toPx())
                lineTo(9.8.dp.toPx(), 2.dp.toPx())
                lineTo(11.2.dp.toPx(), bodyTop)
            }
            drawPath(housing, tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
            drawCircle(
                color = tint,
                radius = 2.6.dp.toPx(),
                center = Offset(8.dp.toPx(), 9.dp.toPx()),
                style = Stroke(width = stroke),
            )
        }
    }
    }
}

// design.md §4.2 — bracketed [ key value ] pairs, session path last.
@Composable
internal fun TelemetryStrip(
    health: Map<Subsystem, HealthEntry>,
    packageInstalled: Boolean,
    deviceCount: Int,
    recording: Boolean,
    sessionPath: String?,
    projectPath: String? = null,
    onRevealSession: (() -> Unit)? = null,
    onRevealProject: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(Ink050).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TelemetryPair("adb", "$deviceCount device(s)")
        TelemetryPair(
            "stream",
            when (health[Subsystem.Stream]?.level) {
                HealthLevel.Ok -> "scrcpy"
                HealthLevel.Degraded -> "screenshot"
                else -> "down"
            },
        )
        TelemetryPair(
            "session",
            if (recording) "active" else "idle",
        )
        Spacer(Modifier.weight(1f))
        TelemetryPair(
            "project", projectPath ?: "none",
            truncateStart = projectPath != null,
            onClick = onRevealProject.takeIf { projectPath != null },
        )
        sessionPath?.let {
            TelemetryPair("path", it, truncateStart = true, onClick = onRevealSession)
        }
    }
}

@Composable
internal fun TelemetryPair(
    key: String,
    value: String,
    truncateStart: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val actionable = onClick != null

    // Monospace glyph bounds sit slightly below the centre of their line box. Lift the complete
    // pair together so its visible letterforms, not only its font metrics, centre in the strip.
    val row = @Composable {
        Row(
            modifier = Modifier.offset(y = (-1).dp)
                .then(
                    if (actionable) {
                        Modifier.clip(RoundedCornerShape(3.dp))
                            .hoverable(interaction)
                            .clickable(onClick = onClick!!)
                            .background(if (hovered) Ink250 else Color.Transparent)
                            .padding(horizontal = 3.dp)
                    } else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("[", color = Ink400, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Spacer(Modifier.width(5.dp))
            Text(key, color = TextLow, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (truncateStart && value.length > 52) "…${value.takeLast(50)}" else value,
                // A path you can act on should look different from one you cannot.
                color = if (actionable && hovered) TextHi else TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(5.dp))
            Text("]", color = Ink400, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }

    if (actionable) HoverTip(RevealLabel) { row() } else row()
}

/** Named once so every reveal affordance says the same thing on the same platform. */
internal val RevealLabel: String get() = if (IsMac) "Reveal in Finder" else "Show in file manager"


// design.md §4.3 — layout budget. The device column is sized first, then the work surface claims
// its minimum, and the drawer is the first thing to go when space runs out.
internal val DrawerWidth = 320.dp
internal val CollapsedColumnWidth = 64.dp
internal val WorkSurfaceMin = 520.dp

internal data class ShellMetrics(
    val deviceWidth: Dp,
    val deviceCollapsed: Boolean,
    val drawerVisible: Boolean,
)

internal fun shellMetrics(width: Dp, height: Dp, aspect: Float, drawerRequested: Boolean): ShellMetrics {
    var drawer = drawerRequested && width >= 1200.dp
    // Below 1040dp the column collapses; below 1360dp it and the drawer never both expand.
    var collapsed = width < 1040.dp || (drawer && width < 1360.dp)
    var deviceWidth = if (collapsed) CollapsedColumnWidth else deviceColumnWidth(height, aspect)

    fun remaining() = width - deviceWidth - (if (drawer) DrawerWidth else 0.dp) - 2.dp

    if (!collapsed && remaining() < WorkSurfaceMin) {
        // The column gives up width before the work surface does.
        deviceWidth = (deviceWidth - (WorkSurfaceMin - remaining())).coerceAtLeast(280.dp)
        if (remaining() < WorkSurfaceMin) {
            collapsed = true
            deviceWidth = CollapsedColumnWidth
        }
    }
    if (drawer && remaining() < WorkSurfaceMin) drawer = false
    return ShellMetrics(deviceWidth, collapsed, drawer)
}

// design.md §4.2 — tabs live on the work surface itself, not in a rail.
@Composable
internal fun SurfaceTabs(
    active: WorkSurface,
    onSelect: (WorkSurface) -> Unit,
    stepCount: Int,
    evidenceCount: Int,
    eventCount: Int,
    drawerOpen: Boolean,
    onToggleDrawer: () -> Unit,
) {
    val activeRule = TextHi
    Row(
        Modifier.fillMaxWidth().height(38.dp).background(Ink100)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkSurface.entries.filterNot { it == WorkSurface.Settings }.forEach { surface ->
            val selected = surface == active
            val badge = when (surface) {
                WorkSurface.Timeline -> eventCount.takeIf { it > 0 }
                WorkSurface.Flow -> stepCount.takeIf { it > 0 }
                WorkSurface.Evidence -> evidenceCount.takeIf { it > 0 }
                else -> null
            }
            Box(
                Modifier.fillMaxHeight()
                    .clickable { onSelect(surface) }
                    // Drawn from the node's measured size, so the tab stays wrap-content. A
                    // fillMaxWidth() underline here makes the first tab swallow the whole row.
                    .drawBehind {
                        if (selected) {
                            val y = size.height - 1.dp.toPx()
                            drawLine(
                                activeRule,
                                Offset(0f, y),
                                Offset(size.width, y),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        surface.label,
                        color = if (selected) TextHi else TextMid,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    )
                    badge?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            it.toString(),
                            color = TextLow,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (active != WorkSurface.Settings) {
            Text(
                if (drawerOpen) "HIDE DETAIL" else "SHOW DETAIL",
                color = TextMid,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggleDrawer)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

/** One installed AVD: its state, and Start / Start headless / Stop as 32×32 icon controls. */
@Composable
private fun AvdRow(
    avd: AvdEntry,
    onStart: (name: String, headless: Boolean) -> Unit,
    onStop: (serial: String) -> Unit,
) {
    Row(
        Modifier.widthIn(min = 260.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(avd.name, color = TextHi, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (val status = avd.status) {
                    AvdStatus.Stopped -> "stopped"
                    AvdStatus.Booting -> "booting…"
                    is AvdStatus.Running -> "running · ${status.serial}"
                },
                color = if (avd.status == AvdStatus.Booting) SignalIce else TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        when (val status = avd.status) {
            AvdStatus.Stopped -> {
                AvdAction("▶", "Start emulator ${avd.name}", true) { onStart(avd.name, false) }
                AvdAction("▷", "Start emulator ${avd.name} headless", true) { onStart(avd.name, true) }
            }
            AvdStatus.Booting -> AvdAction("▶", "Start emulator ${avd.name}", false) {}
            is AvdStatus.Running -> AvdAction(
                "■",
                if (avd.owned) "Stop emulator ${avd.name}" else "Stop emulator ${avd.name} (not started by FlowScope)",
                avd.owned,
            ) { onStop(status.serial) }
        }
    }
}

@Composable
private fun AvdAction(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    HoverTip(label) {
        Box(
            Modifier.size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .semantics {
                    contentDescription = label
                    role = Role.Button
                }
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = if (enabled) TextHi else Ink400, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingsButton(selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) TextHi else TextMid
    val iconCutout = if (selected) Ink250 else Ink100
    HoverTip(if (selected) "Close Settings" else "Settings") {
    Box(
        Modifier.size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Ink250 else Color.Transparent)
            .border(1.dp, if (selected) Ink400 else Ink300, RoundedCornerShape(6.dp))
            .semantics {
                contentDescription = "Open Settings"
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val rootRadius = 5.2.dp.toPx()
            val toothRadius = 7.4.dp.toPx()
            val gear = GraphicsPath()
            repeat(32) { point ->
                // Four vertices per tooth create a continuous, conventional gear silhouette
                // instead of the detached radial strokes that made the old icon resemble a sun.
                val radius = if (point % 4 == 1 || point % 4 == 2) toothRadius else rootRadius
                val angle = -PI.toFloat() / 2f + point * (2f * PI.toFloat() / 32f)
                val x = center.x + cos(angle) * radius
                val y = center.y + sin(angle) * radius
                if (point == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
            }
            gear.close()
            drawPath(gear, tint)
            drawCircle(iconCutout, radius = 2.4.dp.toPx(), center = center)
        }
    }
    }
}

// design.md §4.2 — one drawer, polymorphic content.
@Composable
internal fun ContextDrawer(
    selection: Selection,
    onClear: () -> Unit,
    onOpenFolder: () -> Unit,
    onAddStep: (String) -> Unit,
    manifest: ProjectManifest? = null,
    installedPackages: List<String> = emptyList(),
    suites: List<String> = emptyList(),
    onManifestChanged: (ProjectManifest) -> Unit = {},
    onOpenManifest: () -> Unit = {},
    onRename: (String) -> Unit = {},
) {
    Column(Modifier.width(DrawerWidth).fillMaxHeight().background(Ink150).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DETAIL",
                color = TextMid,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.weight(1f))
            if (selection !is Selection.None) {
                Text(
                    "CLEAR",
                    color = TextLow,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClear)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (selection) {
                is Selection.None -> Text(
                    "Select an event, step, or checkpoint to inspect it here.",
                    color = TextLow,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                is Selection.Event -> {
                    val event = selection.event
                    DrawerField("KIND", event.title)
                    DrawerField("TIME", event.time)
                    DrawerField("CHANNEL", event.channel.label)
                    if (event.duration.isNotEmpty()) DrawerField("DURATION", event.duration)
                    // design.md §5.5 — the full payload, pretty-printed, not a truncated line.
                    DrawerBody(event.payload?.let(::prettyJson) ?: event.detail)
                    event.suggestedStep?.let { step ->
                        Spacer(Modifier.height(12.dp))
                        SelectorChip(selectorOfStep(step) ?: step, stabilityOfStep(step))
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onAddStep(step) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add as step", fontSize = 12.sp) }
                        Spacer(Modifier.height(6.dp))
                        CopyButton("Copy selector", selectorOfStep(step) ?: step)
                    }
                    event.payload?.let {
                        Spacer(Modifier.height(6.dp))
                        CopyButton("Copy payload", it)
                    }
                }
                is Selection.Step -> {
                    DrawerField("STEP", selection.number.toString())
                    DrawerBody(selection.source)
                    val stability = stabilityOfStep(selection.source)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "SELECTOR",
                        color = TextLow,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(5.dp))
                    SelectorChip(selectorOfStep(selection.source) ?: "coordinate", stability)
                    Spacer(Modifier.height(8.dp))
                    Text(stability.label, color = TextMid, fontSize = 11.sp, lineHeight = 16.sp)
                    selectorOfStep(selection.source)?.let {
                        Spacer(Modifier.height(10.dp))
                        CopyButton("Copy selector", it)
                    }
                }
                is Selection.Checkpoint -> {
                    DrawerField("CHECKPOINT", selection.artifact.sequence.toString())
                    DrawerField("REASON", selection.artifact.reason)
                    DrawerBody(selection.artifact.directory.toString())
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth()) {
                        Text("Open session folder", fontSize = 12.sp)
                    }
                }
                is Selection.WebMetadata -> {
                    DrawerField("WEBVIEW", selection.title)
                    selection.fields.forEach { (label, value) ->
                        DrawerField(label.uppercase(), value)
                    }
                }
                is Selection.ProjectSettings -> ProjectSettingsForm(
                    manifest = manifest,
                    installedPackages = installedPackages,
                    suites = suites,
                    onManifestChanged = onManifestChanged,
                    onOpenManifest = onOpenManifest,
                )
                is Selection.RenameFile -> RenameFileForm(
                    currentName = selection.currentName,
                    onRename = onRename,
                )
            }
        }
    }
}

@Composable
private fun DrawerField(label: String, value: String) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(
            label,
            color = TextLow,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(value, color = TextHi, fontSize = 12.5.sp)
    }
}

@Composable
private fun DrawerBody(text: String) {
    // A body that is itself a path on disk becomes the handle for revealing it — the session path
    // shown for "Recording stopped" is the most common case.
    val target = remember(text) { existingPathIn(text) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val body = @Composable {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .background(if (target != null && hovered) Ink150 else Ink100)
                .then(
                    if (target != null) {
                        Modifier.hoverable(interaction)
                            .clickable { revealInFileManager(target) }
                    } else Modifier,
                )
                .padding(10.dp),
        ) {
            Text(
                text,
                color = if (target != null && hovered) TextHi else TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
            if (target != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    RevealLabel.uppercase(),
                    color = if (hovered) SignalIce else TextLow,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
    }

    if (target != null) HoverTip(RevealLabel) { body() } else body()
}

// design.md §4.2 — collapsed, the column still shows the device and the record state.
@Composable
internal fun CollapsedDeviceStrip(
    liveImage: ImageBitmap?,
    recording: Boolean,
    onExpand: () -> Unit,
) {
    Column(
        Modifier.width(CollapsedColumnWidth).fillMaxHeight().background(Ink150)
            .clickable(onClick = onExpand).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp, 96.dp).clip(RoundedCornerShape(6.dp)).background(Ink000),
            contentAlignment = Alignment.Center,
        ) {
            liveImage?.let {
                Image(
                    painter = BitmapPainter(it),
                    contentDescription = "Live device thumbnail",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(if (recording) SignalRed else Ink400),
        )
    }
}


@Composable
private fun SearchIcon(tint: Color) {
    Canvas(Modifier.size(12.dp)) {
        val stroke = 1.4.dp.toPx()
        val r = size.minDimension * .33f
        val c = Offset(r + stroke, r + stroke)
        drawCircle(tint, radius = r, center = c, style = Stroke(width = stroke))
        drawLine(
            tint,
            Offset(c.x + r * .72f, c.y + r * .72f),
            Offset(size.width - stroke * .5f, size.height - stroke * .5f),
            stroke,
            StrokeCap.Round,
        )
    }
}


// design.md §5.4 — selector plus a stability pip, used on the stage overlay and in the drawer.
@Composable
internal fun SelectorChip(label: String, stability: SelectorStability) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).background(Ink200)
            .border(1.dp, Ink400, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StabilityPip(stability)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = TextHi,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun StabilityPip(stability: SelectorStability) {
    Row(
        Modifier.semantics { contentDescription = "${stability.pips} of 3 — ${stability.label}" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(3) { index ->
            Box(
                Modifier.size(4.dp).clip(CircleShape)
                    .background(if (index < stability.pips) stability.color() else Ink400),
            )
        }
    }
}


/**
 * Grades a `.flow` step's selector the same way the host resolver grades a live tap: a resource id
 * is durable, a semantic description is good, visible text is fragile across localisation, and a
 * bare class or raw coordinate is not durable at all (design.md §5.4).
 */
internal fun stabilityOfStep(source: String): SelectorStability = when {
    Regex("""\bid=""").containsMatchIn(source) -> SelectorStability.High
    Regex("""\bdesc=""").containsMatchIn(source) -> SelectorStability.Medium
    Regex("""\b(text|text-contains)=""").containsMatchIn(source) -> SelectorStability.Low
    else -> SelectorStability.Unresolved
}

/** The selector portion of a step line, or null when it carries none. */
internal fun selectorOfStep(source: String): String? {
    val matches = Regex("""\b(id|text|text-contains|desc|class)=("[^"]*"|\S+)""").findAll(source)
        .map { it.value }.toList()
    return matches.takeIf { it.isNotEmpty() }?.joinToString(" ")
}


/**
 * Minimal pretty-printer for the single-line JSON the agent and host emit. Deliberately does not
 * parse: it only reflows an already-valid object so a payload is readable in the drawer, and falls
 * back to the original text for anything that is not JSON.
 */
internal fun prettyJson(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return raw
    val out = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false
    fun newline() {
        out.append('\n').append("  ".repeat(depth))
    }
    for (char in trimmed) {
        when {
            escaped -> { out.append(char); escaped = false }
            char == '\\' && inString -> { out.append(char); escaped = true }
            char == '"' -> { out.append(char); inString = !inString }
            inString -> out.append(char)
            char == '{' || char == '[' -> { out.append(char); depth++; newline() }
            char == '}' || char == ']' -> { depth--; newline(); out.append(char) }
            char == ',' -> { out.append(char); newline() }
            char == ':' -> out.append(": ")
            char.isWhitespace() -> Unit
            else -> out.append(char)
        }
    }
    return out.toString()
}

// design.md §5.9 / §6.1 — the command palette is a floating layer, dismissed with Esc.
@Composable
internal fun CommandPalette(commands: List<Command>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(commands, query) { filterCommands(commands, query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, 120),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(520.dp).heightIn(max = 420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ink150)
                .border(1.dp, Ink400, RoundedCornerShape(12.dp))
                .onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                            onDismiss(); true
                        }
                        event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                            matches.firstOrNull()?.takeIf { it.enabled }?.let {
                                it.run(); onDismiss()
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TextHi,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(SignalIce),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    "Type a command",
                                    color = TextLow,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            HorizontalDivider(color = Ink300)
            if (matches.isEmpty()) {
                Text(
                    "No matching command",
                    color = TextLow,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(14.dp),
                )
            } else {
                LazyColumn(Modifier.padding(vertical = 4.dp)) {
                    items(matches, key = { it.id }) { command ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = command.enabled) {
                                    command.run(); onDismiss()
                                }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                command.label,
                                color = if (command.enabled) TextHi else TextLow,
                                fontSize = 12.5.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                command.shortcut,
                                color = TextLow,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}


/** Filled when the package is a favourite, outlined otherwise. */
@Composable
private fun StarIcon(filled: Boolean) {
    val tint = if (filled) SignalAmber else Ink400
    Canvas(Modifier.size(12.dp)) {
        val outer = size.minDimension / 2f
        val inner = outer * .45f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val path = GraphicsPath()
        for (point in 0 until 10) {
            val radius = if (point % 2 == 0) outer else inner
            val angle = Math.PI / 2 * -1 + point * Math.PI / 5
            val x = centre.x + (radius * kotlin.math.cos(angle)).toFloat()
            val y = centre.y + (radius * kotlin.math.sin(angle)).toFloat()
            if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (filled) drawPath(path, tint)
        else drawPath(path, tint, style = Stroke(width = 1.2.dp.toPx()))
    }
}


/** Copies a value to the host clipboard and confirms it briefly in place. */
@Composable
internal fun CopyButton(label: String, value: String) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200)
            copied = false
        }
    }
    OutlinedButton(
        onClick = {
            copyToClipboard(value)
            copied = true
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (copied) "Copied" else label,
            fontSize = 12.sp,
            color = if (copied) SignalGreen else TextHi,
        )
    }
}

// ---------------------------------------------------------------------- project

/**
 * The project chip: the outermost context in the session bar, left of the device. Takes one holder
 * rather than a dozen parameters — `SessionBar` is already at its limit.
 */
@Composable
internal fun ProjectChip(chrome: ProjectChrome) {
    var menu by remember { mutableStateOf(false) }
    var pathDraft by remember { mutableStateOf("") }

    Box {
        ChromeChip(
            label = chrome.label,
            enabled = true,
            leading = {
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(if (chrome.isOpen) SignalGreen else Ink400),
                )
            },
            onClick = { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (chrome.recents.isNotEmpty()) {
                chrome.recents.take(RecentProjectCap).forEach { recent ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    recent.root.fileName?.toString() ?: recent.root.toString(),
                                    color = TextHi,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    recent.root.toString(),
                                    color = TextMid,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 320.dp),
                                )
                            }
                        },
                        onClick = { menu = false; chrome.onOpenRecent(recent.root) },
                    )
                }
                HorizontalDivider(color = Ink300)
            }
            DropdownMenuItem(
                text = { Text("Open project…", color = TextHi, fontSize = 12.sp) },
                onClick = { menu = false; chrome.onOpen() },
            )
            DropdownMenuItem(
                text = { Text("New project…", color = TextHi, fontSize = 12.sp) },
                onClick = { menu = false; chrome.onCreate() },
            )
            // Keyboard-only path entry: no dialog, and the only one of the three that is testable.
            DropdownMenuItem(
                text = {
                    OutlinedTextField(
                        value = pathDraft,
                        onValueChange = { pathDraft = it },
                        singleLine = true,
                        label = { Text("Open path…", fontSize = 11.sp) },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.width(300.dp).onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter &&
                                pathDraft.isNotBlank()
                            ) {
                                menu = false
                                chrome.onOpenPath(pathDraft)
                                pathDraft = ""
                                true
                            } else false
                        },
                    )
                },
                onClick = {},
            )
            if (chrome.isOpen) {
                HorizontalDivider(color = Ink300)
                DropdownMenuItem(
                    text = { Text("Project settings", color = TextHi, fontSize = 12.sp) },
                    onClick = { menu = false; chrome.onSettings() },
                )
                DropdownMenuItem(
                    text = { Text("Open flowscope.json", color = TextHi, fontSize = 12.sp) },
                    onClick = { menu = false; chrome.onManifest() },
                )
                DropdownMenuItem(
                    text = { Text("Reload project", color = TextHi, fontSize = 12.sp) },
                    onClick = { menu = false; chrome.onReload() },
                )
                DropdownMenuItem(
                    text = { Text("Reveal project folder", color = TextHi, fontSize = 12.sp) },
                    onClick = { menu = false; chrome.onReveal() },
                )
                DropdownMenuItem(
                    text = { Text("Close project", color = TextMid, fontSize = 12.sp) },
                    onClick = { menu = false; chrome.onClose() },
                )
            }
        }
    }
}

/**
 * The file rail: tests, suites, and recent runs for the open project. Lives inside the Flow
 * surface rather than being its own tab, because you pick a file *in order to* edit it.
 */
@Composable
internal fun FlowFileRail(
    tests: List<ProjectEntry>,
    suites: List<ProjectEntry>,
    runs: List<RunReport>,
    scratch: List<ProjectEntry>,
    projectOpen: Boolean,
    openFile: Path?,
    onOpen: (Path) -> Unit,
    onNewTest: () -> Unit,
    onNewSuite: () -> Unit,
    onRunSuite: (Path) -> Unit,
    onOpenProject: () -> Unit,
    onCreateProject: () -> Unit,
    onOpenRun: (Path) -> Unit,
    onRevealRun: (Path) -> Unit,
) {
    Column(
        Modifier.width(RailWidth).fillMaxHeight().background(Ink150)
            .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
    ) {
        if (!projectOpen) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "No project open",
                    color = TextHi,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Saves go to ~/.flowscope. Open a project to keep tests and suites with the app they test.",
                    color = TextLow,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenProject, modifier = Modifier.fillMaxWidth()) {
                    Text("Open project…", fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onCreateProject, modifier = Modifier.fillMaxWidth()) {
                    Text("New project…", fontSize = 11.sp)
                }
            }
            if (scratch.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                RailSection("SCRATCH", null)
                scratch.forEach { entry ->
                    RailRow(entry.label, entry.path == openFile, null) { onOpen(entry.path) }
                }
            }
        } else {
            RailSection("TESTS", onNewTest)
            if (tests.isEmpty()) RailEmpty("No tests yet")
            tests.forEach { entry ->
                RailRow(entry.label, entry.path == openFile, null) { onOpen(entry.path) }
            }

            Spacer(Modifier.height(14.dp))
            RailSection("SUITES", onNewSuite)
            if (suites.isEmpty()) RailEmpty("No suites yet")
            suites.forEach { entry ->
                RailRow(entry.label, entry.path == openFile, "▶") { onRunSuite(entry.path) }
            }
        }

        if (runs.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            RailSection("RUNS", null)
            runs.forEach { report ->
                RailRunRow(
                    report,
                    onOpen = { onOpenRun(report.directory()) },
                    onReveal = { onRevealRun(report.directory()) },
                )
            }
        }
    }
}

@Composable
private fun RailSection(label: String, onAdd: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = TextLow,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.weight(1f))
        onAdd?.let {
            HoverTip(
                if (label == "SUITES") "New suite" else "New test",
                if (label == "SUITES") "\u2318\u21e7N" else "\u2318N",
            ) {
                Text(
                    "+",
                    color = TextMid,
                    fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = it)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun RailEmpty(text: String) {
    Text(
        text,
        color = TextLow,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
    )
}

@Composable
private fun RailRow(label: String, selected: Boolean, trailing: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Ink250 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) TextHi else TextMid,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Spacer(Modifier.width(6.dp))
            Text(it, color = TextLow, fontSize = 10.sp)
        }
    }
}

@Composable
private fun RailRunRow(report: RunReport, onOpen: () -> Unit, onReveal: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape)
                .background(if (report.isPassed()) SignalGreen else SignalRed),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            report.name(),
            color = TextMid,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "${report.passed()}/${report.total()}",
            color = TextLow,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(5.dp))
        HoverTip("Reveal report folder") {
            Text(
                "…",
                color = TextLow,
                fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable(onClick = onReveal)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/** `flowscope.json` as a form. Every edit writes the whole manifest through one writer. */
@Composable
private fun ProjectSettingsForm(
    manifest: ProjectManifest?,
    installedPackages: List<String>,
    suites: List<String>,
    onManifestChanged: (ProjectManifest) -> Unit,
    onOpenManifest: () -> Unit,
) {
    if (manifest == null) {
        Text("Open a project to edit its settings.", color = TextLow, fontSize = 12.sp)
        return
    }
    val defaults = manifest.defaults()
    var name by remember(manifest) { mutableStateOf(manifest.name()) }
    var packageName by remember(manifest) { mutableStateOf(defaults.packageName().orEmpty()) }
    var timeout by remember(manifest) {
        mutableStateOf(defaults.timeoutMillis()?.toString().orEmpty())
    }
    var serial by remember(manifest) { mutableStateOf(defaults.serial().orEmpty()) }
    var suiteMenu by remember { mutableStateOf(false) }

    fun commit(
        newName: String = name,
        newPackage: String = packageName,
        newMode: CaptureMode? = defaults.mode(),
        newTimeout: String = timeout,
        newSerial: String = serial,
        newSuite: String? = manifest.defaultSuite(),
    ) {
        onManifestChanged(
            ProjectManifest(
                ProjectManifest.SCHEMA_VERSION,
                newName.trim().ifEmpty { manifest.name() },
                manifest.flowsDir(),
                manifest.reportsDir(),
                ProjectManifest.Defaults(
                    newPackage.trim().ifEmpty { null },
                    newMode,
                    newTimeout.trim().toLongOrNull()?.takeIf { it > 0 },
                    newSerial.trim().ifEmpty { null },
                ),
                newSuite?.trim()?.ifEmpty { null },
            ),
        )
    }

    Text(
        "Authoring defaults only — every .flow keeps its own header and runs without this project.",
        color = TextLow,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
    Spacer(Modifier.height(12.dp))

    DrawerTextField("NAME", name, { name = it }) { commit(newName = name) }
    DrawerTextField("DEFAULT PACKAGE", packageName, { packageName = it }) {
        commit(newPackage = packageName)
    }
    if (installedPackages.isNotEmpty() && packageName.isNotBlank()) {
        val matches = rankPackages(installedPackages, packageName, emptySet()).take(3)
        matches.forEach { suggestion ->
            Text(
                suggestion,
                color = SignalIce,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp))
                    .clickable { packageName = suggestion; commit(newPackage = suggestion) }
                    .padding(vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    Text(
        "DEFAULT MODE",
        color = TextLow,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(5.dp))
    Row {
        CaptureMode.entries.forEach { mode ->
            val selected = defaults.mode() == mode
            Text(
                mode.cliName(),
                color = if (selected) TextHi else TextMid,
                fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (selected) Ink300 else Ink200)
                    .clickable { commit(newMode = if (selected) null else mode) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
    }
    Spacer(Modifier.height(12.dp))

    DrawerTextField("DEFAULT TIMEOUT (MS)", timeout, { timeout = it }) {
        commit(newTimeout = timeout)
    }
    DrawerTextField("DEFAULT SERIAL", serial, { serial = it }) { commit(newSerial = serial) }

    Text(
        "DEFAULT SUITE",
        color = TextLow,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(5.dp))
    Box {
        ChromeChip(
            label = manifest.defaultSuite() ?: "none",
            enabled = true,
            mono = true,
            onClick = { suiteMenu = true },
        )
        DropdownMenu(expanded = suiteMenu, onDismissRequest = { suiteMenu = false }) {
            DropdownMenuItem(
                text = { Text("none", color = TextMid, fontSize = 12.sp) },
                onClick = { suiteMenu = false; commit(newSuite = null) },
            )
            suites.forEach { suite ->
                val leaf = suite.substringAfterLast('/')
                DropdownMenuItem(
                    text = { Text(leaf, color = TextHi, fontSize = 12.sp) },
                    onClick = { suiteMenu = false; commit(newSuite = leaf) },
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    OutlinedButton(onClick = onOpenManifest, modifier = Modifier.fillMaxWidth()) {
        Text("Open flowscope.json", fontSize = 12.sp)
    }
}

@Composable
private fun RenameFileForm(currentName: String, onRename: (String) -> Unit) {
    var draft by remember(currentName) { mutableStateOf(currentName) }
    Text("RENAME", color = TextLow, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "A suite that references the old name keeps pointing at it — FlowScope warns rather than editing other checked-in files.",
        color = TextLow,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = { onRename(draft) },
        enabled = draft.isNotBlank() && draft != currentName,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Rename", fontSize = 12.sp) }
}

@Composable
private fun DrawerTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(
            label,
            color = TextLow,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    onCommit()
                    true
                } else false
            },
        )
    }
}

internal val RailWidth = 200.dp

/**
 * Hover label for a control that shows only an icon. The icons already carry `contentDescription`
 * for screen readers; this is the sighted equivalent — without it an icon-only button is a guess
 * until you click it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HoverTip(label: String, shortcut: String? = null, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Row(
                Modifier.clip(RoundedCornerShape(5.dp)).background(Ink300)
                    .border(1.dp, Ink400, RoundedCornerShape(5.dp))
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = TextHi, fontSize = 11.sp)
                if (!shortcut.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        shortcut,
                        color = TextMid,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
        },
        delayMillis = 450,
        content = content,
    )
}
