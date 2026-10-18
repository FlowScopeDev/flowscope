package dev.flowscope.ide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path as GraphicsPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.flowscope.host.Device
import dev.flowscope.host.EmbeddedScrcpyClient

// design.md §5.3.1 — width is an output of the device aspect ratio, never an input.
internal const val FallbackAspect = 9f / 19.5f
internal val DeviceBezel = 7.dp

/** Gap between the bezel and the corner ticks. Lives inside the column's existing padding. */
internal val TickFrame = 7.dp
internal val DeviceColumnPadding = 16.dp
internal val DeviceDockHeight = 44.dp

/** Column chrome above and below the stage: padding, the gap, and the dock. */
internal val DeviceColumnChrome = DeviceColumnPadding * 2 + 12.dp + DeviceDockHeight

internal fun deviceColumnWidth(available: Dp, aspect: Float): Dp {
    val stageHeight = (available - DeviceColumnChrome).coerceAtLeast(160.dp)
    val stageWidth = stageHeight * aspect
    return (stageWidth + DeviceBezel * 2 + DeviceColumnPadding * 2).coerceIn(280.dp, 560.dp)
}

@Composable
internal fun DeviceColumn(
    modifier: Modifier,
    liveImage: ImageBitmap?,
    aspect: Float,
    device: Device?,
    recording: Boolean,
    streaming: Boolean,
    feedback: TapFeedback?,
    interaction: InteractionMode,
    onInteractionChange: (InteractionMode) -> Unit,
    mirrorAvailable: Boolean,
    mirrorRunning: Boolean,
    onDeviceTap: (Offset) -> Unit,
    onDeviceSwipe: (Offset, Offset) -> Unit,
    onDeviceScroll: (String) -> Unit,
    onDeviceKey: (EmbeddedScrcpyClient.DeviceKey) -> Unit,
    onDeviceText: (String) -> Unit,
    onToggleMirror: () -> Unit,
) {
    Column(modifier.background(Ink150).padding(DeviceColumnPadding)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            DeviceStage(
                liveImage, aspect, device, recording, streaming, feedback,
                interaction == InteractionMode.Inspect,
                onDeviceTap, onDeviceSwipe, onDeviceScroll,
            )
        }
        Spacer(Modifier.height(12.dp))
        DeviceDock(
            enabled = device != null,
            streaming = streaming,
            recording = recording,
            interaction = interaction,
            onInteractionChange = onInteractionChange,
            mirrorAvailable = mirrorAvailable,
            mirrorRunning = mirrorRunning,
            onDeviceKey = onDeviceKey,
            onDeviceText = onDeviceText,
            onToggleMirror = onToggleMirror,
        )
    }
}

