package dev.flowscope.ide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.flowscope.host.UiHierarchy
import dev.flowscope.host.WebViewSnapshot
import dev.flowscope.host.WebViewTarget

// design.md §5.7 — filmstrip plus a split detail; the hierarchy is already loaded, so showing it
// beside the screenshot costs nothing and removes a page switch.
@Composable
internal fun EvidenceSurface(
    modifier: Modifier,
    evidence: List<EvidenceArtifact>,
    selected: EvidenceArtifact?,
    image: ImageBitmap?,
    nodes: List<UiHierarchy.UiNode>,
    onSelect: (EvidenceArtifact) -> Unit,
    onRefresh: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    var hoveredNode by remember { mutableStateOf<UiHierarchy.UiNode?>(null) }

    Column(modifier.background(Panel)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Evidence", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextHi)
            Spacer(Modifier.width(10.dp))
            Text(
                "${evidence.size} CHECKPOINT(S)",
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onRefresh) { Text("Refresh", fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onOpenFolder, enabled = evidence.isNotEmpty()) {
                Text("Open folder", fontSize = 12.sp)
            }
        }
        HorizontalDivider(color = Border)

        if (evidence.isEmpty()) {
            EmptyState(
                mark = EmptyMark.Evidence,
                caps = "NO EVIDENCE",
                message = "Capture a checkpoint to record a screenshot, hierarchy, and window state.",
                hint = "⌘⇧C",
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().height(92.dp).background(Ink150)
                .horizontalScroll(rememberScrollState()).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            evidence.forEach { artifact ->
                val isSelected = artifact == selected
                Column(
                    Modifier.clickable { onSelect(artifact) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(4.dp))
                            .background(Ink000)
                            .then(
                                if (isSelected) {
                                    Modifier.border(2.dp, TextHi, RoundedCornerShape(4.dp))
                                } else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected && image != null) {
                            Image(
                                painter = BitmapPainter(image),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(2.dp),
                            )
                        } else {
                            Text(
                                "${artifact.sequence}",
                                color = TextLow,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${artifact.sequence} · ${artifact.reason}",
                        color = if (isSelected) TextHi else TextMid,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(96.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = Border)

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier.weight(1f).fillMaxHeight().background(Ink000),
                contentAlignment = Alignment.Center,
            ) {
                if (image == null) {
                    Text("No screenshot available", color = TextLow, fontSize = 12.sp)
                } else {
                    Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                        Image(
                            painter = BitmapPainter(image),
                            contentDescription = selected?.let { "Checkpoint ${it.sequence}" },
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Hovering a node in the tree highlights its bounds here (§5.7).
                        val highlightTint = SignalIce
                        hoveredNode?.let { node ->
                            Canvas(Modifier.fillMaxSize()) {
                                val scale = minOf(
                                    size.width / image.width,
                                    size.height / image.height,
                                )
                                val left = (size.width - image.width * scale) / 2f
                                val top = (size.height - image.height * scale) / 2f
                                drawRect(
                                    highlightTint.copy(alpha = .16f),
                                    topLeft = Offset(left + node.left() * scale, top + node.top() * scale),
                                    size = Size(
                                        (node.right() - node.left()) * scale,
                                        (node.bottom() - node.top()) * scale,
                                    ),
                                )
                                drawRect(
                                    highlightTint,
                                    topLeft = Offset(left + node.left() * scale, top + node.top() * scale),
                                    size = Size(
                                        (node.right() - node.left()) * scale,
                                        (node.bottom() - node.top()) * scale,
                                    ),
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
            VerticalDivider(Modifier.fillMaxHeight().width(1.dp), color = Border)
            Column(Modifier.width(330.dp).fillMaxHeight().background(Panel)) {
                Text(
                    "HIERARCHY",
                    color = TextMid,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(12.dp),
                )
                if (nodes.isEmpty()) {
                    Text(
                        "No hierarchy was captured for this checkpoint.",
                        color = TextLow,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(nodes.size) { index ->
                            val node = nodes[index]
                            HierarchyRow(
                                node = node,
                                selected = node == hoveredNode,
                                onClick = { hoveredNode = if (hoveredNode == node) null else node },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HierarchyRow(
    node: UiHierarchy.UiNode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val identity = listOfNotNull(
        node.id().takeIf(String::isNotBlank),
        node.text().takeIf(String::isNotBlank)?.let { "\"$it\"" },
        node.description().takeIf(String::isNotBlank)?.let { "desc:$it" },
    ).joinToString(" · ")
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) Ink250 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            node.className().substringAfterLast('.').ifBlank { "View" },
            color = TextHi,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (identity.isNotBlank()) {
            Text(
                identity,
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// design.md §5.10 — composed empty states, not a grey sentence.
@Composable
internal fun EmptyState(
    caps: String,
    message: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    mark: EmptyMark = EmptyMark.Events,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyGlyph(mark)
            Spacer(Modifier.height(16.dp))
            Text(
                caps,
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = TextLow,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            hint?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    color = TextMid,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Ink200)
                        .border(1.dp, Ink400, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** What an empty panel is waiting for; picks the line drawing shown above its message. */
internal enum class EmptyMark { Events, Evidence, Tree, Performance, Web, Alert }

/** A quiet line drawing on a raised tile: says what the panel will hold without a stock graphic. */
@Composable
private fun EmptyGlyph(mark: EmptyMark) {
    val tint = if (mark == EmptyMark.Alert) SignalAmber else TextMid
    val tileFill = Ink200
    val tileShape = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(56.dp).clip(tileShape).background(tileFill).border(1.dp, Ink400, tileShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun at(x: Float, y: Float) = Offset(w * x, h * y)
            when (mark) {
                // A pulse trace: events arriving over time.
                EmptyMark.Events -> drawPath(
                    Path().apply {
                        moveTo(w * .02f, h * .55f)
                        lineTo(w * .25f, h * .55f)
                        lineTo(w * .38f, h * .15f)
                        lineTo(w * .55f, h * .88f)
                        lineTo(w * .68f, h * .45f)
                        lineTo(w * .98f, h * .45f)
                    },
                    tint, style = stroke,
                )
                // A camera viewfinder: corner brackets around a lens.
                EmptyMark.Evidence -> {
                    val c = .3f
                    listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).forEach { (fx, fy) ->
                        val sx = if (fx == 0f) 1f else -1f
                        val sy = if (fy == 0f) 1f else -1f
                        val corner = at(fx, fy)
                        drawLine(tint, corner, Offset(corner.x + sx * w * c, corner.y), stroke.width, StrokeCap.Round)
                        drawLine(tint, corner, Offset(corner.x, corner.y + sy * h * c), stroke.width, StrokeCap.Round)
                    }
                    drawCircle(tint, radius = w * .17f, center = at(.5f, .5f), style = stroke)
                }
                // A parent node branching to two children: the view hierarchy.
                EmptyMark.Tree -> {
                    val top = at(.5f, .14f)
                    val left = at(.2f, .84f)
                    val right = at(.8f, .84f)
                    val fork = at(.5f, .5f)
                    drawLine(tint, top, fork, stroke.width, StrokeCap.Round)
                    drawLine(tint, fork, Offset(left.x, fork.y), stroke.width, StrokeCap.Round)
                    drawLine(tint, fork, Offset(right.x, fork.y), stroke.width, StrokeCap.Round)
                    drawLine(tint, Offset(left.x, fork.y), left, stroke.width, StrokeCap.Round)
                    drawLine(tint, Offset(right.x, fork.y), right, stroke.width, StrokeCap.Round)
                    val node = w * .1f
                    listOf(top, left, right).forEach { drawCircle(tileFill, node * 1.3f, it); drawCircle(tint, node, it, style = stroke) }
                }
                // A frame-time histogram: three bars of different height.
                EmptyMark.Performance -> {
                    listOf(.18f to .5f, .5f to .9f, .82f to .68f).forEach { (x, top) ->
                        drawLine(tint, at(x, .92f), at(x, 1f - top), stroke.width * 2.2f, StrokeCap.Round)
                    }
                }
                // A globe: meridians and an equator.
                EmptyMark.Web -> {
                    drawCircle(tint, radius = w * .46f, center = at(.5f, .5f), style = stroke)
                    drawLine(tint, at(.04f, .5f), at(.96f, .5f), stroke.width, StrokeCap.Round)
                    drawOval(tint, topLeft = at(.3f, .04f), size = Size(w * .4f, h * .92f), style = stroke)
                }
                // A caution triangle with a mark.
                EmptyMark.Alert -> {
                    drawPath(
                        Path().apply {
                            moveTo(w * .5f, h * .08f)
                            lineTo(w * .96f, h * .9f)
                            lineTo(w * .04f, h * .9f)
                            close()
                        },
                        tint, style = stroke,
                    )
                    drawLine(tint, at(.5f, .38f), at(.5f, .62f), stroke.width, StrokeCap.Round)
                    drawCircle(tint, radius = stroke.width * .7f, center = at(.5f, .76f))
                }
            }
        }
    }
}

// design.md §5.8 — four tiles and a frame-time histogram instead of 16,000 characters of dumpsys.
@Composable
internal fun PerformanceSurface(
    modifier: Modifier,
    snapshot: PerformanceSnapshot?,
    previous: PerformanceSnapshot?,
    packageName: String,
    busy: Boolean,
    onCollect: () -> Unit,
) {
    var showRaw by remember { mutableStateOf(false) }

    Column(modifier.background(Panel)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Performance", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextHi)
            Spacer(Modifier.width(10.dp))
            Text(
                "meminfo + gfxinfo · ${packageName.ifBlank { "no package set" }}",
                color = TextMid,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            if (snapshot != null) {
                Text(
                    if (showRaw) "TILES" else "RAW",
                    color = TextMid,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Ink200)
                        .clickable { showRaw = !showRaw }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = onCollect, enabled = !busy) {
                Text(if (busy) "Collecting…" else "Collect snapshot", fontSize = 12.sp)
            }
        }
        HorizontalDivider(color = Border)

        when {
            snapshot == null -> EmptyState(
                mark = EmptyMark.Performance,
                caps = "NO SNAPSHOT",
                message = "Collect a snapshot to inspect memory and rendering for this package.",
                modifier = Modifier.weight(1f),
            )
            showRaw -> Column(
                Modifier.weight(1f).fillMaxWidth().background(Ink050)
                    .verticalScroll(rememberScrollState()).padding(14.dp),
            ) {
                Text(
                    snapshot.raw,
                    color = TextMid,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
            }
            else -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(
                        "JAVA HEAP", formatMegabytes(snapshot.javaHeapKb), "MB",
                        delta(snapshot.javaHeapKb, previous?.javaHeapKb), Modifier.weight(1f),
                    )
                    MetricTile(
                        "NATIVE HEAP", formatMegabytes(snapshot.nativeHeapKb), "MB",
                        delta(snapshot.nativeHeapKb, previous?.nativeHeapKb), Modifier.weight(1f),
                    )
                    MetricTile(
                        "TOTAL PSS", formatMegabytes(snapshot.totalPssKb), "MB",
                        delta(snapshot.totalPssKb, previous?.totalPssKb), Modifier.weight(1f),
                    )
                    MetricTile(
                        "JANK", formatPercent(snapshot.jankPercent), "%",
                        null, Modifier.weight(1f),
                        tint = snapshot.jankPercent?.let {
                            when {
                                it < 5 -> SignalGreen
                                it < 20 -> SignalAmber
                                else -> SignalRed
                            }
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (snapshot.percentiles.isNotEmpty()) {
                    Text(
                        "FRAME TIME PERCENTILES",
                        color = TextMid,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = 1.2.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    FrameHistogram(snapshot.percentiles, Modifier.fillMaxWidth().height(140.dp))
                }
                snapshot.totalFrames?.let { frames ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "$frames frames rendered · ${snapshot.jankyFrames ?: 0} janky",
                        color = TextMid,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

private fun delta(current: Long?, previous: Long?): String? {
    if (current == null || previous == null) return null
    val difference = current - previous
    if (difference == 0L) return "no change"
    val sign = if (difference > 0) "+" else "−"
    return "$sign${formatMegabytes(kotlin.math.abs(difference))} MB"
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String,
    delta: String?,
    modifier: Modifier,
    tint: Color? = null,
) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(Ink150)
            .border(1.dp, Ink300, RoundedCornerShape(8.dp)).padding(12.dp),
    ) {
        Text(
            label,
            color = TextMid,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = tint ?: TextHi,
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(4.dp))
            Text(unit, color = TextMid, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(delta ?: "first snapshot", color = TextLow, fontSize = 10.sp)
    }
}

@Composable
private fun FrameHistogram(percentiles: Map<Int, Int>, modifier: Modifier) {
    val ordered = percentiles.toList().sortedBy { it.first }
    val peak = (ordered.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Ink000).padding(12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ordered.forEach { (percentile, milliseconds) ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    "${milliseconds}ms",
                    color = TextMid,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .height((96 * milliseconds / peak).coerceAtLeast(2).dp)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(
                            when (bandOf(milliseconds)) {
                                FrameBand.Good -> SignalGreen
                                FrameBand.Warn -> SignalAmber
                                FrameBand.Bad -> SignalRed
                            },
                        ),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "p$percentile",
                    color = TextLow,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

/** The captured hierarchy as a structured, searchable list rather than an indented text dump. */
@Composable
internal fun TreeSurface(
    modifier: Modifier,
    nodes: List<UiHierarchy.UiNode>,
    subtitle: String,
    onRefresh: () -> Unit,
    webSnapshot: WebViewSnapshot?,
    webLoading: Boolean,
    webError: String?,
    onRefreshWeb: () -> Unit,
    webTargets: List<WebViewTarget>,
    selectedWebTarget: WebViewTarget?,
    onSelectWebTarget: (WebViewTarget) -> Unit,
    onSelectWeb: (String, List<Pair<String, String>>) -> Unit,
) {
    var view by remember { mutableStateOf("NATIVE") }
    var query by remember { mutableStateOf("") }
    val visible = remember(nodes, query) {
        if (query.isBlank()) nodes
        else nodes.filter {
            val q = query.lowercase()
            it.className().lowercase().contains(q) || it.id().lowercase().contains(q) ||
                it.text().lowercase().contains(q) || it.description().lowercase().contains(q)
        }
    }
    Column(modifier.background(Panel)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tree", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextHi)
            Spacer(Modifier.width(10.dp))
            TreeModeButton("NATIVE", view == "NATIVE") { view = "NATIVE" }
            Spacer(Modifier.width(4.dp))
            TreeModeButton("WEBVIEW", view == "WEBVIEW") {
                view = "WEBVIEW"
                if (webSnapshot == null && !webLoading) onRefreshWeb()
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (view == "NATIVE") "${visible.size} node(s) · $subtitle"
                else webSnapshot?.targetUrl().orEmpty(),
                color = TextMid,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (view == "NATIVE") {
                OutlinedButton(onClick = onRefresh) { Text("Refresh", fontSize = 12.sp) }
            } else {
                HoverTip("Refresh WebView inspection") {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                            .border(1.dp, Border, RoundedCornerShape(6.dp))
                            .semantics {
                                contentDescription = "Refresh WebView inspection"
                                role = Role.Button
                            }
                            .clickable(enabled = !webLoading, onClick = onRefreshWeb),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("↻", color = if (webLoading) TextLow else TextHi, fontSize = 17.sp)
                    }
                }
            }
        }
        HorizontalDivider(color = Border)
        if (view == "WEBVIEW") {
            WebViewInspectionBody(
                webSnapshot, webLoading, webError, webTargets, selectedWebTarget,
                onSelectWebTarget, onSelectWeb,
            )
        } else if (nodes.isEmpty()) {
            EmptyState(
                mark = EmptyMark.Tree,
                caps = "NO HIERARCHY",
                message = "Capture a checkpoint to inspect the UI hierarchy.",
                hint = "⌘⇧C",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(visible.size) { index ->
                    val node = visible[index]
                    val identity = listOfNotNull(
                        node.id().takeIf(String::isNotBlank)?.let { "id=$it" },
                        node.text().takeIf(String::isNotBlank)?.let { "text=\"$it\"" },
                        node.description().takeIf(String::isNotBlank)?.let { "desc=\"$it\"" },
                    ).joinToString("  ")
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            node.className().substringAfterLast('.').ifBlank { "View" },
                            color = TextHi,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.width(170.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            identity,
                            color = TextMid,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${node.right() - node.left()}×${node.bottom() - node.top()}",
                            color = TextLow,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                    }
                    HorizontalDivider(color = Border)
                }
            }
        }
    }
}

@Composable
private fun TreeModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) TextHi else TextMid,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (selected) Ink250 else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun WebViewInspectionBody(
    snapshot: WebViewSnapshot?,
    loading: Boolean,
    error: String?,
    targets: List<WebViewTarget>,
    selectedTarget: WebViewTarget?,
    onSelectTarget: (WebViewTarget) -> Unit,
    onSelect: (String, List<Pair<String, String>>) -> Unit,
) {
    when {
        loading -> EmptyState(
            mark = EmptyMark.Web,
            caps = "INSPECTING WEBVIEW",
            message = "Reading package-scoped DevTools metadata. Values remain hidden.",
            hint = "",
            modifier = Modifier.fillMaxSize(),
        )
        error != null -> EmptyState(
            mark = EmptyMark.Alert,
            caps = "WEBVIEW UNAVAILABLE",
            message = error,
            hint = "Enable WebView debugging in the debug build, then refresh.",
            modifier = Modifier.fillMaxSize(),
        )
        snapshot == null -> EmptyState(
            mark = EmptyMark.Web,
            caps = "NO WEBVIEW SNAPSHOT",
            message = "Refresh to inspect the selected package's active WebView.",
            hint = "Values are never shown or persisted.",
            modifier = Modifier.fillMaxSize(),
        )
        else -> LazyColumn(Modifier.fillMaxSize()) {
            if (targets.size > 1) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .background(Ink150).padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        targets.forEachIndexed { index, target ->
                            val selected = target == selectedTarget
                            Text(
                                "${index + 1} · ${target.url()}",
                                color = if (selected) TextHi else TextMid,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(if (selected) Ink250 else Ink100)
                                    .border(1.dp, if (selected) Ink400 else Border, RoundedCornerShape(4.dp))
                                    .clickable { onSelectTarget(target) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                maxLines = 1,
                            )
                        }
                    }
                    HorizontalDivider(color = Border)
                }
            }
            item { WebSectionHeader("OVERVIEW", "${snapshot.frames().size} frame(s)") }
            item {
                WebRow("Target", snapshot.targetUrl(), "SANITIZED") {
                    onSelect("Target", listOf("URL" to snapshot.targetUrl(), "QUERY VALUES" to "HIDDEN"))
                }
            }
            snapshot.frames().forEach { frame ->
                item {
                    WebRow(frame.name().ifBlank { "Frame" }, frame.url(), "FRAME") {
                        onSelect("Frame", listOf("ID" to frame.id(), "PARENT" to frame.parentId(),
                            "URL" to frame.url()))
                    }
                }
            }
            item { WebSectionHeader("COOKIES", "${snapshot.cookies().size} · VALUES HIDDEN") }
            snapshot.cookies().forEach { cookie ->
                item {
                    val flags = listOfNotNull(
                        "Secure".takeIf { cookie.secure() }, "HttpOnly".takeIf { cookie.httpOnly() },
                        cookie.sameSite().takeIf(String::isNotBlank)?.let { "SameSite=$it" },
                    ).joinToString(" · ").ifBlank { "No security flags" }
                    WebRow(cookie.name(), "${cookie.domain()}${cookie.path()}", flags) {
                        onSelect("Cookie ${cookie.name()}", listOf(
                            "DOMAIN" to cookie.domain(), "PATH" to cookie.path(), "VALUE" to "HIDDEN",
                            "SECURE" to cookie.secure().toString(), "HTTPONLY" to cookie.httpOnly().toString(),
                            "SAMESITE" to cookie.sameSite().ifBlank { "unset" },
                            "EXPIRY" to cookie.expires(), "PARTITIONED" to cookie.partitioned().toString(),
                            "SIZE" to cookie.size().toString(),
                        ))
                    }
                }
            }
            item { WebSectionHeader("STORAGE", "${snapshot.storage().size} KEY(S) · VALUES HIDDEN") }
            snapshot.storage().forEach { entry ->
                item {
                    WebRow(entry.key(), entry.origin(), entry.area().uppercase()) {
                        onSelect("${entry.area()} storage", listOf(
                            "KEY" to entry.key(), "ORIGIN" to entry.origin(), "VALUE" to "HIDDEN",
                            "SIZE" to entry.size().toString(),
                        ))
                    }
                }
            }
            item { WebSectionHeader("NATIVE BRIDGES", "${snapshot.bridges().size}") }
            snapshot.bridges().forEach { bridge ->
                item {
                    WebRow(bridge.name(), bridge.methods().joinToString(", ").ifBlank { "No enumerable methods" },
                        "${bridge.source().uppercase()} · ${bridge.confidence()}") {
                        onSelect("Bridge ${bridge.name()}", listOf(
                            "METHODS" to bridge.methods().joinToString(", "),
                            "SOURCE" to bridge.source(), "CONFIDENCE" to bridge.confidence(),
                        ))
                    }
                }
            }
            item { WebSectionHeader("SECURITY", if (snapshot.security().secureContext()) "SECURE CONTEXT" else "INSECURE") }
            item {
                val security = snapshot.security()
                WebRow("Secure context", security.secureContext().toString(),
                    if (security.mixedContent()) "MIXED CONTENT" else "NO MIXED CONTENT") {
                    onSelect("WebView security", listOf(
                        "SECURE CONTEXT" to security.secureContext().toString(),
                        "MIXED CONTENT" to security.mixedContent().toString(),
                        "JAVASCRIPT" to (security.javaScriptEnabled()?.toString() ?: "unknown"),
                        "DOM STORAGE" to (security.domStorageEnabled()?.toString() ?: "unknown"),
                        "FILE ACCESS" to (security.allowFileAccess()?.toString() ?: "unknown"),
                        "CONTENT ACCESS" to (security.allowContentAccess()?.toString() ?: "unknown"),
                        "MIXED CONTENT MODE" to security.mixedContentMode(),
                        "SAFE BROWSING" to (security.safeBrowsingEnabled()?.toString() ?: "unknown"),
                    ))
                }
            }
            snapshot.diagnostics().forEach { diagnostic ->
                item { WebRow("Diagnostic", diagnostic, "REVIEW") { onSelect("Security diagnostic", listOf("DETAIL" to diagnostic)) } }
            }
            snapshot.warnings().forEach { warning ->
                item { WebRow("Capability", warning, "UNAVAILABLE") { onSelect("Capability warning", listOf("DETAIL" to warning)) } }
            }
        }
    }
}

@Composable
private fun WebSectionHeader(title: String, summary: String) {
    Row(
        Modifier.fillMaxWidth().background(Ink150).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextMid, fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text(summary, color = TextLow, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
    }
    HorizontalDivider(color = Border)
}

@Composable
private fun WebRow(title: String, detail: String, badge: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextHi, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            modifier = Modifier.width(180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, color = TextMid, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(badge, color = TextLow, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
    }
    HorizontalDivider(color = Border)
}