// design.md §5.3.2 — the stage fills the height it is given and takes its width from the device.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DeviceStage(
    screenshot: ImageBitmap?,
    aspect: Float,
    device: Device?,
    recording: Boolean,
    streaming: Boolean,
    feedback: TapFeedback?,
    showGestureTrail: Boolean,
    onTap: (Offset) -> Unit,
    onSwipe: (Offset, Offset) -> Unit,
    onScroll: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Height-bound is the normal case; the width branch covers tablets, landscape, and the
        // 560dp column clamp.
        val widthIfHeightBound = maxHeight * aspect
        val stageWidth = if (widthIfHeightBound <= maxWidth) widthIfHeightBound else maxWidth
        val stageHeight = if (widthIfHeightBound <= maxWidth) maxHeight else maxWidth / aspect

        // Keep the gesture detectors alive across frames: `screenshot` is a fresh ImageBitmap on
        // every decoded frame, so using it as a pointerInput key restarted the detectors tens of
        // times a second and dropped or duplicated gestures mid-click. These keep the latest
        // values visible to the long-lived gesture coroutine instead.
        val currentFrame by rememberUpdatedState(screenshot)
        val currentTap by rememberUpdatedState(onTap)
        val currentSwipe by rememberUpdatedState(onSwipe)
        val currentScroll by rememberUpdatedState(onScroll)
        val currentShowGestureTrail by rememberUpdatedState(showGestureTrail)

        var containerSize by remember { mutableStateOf(IntSize.Zero) }
        var dragStart by remember { mutableStateOf<Offset?>(null) }
        var dragEnd by remember { mutableStateOf<Offset?>(null) }
        val dragPath = remember { mutableStateListOf<Offset>() }
        var trailFading by remember { mutableStateOf<List<Offset>>(emptyList()) }
        var trailToken by remember { mutableLongStateOf(0L) }

        // design.md §3.4 — ripple 420ms, bounds hold 1200ms then a 200ms fade.
        val reduceMotion = LocalReduceMotion.current
        val reveal = remember { Animatable(0f) }
        LaunchedEffect(feedback?.id, reduceMotion) {
            if (feedback == null) return@LaunchedEffect
            reveal.snapTo(0f)
            if (reduceMotion) {
                // Hold the resolved bounds without the expanding ring, then drop them.
                reveal.snapTo(RippleFraction)
                delay(TapFeedbackMillis.toLong() - 420)
                reveal.snapTo(1f)
            } else {
                reveal.animateTo(1f, tween(TapFeedbackMillis, easing = LinearEasing))
            }
        }
        val trailAlpha = remember { Animatable(0f) }
        LaunchedEffect(showGestureTrail) {
            if (!showGestureTrail) {
                dragPath.clear()
                trailFading = emptyList()
                trailToken += 1
                trailAlpha.snapTo(0f)
            }
        }
        LaunchedEffect(trailToken) {
            if (trailFading.isEmpty()) return@LaunchedEffect
            trailAlpha.snapTo(.85f)
            if (reduceMotion) trailAlpha.snapTo(0f)
            else trailAlpha.animateTo(0f, tween(220, easing = LinearEasing))
            trailFading = emptyList()
        }

        // The rectangle the device image actually occupies inside the bezel. Hit testing and the
        // feedback overlay both derive from this, so a drawn box can never drift from the tap
        // that produced it (design.md §8.7).
        fun displayRect(): Rect? {
            val image = currentFrame ?: return null
            if (containerSize.width == 0 || containerSize.height == 0) return null
            val scale = minOf(
                containerSize.width.toFloat() / image.width,
                containerSize.height.toFloat() / image.height,
            )
            val w = image.width * scale
            val h = image.height * scale
            val left = (containerSize.width - w) / 2f
            val top = (containerSize.height - h) / 2f
            return Rect(left, top, left + w, top + h)
        }

        fun normalized(position: Offset): Offset? {
            val rect = displayRect() ?: return null
            if (position.x !in rect.left..rect.right || position.y !in rect.top..rect.bottom) return null
            return Offset((position.x - rect.left) / rect.width, (position.y - rect.top) / rect.height)
        }

        /** Inverse of [normalized]: a 0..1 point back to a position inside the stage. */
        fun toStage(point: Offset): Offset? {
            val rect = displayRect() ?: return null
            return Offset(rect.left + point.x * rect.width, rect.top + point.y * rect.height)
        }

        Box(
            Modifier.requiredSize(stageWidth + TickFrame * 2, stageHeight + TickFrame * 2),
            contentAlignment = Alignment.Center,
        ) {
            // design.md §5.3.2 — bracket motif, drawn clear of the device screen.
            val tickTint = Ink400
            Canvas(Modifier.fillMaxSize()) {
                val length = 10.dp.toPx()
                val stroke = 1.5.dp.toPx()
                fun tick(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(tickTint, Offset(x, y), Offset(x + dx * length, y), stroke, StrokeCap.Round)
                    drawLine(tickTint, Offset(x, y), Offset(x, y + dy * length), stroke, StrokeCap.Round)
                }
                tick(0f, 0f, 1f, 1f)
                tick(size.width, 0f, -1f, 1f)
                tick(0f, size.height, 1f, -1f)
                tick(size.width, size.height, -1f, -1f)
            }

        Box(
            Modifier.size(stageWidth, stageHeight)
                .clip(RoundedCornerShape(26.dp))
                .background(Ink000)
                .then(
                    if (recording) Modifier.border(2.dp, SignalRed, RoundedCornerShape(26.dp))
                    else Modifier,
                )
                .padding(DeviceBezel)
                .onSizeChanged { containerSize = it }
                .pointerInput(Unit) {
                    detectTapGestures { position -> normalized(position)?.let(currentTap) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragStart = normalized(it)
                            dragEnd = dragStart
                            dragPath.clear()
                            if (currentShowGestureTrail) dragStart?.let(dragPath::add)
                        },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragEnd
                            if (start != null && end != null) currentSwipe(start, end)
                            dragStart = null
                            dragEnd = null
                            trailFading = if (currentShowGestureTrail) dragPath.toList() else emptyList()
                            trailToken += 1
                            dragPath.clear()
                        },
                        onDragCancel = {
                            dragStart = null
                            dragEnd = null
                            trailFading = emptyList()
                            trailToken += 1
                            dragPath.clear()
                        },
                        onDrag = { change, _ ->
                            val point = normalized(change.position)
                            dragEnd = point
                            point?.let {
                                if (currentShowGestureTrail && dragPath.size < 240) dragPath.add(it)
                            }
                        },
                    )
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val deltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (deltaY != 0f) currentScroll(if (deltaY > 0f) "down" else "up")
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                screenshot != null -> Image(
                    painter = BitmapPainter(screenshot),
                    contentDescription = "Live screen from ${device?.displayName() ?: "Android device"}" +
                        if (streaming) " · streaming" else " · screenshot fallback",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)),
                )
                device == null -> EmptyDevice("Connect with USB or\nwireless debugging")
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Reading device screen…", color = TextMid, fontSize = 12.sp)
                }
            }

            // design.md §5.3.2 — tap ripple, resolved node bounds, and the swipe trail.
            val overlayIce = SignalIce
            val overlayAmber = SignalAmber
            Canvas(Modifier.fillMaxSize()) {
                val live = if (showGestureTrail) dragPath.toList() else emptyList()
                val dragging = live.size > 1
                val trail = if (showGestureTrail) {
                    if (dragging) live else trailFading
                } else {
                    emptyList()
                }
                if (trail.size > 1) {
                    val alpha = if (dragging) .85f else trailAlpha.value
                    val tint = overlayIce.copy(alpha = alpha)
                    val points = trail.mapNotNull(::toStage)
                    if (points.size > 1) {
                        for (i in 0 until points.size - 1) {
                            drawLine(tint, points[i], points[i + 1], 2.5.dp.toPx(), StrokeCap.Round)
                        }
                        // Hollow ring marks where the gesture started.
                        drawCircle(
                            tint,
                            radius = 5.dp.toPx(),
                            center = points.first(),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                        // Arrowhead at the tip, so direction is readable at a glance.
                        val tip = points.last()
                        val prev = points[points.lastIndex - 1]
                        val dx = tip.x - prev.x
                        val dy = tip.y - prev.y
                        val length = kotlin.math.hypot(dx, dy)
                        if (length > 0.5f) {
                            val ux = dx / length
                            val uy = dy / length
                            val head = 9.dp.toPx()
                            val spread = 0.5f
                            fun wing(sign: Float) = Offset(
                                tip.x - head * (ux * kotlin.math.cos(spread) - sign * uy * kotlin.math.sin(spread)),
                                tip.y - head * (uy * kotlin.math.cos(spread) + sign * ux * kotlin.math.sin(spread)),
                            )
                            drawLine(tint, tip, wing(1f), 2.5.dp.toPx(), StrokeCap.Round)
                            drawLine(tint, tip, wing(-1f), 2.5.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }

                val fb = feedback ?: return@Canvas
                val progress = reveal.value
                val contact = toStage(fb.point) ?: return@Canvas
                val tint = if (fb.bounds == null) overlayAmber else overlayIce

                // Ripple over the first 420ms.
                val ripple = (progress / RippleFraction).coerceIn(0f, 1f)
                if (ripple < 1f) {
                    drawCircle(
                        tint.copy(alpha = (1f - ripple) * .9f),
                        radius = 36.dp.toPx() * ripple,
                        center = contact,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }

                // Bounds hold, then fade over the last 200ms.
                val holdAlpha = ((1f - progress) / (1f - HoldFraction)).coerceIn(0f, 1f)
                fb.bounds?.let { b ->
                    val tl = toStage(Offset(b.left, b.top)) ?: return@let
                    val br = toStage(Offset(b.right, b.bottom)) ?: return@let
                    drawRect(
                        tint.copy(alpha = .14f * holdAlpha),
                        topLeft = tl,
                        size = Size(br.x - tl.x, br.y - tl.y),
                    )
                    drawRect(
                        tint.copy(alpha = holdAlpha),
                        topLeft = tl,
                        size = Size(br.x - tl.x, br.y - tl.y),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }

            // The selector chip rides just under the resolved node.
            feedback?.let { fb ->
                val holdAlpha = ((1f - reveal.value) / (1f - HoldFraction)).coerceIn(0f, 1f)
                if (holdAlpha > 0f && fb.label.isNotEmpty()) {
                    val anchorPoint = fb.bounds?.let { Offset(it.left, it.bottom) } ?: fb.point
                    val stagePoint = toStage(anchorPoint)
                    if (stagePoint != null) {
                        Box(
                            Modifier.align(Alignment.TopStart)
                                .offset {
                                    IntOffset(
                                        stagePoint.x.toInt().coerceIn(0, maxOf(0, containerSize.width - 180)),
                                        (stagePoint.y + 6.dp.toPx()).toInt()
                                            .coerceIn(0, maxOf(0, containerSize.height - 28)),
                                    )
                                }
                                .alpha(holdAlpha),
                        ) {
                            SelectorChip(fb.label, fb.stability)
                        }
                    }
                }
            }
            }
        }
    }
}

internal const val TapFeedbackMillis = 1400
private const val RippleFraction = 420f / TapFeedbackMillis
private const val HoldFraction = 1200f / TapFeedbackMillis

@Composable
internal fun StageBadge(label: String, tint: Color) {
    Text(
        label,
        color = tint,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Medium,
    )
}

// Compact 44dp device controls.
@Composable
internal fun DeviceDock(
    enabled: Boolean,
    streaming: Boolean,
    recording: Boolean,
    interaction: InteractionMode,
    onInteractionChange: (InteractionMode) -> Unit,
    mirrorAvailable: Boolean,
    mirrorRunning: Boolean,
    onDeviceKey: (EmbeddedScrcpyClient.DeviceKey) -> Unit,
    onDeviceText: (String) -> Unit,
    onToggleMirror: () -> Unit,
) {
    var textMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var deviceText by remember { mutableStateOf("") }

    Row(
        Modifier.fillMaxWidth().height(DeviceDockHeight).clip(RoundedCornerShape(8.dp))
            .background(Ink100).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DockButton("Back", enabled) { onDeviceKey(EmbeddedScrcpyClient.DeviceKey.BACK) }
        DockButton("Home", enabled) { onDeviceKey(EmbeddedScrcpyClient.DeviceKey.HOME) }
        DockButton("Recent apps", enabled) { onDeviceKey(EmbeddedScrcpyClient.DeviceKey.APP_SWITCH) }

        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(1.dp).height(18.dp).background(Ink300))
        Spacer(Modifier.width(6.dp))

        Box {
            DockButton("Type to device", enabled) { textMenu = true }
            DropdownMenu(expanded = textMenu, onDismissRequest = { textMenu = false }) {
                Column(Modifier.width(320.dp).padding(12.dp)) {
                    OutlinedTextField(
                        value = deviceText,
                        onValueChange = { deviceText = it },
                        singleLine = true,
                        placeholder = { Text("Text to send", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            runCatching {
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                            }.getOrNull()?.let { deviceText = it }
                        }) { Text("Paste host clipboard", fontSize = 11.sp) }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                onDeviceText(deviceText)
                                deviceText = ""
                                textMenu = false
                            },
                            enabled = deviceText.isNotBlank(),
                        ) { Text("Send", fontSize = 12.sp) }
                    }
                }
            }
        }

        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(1.dp).height(18.dp).background(Ink300))
        Spacer(Modifier.width(6.dp))

        // design.md §5.3.3 — inspect costs a uiautomator dump per tap; mirror is raw passthrough.
        val inspecting = interaction == InteractionMode.Inspect
        Text(
            interaction.label,
            color = if (inspecting) SignalIce else TextMid,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(if (inspecting) SignalIceDim else Ink200)
                .clickable(enabled = enabled) {
                    onInteractionChange(
                        if (inspecting) InteractionMode.Mirror else InteractionMode.Inspect,
                    )
                }
                .semantics { contentDescription = "Interaction mode: ${interaction.detail}" }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )

        Spacer(Modifier.weight(1f))

        // Stream and record state live here now, clear of the device screen.
        if (recording) {
            StageBadge("LIVE", SignalRed)
            Spacer(Modifier.width(8.dp))
        }
        StageBadge(
            if (streaming) "SCRCPY" else "SCREENSHOT",
            if (streaming) SignalGreen else SignalAmber,
        )
        Spacer(Modifier.width(6.dp))

        Box {
            DockButton("More", true) { overflowMenu = true }
            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (mirrorRunning) "Stop external mirror" else "Open external mirror",
                            color = TextHi,
                            fontSize = 12.sp,
                        )
                    },
                    enabled = enabled && (mirrorAvailable || mirrorRunning),
                    onClick = { onToggleMirror(); overflowMenu = false },
                )
            }
        }
    }
}

@Composable
internal fun DockButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) TextMid else TextLow
    HoverTip(label) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = 1.5.dp.toPx()
            when (label) {
                "Back" -> {
                    val path = GraphicsPath().apply {
                        moveTo(size.width * .62f, size.height * .18f)
                        lineTo(size.width * .3f, size.height * .5f)
                        lineTo(size.width * .62f, size.height * .82f)
                    }
                    drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
                }
                "Home" -> drawCircle(tint, radius = size.minDimension * .34f, style = Stroke(width = stroke))
                "Recent apps" -> drawRoundRect(
                    tint,
                    topLeft = Offset(size.width * .18f, size.height * .18f),
                    size = androidx.compose.ui.geometry.Size(size.width * .64f, size.height * .64f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = Stroke(width = stroke),
                )
                "Type to device" -> {
                    drawRoundRect(
                        tint,
                        topLeft = Offset(0f, size.height * .25f),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height * .5f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        style = Stroke(width = stroke),
                    )
                    val y = size.height * .42f
                    listOf(.26f, .5f, .74f).forEach { fx ->
                        drawCircle(tint, radius = stroke * .6f, center = Offset(size.width * fx, y))
                    }
                    drawLine(
                        tint,
                        Offset(size.width * .3f, size.height * .62f),
                        Offset(size.width * .7f, size.height * .62f),
                        stroke,
                        StrokeCap.Round,
                    )
                }
                else -> listOf(.2f, .5f, .8f).forEach { fx ->
                    drawCircle(tint, radius = stroke * .8f, center = Offset(size.width * fx, size.height * .5f))
                }
            }
        }
    }
    }
}

@Composable
internal fun EmptyDevice(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("▯", fontSize = 42.sp, color = TextSecondary)
        Spacer(Modifier.height(10.dp))
        Text(message, color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
