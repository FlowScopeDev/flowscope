package dev.flowscope.ide

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.MutableState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path as GraphicsPath
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.flowscope.host.AdbClient
import dev.flowscope.host.CaptureMode
import dev.flowscope.host.Device
import dev.flowscope.host.EmbeddedScrcpyClient
import dev.flowscope.host.EmulatorClient
import dev.flowscope.host.FlowProject
import dev.flowscope.host.FlowSuite
import dev.flowscope.host.FlowSuiteParser
import dev.flowscope.host.FlowSuiteRunner
import dev.flowscope.host.FlowTestParser
import dev.flowscope.host.FlowTestRunner
import dev.flowscope.host.ProjectManifest
import dev.flowscope.host.ProjectScaffold
import dev.flowscope.host.RunReport
import dev.flowscope.host.InteractionSteps
import dev.flowscope.host.ScrcpyClient
import dev.flowscope.host.ScrcpyControl
import dev.flowscope.host.SessionRecorder
import dev.flowscope.host.UiHierarchy
import dev.flowscope.host.UiSelectorResolver
import dev.flowscope.host.WebViewInspector
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel as FrameChannel
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Dimension
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

fun main() {
    // Must be set before the AWT toolkit initialises.
    if (IsMac) System.setProperty("apple.awt.application.appearance", "system")
    val initialTheme = Preferences.loadThemeMode()
    application {
        val state = rememberWindowState(width = 1440.dp, height = 900.dp)
        var themeMode by remember { mutableStateOf(initialTheme) }
        // Shortcuts are handled by the window, not by a Modifier inside it. A
        // `Modifier.onPreviewKeyEvent` only runs when something in its subtree holds focus, so on a
        // freshly opened window — where nothing is focused yet — every shortcut was silently dead.
        // The window's own handler sees each key regardless of focus.
        val shortcuts = remember { mutableStateOf<(KeyEvent) -> Boolean>({ false }) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Flow Scope",
            state = state,
            onPreviewKeyEvent = { shortcuts.value(it) },
        ) {
            DisposableEffect(Unit) {
                onDispose { DesktopNotifications.close() }
            }
            window.minimumSize = Dimension(1040, 680)
            // design.md §4.1 — extend content under the title bar so the session bar is the top
            // of the window. The traffic lights float over it; SessionBar reserves their inset.
            if (IsMac) {
                runCatching {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            FlowScopeTheme(themeMode) {
                // The window is needed for modal dialog ownership (ProjectPicker).
                FlowScopeApp(themeMode, window, shortcuts, { themeMode = it })
            }
        }
    }
}

@Composable
private fun FlowScopeApp(
    themeMode: ThemeMode,
    window: java.awt.Frame?,
    shortcuts: MutableState<(KeyEvent) -> Boolean>,
    onThemeModeChanged: (ThemeMode) -> Unit,
) {
    val adb = remember { AdbClient() }
    val scrcpy = remember { ScrcpyClient() }
    val emulator = remember { EmulatorClient() }
    val embeddedScrcpy = remember { EmbeddedScrcpyClient(adb, scrcpy) }
    val embeddedFrames = remember {
        FrameChannel<EmbeddedScrcpyClient.Frame>(FrameChannel.CONFLATED)
    }
    val scope = rememberCoroutineScope()
    val authoring = remember { AuthoringState() }
    val projectState = remember { ProjectState() }
    val shell = remember { ShellState() }
    val timeline = remember { TimelineState() }
    val session = remember { SessionState() }
    val deviceState = remember {
        DeviceState().apply {
            embeddedStatus = embeddedScrcpy.availabilitySummary()
            mirrorStatus = if (scrcpy.available()) {
                "scrcpy ready"
            } else "scrcpy not installed · screenshot fallback active"
        }
    }
    LaunchedEffect(Unit) {
        shell.reduceMotion = withContext(Dispatchers.IO) { detectReduceMotion() }
        deviceState.favourites = withContext(Dispatchers.IO) { Preferences.loadFavourites() }
        deviceState.showDisplay = withContext(Dispatchers.IO) { Preferences.loadShowDevice() }
        deviceState.recentPackages = withContext(Dispatchers.IO) { Preferences.loadRecentPackages() }
        // Recents are loaded by the session-restore effect, which needs them sequentially.
    }

    // Gated on installedPackages rather than applied inline: openProject can land before the
    // effect that loads the device's package list, and prefilling a package the device does not
    // have would block recording with a confusing message.
    LaunchedEffect(projectState.root, deviceState.installedPackages) {
        val defaults = projectState.manifest?.defaults() ?: return@LaunchedEffect
        defaults.packageName()?.let { configured ->
            val installed = deviceState.installedPackages
            if (deviceState.packageName.isBlank() &&
                (installed.isEmpty() || configured in installed)
            ) {
                deviceState.packageName = configured
            }
        }
        defaults.mode()?.let { mode ->
            if (!deviceState.captureModeChosen) deviceState.captureMode = mode
        }
        defaults.serial()?.let { serial ->
            if (deviceState.selected == null) {
                deviceState.devices.firstOrNull { it.serial() == serial }
                    ?.let { deviceState.selected = it }
            }
        }
    }

    LaunchedEffect(deviceState.serial, deviceState.packageName, deviceState.captureMode) {
        session.webViewSnapshot = null
        session.webViewTargets = emptyList()
        session.selectedWebViewTarget = null
        session.webViewError = null
    }

    suspend fun refreshDevices() {
        deviceState.busy = true
        runCatching { withContext(Dispatchers.IO) { adb.devices() } }
            .onSuccess { found ->
                deviceState.devices = found.filter { it.state() == "device" }
                val currentSerial = deviceState.selected?.serial()
                // While recording, keep the selection pinned — the only thing that matters is
                // whether the target is still attached.
                if (session.recording != null) {
                    if (deviceState.devices.none { it.serial() == currentSerial }) {
                        deviceState.lostSerial = currentSerial
                    }
                    deviceState.busy = false
                    return@onSuccess
                }
                deviceState.selected = deviceState.devices.firstOrNull { it.serial() == currentSerial }
                    ?: deviceState.devices.singleOrNull()
                    ?: deviceState.devices.firstOrNull()
                session.status = when {
                    deviceState.devices.isEmpty() -> "No authorized device detected"
                    deviceState.selected != null -> "Connected over ADB"
                    else -> "Choose a device"
                }
                if (timeline.events.isEmpty()) {
                    deviceState.selected?.let {
                        timeline.sequence += 1
                        timeline.add(
                            CapturedEvent(
                                id = timeline.sequence,
                                time = now(),
                                epochMillis = System.currentTimeMillis(),
                                symbol = "✓",
                                title = "Device connected",
                                detail = "${it.displayName()} · ${it.serial()}",
                            ),
                        )
                    }
                }
            }
            .onFailure { session.status = it.message ?: "ADB is unavailable" }
        deviceState.busy = false
    }

    suspend fun refreshAvds() {
        deviceState.emulatorAvailable = emulator.available()
        if (!emulator.available()) return
        deviceState.avdNames = runCatching { withContext(Dispatchers.IO) { emulator.listAvds() } }
            .getOrDefault(emptyList())
    }

    // Which AVD each running emulator is costs one `adb emu` call per emulator, so it is read when
    // the set of attached devices changes rather than on every 3 s poll.
    LaunchedEffect(deviceState.devices.map { it.serial() }) {
        if (!emulator.available()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                deviceState.runningAvds = emulator.runningEmulators(adb)
                deviceState.ownedAvds = emulator.ownedAvds()
            }
        }
    }
    LaunchedEffect(Unit) { refreshAvds() }

    fun startAvd(name: String, headless: Boolean) {
        scope.launch {
            deviceState.bootingAvds = deviceState.bootingAvds + name
            session.status = "Starting $name…"
            runCatching {
                withContext(Dispatchers.IO) {
                    emulator.start(adb, name, headless)
                    emulator.waitForBoot(adb, name, EmulatorClient.DEFAULT_BOOT_TIMEOUT_MS)
                }
            }.onSuccess { serial ->
                deviceState.ownedAvds = deviceState.ownedAvds + name
                refreshDevices()
                deviceState.devices.firstOrNull { it.serial() == serial }
                    ?.takeIf { session.recording == null }
                    ?.let { deviceState.selected = it }
                session.status = "$name is ready as $serial"
            }.onFailure { session.status = it.message ?: "Could not start $name" }
            deviceState.bootingAvds = deviceState.bootingAvds - name
        }
    }

    fun stopAvd(serial: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { emulator.stop(adb, serial) } }
                .onSuccess { session.status = "Stopped $serial" }
                .onFailure { session.status = it.message ?: "Could not stop $serial" }
            refreshDevices()
        }
    }

    LaunchedEffect(Unit) {
        launch {
            for (frame in embeddedFrames) {
                // Raw BGRA straight from the decoder — no JPEG round trip, no marker scanning.
                runCatching { frame.toComposeBitmap() }
                    .onSuccess { deviceState.embeddedFrame = it }
                // makeRaster copies, so the buffer is free the moment the image exists.
                frame.recycle()
            }
        }
        refreshDevices()
        while (isActive) {
            delay(3_000)
            // Poll during recording too: a disconnected device must not look like a live session.
            if (!deviceState.busy) refreshDevices()
        }
    }

    LaunchedEffect(deviceState.selected?.serial()) {
        val serial = deviceState.selected?.serial()
        deviceState.installedPackages = if (serial == null) emptyList() else {
            runCatching { withContext(Dispatchers.IO) { adb.installedPackages(serial) } }
                .getOrDefault(emptyList())
        }
    }

    LaunchedEffect(deviceState.selected?.serial(), deviceState.showDisplay) {
        deviceState.embeddedSession?.let { previous -> withContext(Dispatchers.IO) { previous.close() } }
        deviceState.embeddedSession = null
        deviceState.embeddedFrame = null
        deviceState.mirrorSession?.let { previous ->
            withContext(Dispatchers.IO) { previous.close() }
            deviceState.mirrorSession = null
            deviceState.mirrorStatus = if (scrcpy.available()) "scrcpy ready" else "scrcpy not installed · screenshot fallback active"
        }
        deviceState.screenshot = null
        if (!deviceState.showDisplay) return@LaunchedEffect
        val serial = deviceState.selected?.serial() ?: return@LaunchedEffect
        while (isActive) {
            runCatching {
                withContext(Dispatchers.IO) {
                    SkiaImage.makeFromEncoded(adb.execOut(serial, "screencap", "-p"))
                        .toComposeImageBitmap()
                }
            }.onSuccess { deviceState.screenshot = it }
            deviceState.embeddedSession?.let {
                if (!it.isAlive()) {
                    val detail = it.failureSummary()
                    deviceState.embeddedSession = null
                    deviceState.embeddedStatus = "Embedded stream stopped: ${detail.take(100)}"
                }
            }
            delay(if (session.recording != null) 500 else 1_500)
        }
    }

    LaunchedEffect(session.recording) {
        session.seconds = 0
        while (session.recording != null && isActive) {
            delay(1_000)
            session.seconds++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { session.recording?.close() }
            runCatching { deviceState.mirrorSession?.close() }
            runCatching { deviceState.embeddedSession?.close() }
            embeddedFrames.close()
        }
    }

    fun addEvent(
        symbol: String,
        title: String,
        detail: String,
        channel: Channel = Channel.Session,
        severity: Severity = Severity.Normal,
        payload: String? = null,
        suggestedStep: String? = null,
    ) {
        timeline.sequence += 1
        timeline.add(
            CapturedEvent(
                id = timeline.sequence,
                time = now(),
                epochMillis = System.currentTimeMillis(),
                symbol = symbol,
                title = title,
                detail = detail,
                channel = channel,
                severity = severity,
                payload = payload,
                suggestedStep = suggestedStep,
            ),
        )
    }

    fun appendStep(step: String) = authoring.appendStep(step)

    fun observeRecorderLine(channel: String, line: String) {
        scope.launch {
            when (channel) {
                "agent" -> {
                    val kind = jsonString(line, "kind") ?: "agent.event"
                    val channel = when {
                        kind.startsWith("network.") -> Channel.Network
                        kind.startsWith("background.") -> Channel.Background
                        kind.startsWith("activity.") || kind.startsWith("navigation") -> Channel.Lifecycle
                        else -> Channel.Ui
                    }
                    val step = suggestedFlowStep(line, kind)
                    addEvent(
                        agentSymbol(kind), kind, agentDetail(line, kind),
                        channel = channel, payload = line, suggestedStep = step,
                    )
                    step?.let(::appendStep)
                }
                "accessibility" -> addEvent(
                    "◎", "Accessibility", line.take(180), channel = Channel.Ui, payload = line,
                )
                "host" -> {
                    val kind = jsonString(line, "kind") ?: "ui.interaction"
                    addEvent(
                        if (kind == "ui.tap") "●" else "↕", kind, line.take(180),
                        channel = Channel.Ui, payload = line,
                    )
                }
                "logs" -> addEvent(
                    "!", "Logcat warning", line.take(180),
                    channel = Channel.Logs, severity = Severity.Warning, payload = line,
                )
            }
        }
    }

    suspend fun refreshSessionArtifacts(root: Path, preferLatest: Boolean = false) {
        val loaded = withContext(Dispatchers.IO) { loadEvidence(root) }
        session.evidence = loaded
        session.selectedEvidence = if (preferLatest) loaded.lastOrNull() else {
            session.selectedEvidence?.let { selected -> loaded.firstOrNull { it.directory == selected.directory } }
                ?: loaded.lastOrNull()
        }
        val selected = session.selectedEvidence
        session.evidenceImage = withContext(Dispatchers.IO) { selected?.screenshot?.let(::loadImage) }
        session.hierarchyNodes = withContext(Dispatchers.IO) { loadHierarchyNodes(selected?.hierarchy) }
    }

    // ---------------------------------------------------------------- project

    /** Re-reads the open project's tests, suites, and recent runs. */
    suspend fun refreshProjectListing() {
        val project = projectState.project
        if (project == null) {
            // With no project open, the rail still surfaces whatever was authored before one
            // existed, so pre-upgrade work stays one "Save as…" away from a project.
            val scratchDir = FlowScopeHome.resolve("tests")
            projectState.tests = emptyList()
            projectState.suites = emptyList()
            projectState.runs = withContext(Dispatchers.IO) {
                runCatching { RunReport.list(FlowScopeHome.resolve("test-runs"), RunListLimit) }
                    .getOrDefault(emptyList())
            }
            projectState.scratch = withContext(Dispatchers.IO) { listFlowFiles(scratchDir) }
            return
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                Triple(
                    project.listTests().map { ProjectEntry(it, relativeLabel(project.testsDir(), it)) },
                    project.listSuites().map { ProjectEntry(it, relativeLabel(project.suitesDir(), it)) },
                    RunReport.list(project.reportsDir(), RunListLimit),
                )
            }.getOrNull()
        }
        if (loaded != null) {
            projectState.tests = loaded.first
            projectState.suites = loaded.second
            projectState.runs = loaded.third
        }
        projectState.scratch = emptyList()
    }

    /** Opens a project and waits for it. [openProject] is the fire-and-forget wrapper. */
    suspend fun openProjectAwaiting(root: Path) {
        run {
            val opened = withContext(Dispatchers.IO) { runCatching { FlowProject.open(root) } }
            opened.onSuccess { project ->
                projectState.project = project
                projectState.status = "${project.manifest().name()} · ${project.root()}"
                withContext(Dispatchers.IO) {
                    Preferences.rememberRecentProject(project.root(), authoring.currentFile)
                }
                projectState.recent = withContext(Dispatchers.IO) { Preferences.loadRecentProjects() }
                refreshProjectListing()
                addEvent("▣", "Project opened", projectState.status, channel = Channel.Session)
            }.onFailure { failure ->
                projectState.status = failure.message ?: "Could not open that project"
                addEvent("×", "Project not opened", projectState.status, severity = Severity.Error)
            }
        }
    }

    fun openProject(root: Path) {
        scope.launch { openProjectAwaiting(root) }
    }

    /** Creates the layout in [root], then opens it. */
    fun createProject(root: Path, name: String?) {
        scope.launch {
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    val label = name?.trim()?.ifEmpty { null }
                        ?: root.fileName?.toString() ?: "FlowScope project"
                    val manifest = ProjectManifest(
                        ProjectManifest.SCHEMA_VERSION, label,
                        ProjectManifest.DEFAULT_FLOWS_DIR, ProjectManifest.DEFAULT_REPORTS_DIR,
                        ProjectManifest.Defaults(
                            deviceState.packageName.trim().ifEmpty { null },
                            deviceState.captureMode,
                            null,
                            null,
                        ),
                        null,
                    )
                    ProjectScaffold.initialize(root, manifest)
                }
            }
            created.onSuccess { project ->
                addEvent("▣", "Project created", project.root().toString(), channel = Channel.Session)
                openProject(project.root())
            }.onFailure { failure ->
                projectState.status = failure.message ?: "Could not create that project"
                addEvent("×", "Project not created", projectState.status, severity = Severity.Error)
            }
        }
    }

    /** Opens [root] when it is already a project, and offers to initialise it when it is not. */
    fun openOrInitialise(root: Path) {
        scope.launch {
            val hasMarker = withContext(Dispatchers.IO) {
                Files.isRegularFile(root.resolve(FlowProject.MANIFEST_NAME))
            }
            if (hasMarker) openProject(root) else createProject(root, null)
        }
    }

    fun closeProject() {
        projectState.project = null
        projectState.status = "No project open"
        scope.launch { refreshProjectListing() }
        addEvent("□", "Project closed", "Saves now go to $FlowScopeHome", channel = Channel.Session)
    }

    fun reloadProject() {
        projectState.root?.let(::openProject)
    }

    fun writeManifest(manifest: ProjectManifest) {
        val project = projectState.project ?: return
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { ProjectManifest.write(project.manifestPath(), manifest) }
            }
            written.onSuccess {
                projectState.project = FlowProject(project.root(), manifest)
                projectState.status = "Saved ${project.manifestPath().fileName}"
                refreshProjectListing()
            }.onFailure { failure ->
                projectState.status = failure.message ?: "Could not write flowscope.json"
                addEvent("×", "Project settings not saved", projectState.status,
                    severity = Severity.Error)
            }
        }
    }

    fun revealProjectFolder() {
        val target = projectState.root ?: FlowScopeHome
        scope.launch(Dispatchers.IO) {
            revealInFileManager(target)
        }
    }

    // ------------------------------------------------------------------- files

    /** Renders a path for a status line: relative to the open project, else `~`-shortened. */
    fun shown(path: Path): String = displayPath(path, projectState.root)

    suspend fun openFileAwaiting(path: Path) {
        run {
            val read = withContext(Dispatchers.IO) {
                runCatching { Files.readString(path, StandardCharsets.UTF_8) }
            }
            read.onSuccess { contents ->
                authoring.adopt(path, contents)
                authoring.lastRun = null
                authoring.status = "Opened · ${shown(path)}"
                shell.surface = WorkSurface.Flow
                projectState.root?.let { root ->
                    withContext(Dispatchers.IO) { Preferences.rememberRecentProject(root, path) }
                }
            }.onFailure { failure ->
                authoring.status = failure.message ?: "Could not open that file"
                addEvent("×", "File not opened", authoring.status, severity = Severity.Error)
            }
        }
    }

    fun openFile(path: Path) {
        scope.launch { openFileAwaiting(path) }
    }

    /** The names already taken in [directory], so a new file never silently overwrites one. */
    suspend fun takenNames(directory: Path): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!Files.isDirectory(directory)) emptySet()
            else Files.list(directory).use { children ->
                children.map { it.fileName.toString() }.toList().toSet()
            }
        }.getOrDefault(emptySet())
    }

    /** Where this buffer should be written when it has no file of its own yet. */
    fun refreshWebViewInspection(pageMatch: String? = session.selectedWebViewTarget?.pageMatch()) {
        val device = deviceState.selected
        val packageName = deviceState.packageName.trim()
        if (device == null || packageName.isBlank()) {
            session.webViewSnapshot = null
            session.webViewError = "Select a connected device and target package first."
            return
        }
        scope.launch {
            session.webViewLoading = true
            session.webViewError = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val targets = WebViewInspector.targets(adb, device, packageName)
                    if (targets.isEmpty()) error("No debuggable WebView page is available")
                    val selected = targets.firstOrNull { it.pageMatch() == pageMatch } ?: targets.first()
                    selected to (targets to WebViewInspector.inspect(
                        adb, device, packageName, selected.pageMatch(), deviceState.captureMode, 10_000,
                    ))
                }
            }.onSuccess { (selected, result) ->
                val (targets, snapshot) = result
                session.webViewTargets = targets
                session.selectedWebViewTarget = selected
                session.webViewSnapshot = snapshot
                addEvent(
                    "◇", "WebView inspected",
                    "${snapshot.cookies().size} cookies · ${snapshot.storage().size} storage keys · " +
                        "${snapshot.bridges().size} bridges",
                )
            }.onFailure { failure ->
                session.webViewSnapshot = null
                session.webViewError = failure.message ?: "WebView inspection failed"
            }
            session.webViewLoading = false
        }
    }

    /**
     * Waits for the startup restore to settle. Returns immediately once it has, which is every
     * call but the ones in the first moments after launch.
     */
    suspend fun awaitProjectRestore() {
        if (!projectState.restoring) return
        withTimeoutOrNull(RestoreWaitMillis) {
            snapshotFlow { projectState.restoring }.first { !it }
        }
    }

    suspend fun deriveTarget(source: String, kind: EditorKind): Path {
        awaitProjectRestore()
        val directory = if (kind == EditorKind.Suite) {
            projectState.suitesDirectory ?: FlowScopeHome.resolve("suites")
        } else projectState.testsDirectory
        val extension = if (kind == EditorKind.Suite) "suite" else "flow"
        val candidate = suggestedFileName(testNameFromSource(source), extension)
        return directory.resolve(uniqueFileName(takenNames(directory), candidate))
    }

    /**
     * Writes the buffer and optionally replays it. Unlike the single `recorded.flow` this replaces,
     * nothing is ever clobbered: a buffer with no file of its own gets a name derived from its
     * `name` directive, de-duplicated against what is already there.
     */
    fun saveTest(runAfterSave: Boolean, saveAs: Boolean = false) {
        val device = deviceState.selected
        if (authoring.source.isBlank()) {
            authoring.status = "Create or record a test first"
            return
        }
        if (runAfterSave && device == null) {
            authoring.status = "Connect a device before running the test"
            return
        }
        val kind = authoring.kind
        scope.launch {
            deviceState.busy = true
            val existing = authoring.currentFile
            val target = if (existing != null && !saveAs) existing
                else deriveTarget(authoring.source, kind).toAbsolutePath()
            val contents = flowFileContents(authoring.source)
            authoring.status = if (runAfterSave) "Running…" else "Saving…"

            runCatching {
                withContext(Dispatchers.IO) {
                    Files.createDirectories(target.parent)
                    Files.writeString(target, contents, StandardCharsets.UTF_8)
                    if (!runAfterSave) {
                        return@withContext SaveOutcome(target, true, null, null, null)
                    }
                    if (kind == EditorKind.Suite) {
                        val suite = FlowSuiteParser.parse(target)
                        val runner = FlowSuiteRunner(
                            adb, device!!, suite, target, projectState.project,
                            projectState.reportsDirectory,
                        )
                        val passed = runner.run()
                        val report = RunReport.read(runner.root()).orElse(null)
                        SaveOutcome(
                            target,
                            passed,
                            runner.root(),
                            suiteLabel(suite, passed),
                            regressionCompletionNotice(
                                suite.name(),
                                passed,
                                report?.total() ?: suite.entries().size,
                                report?.passed() ?: if (passed) suite.entries().size else 0,
                                report?.failed() ?: if (passed) 0 else 1,
                            ),
                        )
                    } else {
                        val parsed = FlowTestParser.parse(target)
                        val runner = FlowTestRunner(
                            adb, device!!, parsed, projectState.reportsDirectory,
                        )
                        val passed = runner.run()
                        val steps = parsed.steps().size
                        SaveOutcome(
                            target, passed, runner.root(),
                            if (passed) "PASSED $steps/$steps" else "FAILED",
                            testCompletionNotice(parsed.name(), passed, steps),
                            runner.webChanges(),
                        )
                    }
                }
            }.onSuccess { outcome ->
                authoring.markSaved(outcome.target, contents)
                authoring.status = if (runAfterSave) {
                    "${if (outcome.passed) "Passed" else "Failed"} · " +
                        shown(outcome.reportRoot ?: outcome.target)
                } else "Saved · ${shown(outcome.target)}"
                if (runAfterSave) {
                    authoring.lastRun = FlowRunResult(
                        passed = outcome.passed,
                        label = outcome.label ?: if (outcome.passed) "PASSED" else "FAILED",
                        reportRoot = outcome.reportRoot?.toString(),
                    )
                    outcome.completionNotice?.let(DesktopNotifications::show)
                }
                addEvent(
                    if (outcome.passed) "✓" else "×",
                    if (runAfterSave) {
                        "${if (kind == EditorKind.Suite) "Suite" else "Test"} " +
                            if (outcome.passed) "passed" else "failed"
                    } else "Saved ${outcome.target.fileName}",
                    authoring.status,
                    severity = if (outcome.passed) Severity.Normal else Severity.Error,
                )
                refreshProjectListing()
                if (runAfterSave && contents.lineSequence().any { " web-" in it }) {
                    refreshWebViewInspection()
                }
                outcome.webChanges.forEach { change ->
                    addEvent("◇", "WebView state changed", change, channel = Channel.Network)
                }
            }.onFailure { failure ->
                authoring.status = failure.message ?: "Test operation failed"
                if (runAfterSave) {
                    authoring.lastRun = FlowRunResult(false, "FAILED", null)
                    DesktopNotifications.show(
                        runErrorNotice(
                            testNameFromSource(contents)
                                ?: target.fileName.toString().substringBeforeLast('.'),
                            regression = kind == EditorKind.Suite,
                        ),
                    )
                }
                addEvent("×", "Test error", authoring.status, severity = Severity.Error)
            }
            deviceState.busy = false
        }
    }

    /** Starts a new, empty buffer of [kind]. Nothing is written until it is saved. */
    fun newFile(kind: EditorKind) {
        scope.launch {
            // Persist whatever is open first, including a buffer that has never been saved —
            // otherwise New test would silently discard it.
            if (authoring.source.isNotBlank() &&
                (authoring.dirty || authoring.currentFile == null)
            ) {
                val target = authoring.currentFile
                    ?: deriveTarget(authoring.source, authoring.kind)
                withContext(Dispatchers.IO) {
                    runCatching {
                        Files.createDirectories(target.parent)
                        Files.writeString(target, flowFileContents(authoring.source),
                            StandardCharsets.UTF_8)
                    }
                }
            }
            val template = if (kind == EditorKind.Suite) {
                suiteTemplate(projectState.label)
            } else {
                val defaults = projectState.manifest?.defaults()
                flowTemplate(
                    "New test",
                    defaults?.packageName() ?: deviceState.packageName.trim().ifEmpty { "com.example.debug" },
                    defaults?.mode() ?: deviceState.captureMode,
                    defaults?.timeoutMillis() ?: DefaultTimeoutMillis,
                )
            }
            authoring.adopt(null, template)
            authoring.lastRun = null
            authoring.status = "New ${if (kind == EditorKind.Suite) "suite" else "test"} · unsaved"
            shell.surface = WorkSurface.Flow
        }
    }

    /**
     * Repoints every suite that named [from] at [to], and returns the suites it changed.
     *
     * A rename that leaves suites pointing at a file that no longer exists produces a project that
     * fails `flowscope project validate` — so the rename completes the job rather than reporting a
     * mess for the user to clean up by hand. Only `test` lines are touched; see
     * [rewriteSuiteReference].
     */
    fun repointSuites(from: Path, to: Path): List<String> {
        val project = projectState.project ?: return emptyList()
        return runCatching {
            val flows = project.flowsDir()
            val oldReference = flows.relativize(from).toString().replace('\\', '/')
            val newReference = flows.relativize(to).toString().replace('\\', '/')
            project.listSuites().mapNotNull { suite ->
                runCatching {
                    val source = Files.readString(suite, StandardCharsets.UTF_8)
                    if (!suiteReferences(source, oldReference)) return@runCatching null
                    val rewritten = rewriteSuiteReference(source, oldReference, newReference)
                    if (rewritten == source) return@runCatching null
                    Files.writeString(suite, rewritten, StandardCharsets.UTF_8)
                    project.relativize(suite)
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun renameOpenFile(rawName: String) {
        val current = authoring.currentFile
        if (current == null) {
            authoring.status = "Save this file before renaming it"
            return
        }
        val requested = rawName.trim()
        if (requested.isEmpty()) return
        val extension = if (authoring.kind == EditorKind.Suite) "suite" else "flow"
        val fileName = if (requested.endsWith(".$extension")) requested
            else suggestedFileName(requested, extension)
        val target = current.resolveSibling(fileName)
        if (target == current) return

        scope.launch {
            val moved = withContext(Dispatchers.IO) {
                runCatching {
                    if (Files.exists(target)) {
                        throw IllegalStateException("${target.fileName} already exists")
                    }
                    Files.move(current, target, StandardCopyOption.ATOMIC_MOVE)
                }
            }
            moved.onSuccess {
                authoring.currentFile = target
                // Suites are repointed in the same action: a half-done rename leaves the project
                // failing `flowscope project validate`.
                val repointed = withContext(Dispatchers.IO) { repointSuites(current, target) }
                authoring.status = if (repointed.isEmpty()) {
                    "Renamed · ${shown(target)}"
                } else {
                    "Renamed · ${repointed.size} suite(s) repointed"
                }
                refreshProjectListing()
                if (repointed.isNotEmpty()) {
                    addEvent(
                        "✎", "Suite references updated",
                        repointed.joinToString(", ") +
                            " · ${current.fileName} → ${target.fileName}",
                    )
                }
            }.onFailure { failure ->
                authoring.status = failure.message ?: "Could not rename that file"
                addEvent("×", "Rename failed", authoring.status, severity = Severity.Error)
            }
        }
    }

    /** Opens a suite into the editor, then replays it, so the run is always of visible text. */
    fun runSuiteFile(path: Path) {
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { Files.readString(path, StandardCharsets.UTF_8) }
            }
            read.onSuccess { contents ->
                authoring.adopt(path, contents)
                authoring.lastRun = null
                shell.surface = WorkSurface.Flow
                saveTest(runAfterSave = true)
            }.onFailure { failure ->
                authoring.status = failure.message ?: "Could not open that suite"
                addEvent("×", "Suite not opened", authoring.status, severity = Severity.Error)
            }
        }
    }

    // Session restore: reopen the last project and the file that was open in it.
    //
    // Keyed on Unit and loading the recents itself, deliberately. Keying on the recents list meant
    // the effect fired once while the list was still empty, disabled itself, and then refused to
    // run when the real list arrived — so the last project was never reopened. Each step is
    // awaited, so `restoring` only clears once the project really is open.
    LaunchedEffect(Unit) {
        val recents = withContext(Dispatchers.IO) { Preferences.loadRecentProjects() }
        projectState.recent = recents
        val mostRecent = recents.firstOrNull()
        if (mostRecent == null) {
            refreshProjectListing()
            projectState.restoring = false
            return@LaunchedEffect
        }
        openProjectAwaiting(mostRecent.root)
        mostRecent.lastOpenFile?.let { relative ->
            val file = mostRecent.root.resolve(relative)
            if (withContext(Dispatchers.IO) { Files.isRegularFile(file) }) openFileAwaiting(file)
        }
        projectState.restoring = false
    }

    fun setShowDisplay(show: Boolean) {
        deviceState.showDisplay = show
        // Dropping the frames now keeps a stale image from lingering behind the hidden column.
        if (!show) {
            deviceState.embeddedFrame = null
            deviceState.embeddedStatus = "Display hidden · actions still run through ADB"
        }
        scope.launch(Dispatchers.IO) { Preferences.saveShowDevice(show) }
    }

    fun toggleDisplay() = setShowDisplay(!deviceState.showDisplay)

    fun collectPerformance() {
        val device = deviceState.selected
        if (device == null || deviceState.packageName.isBlank()) {
            addEvent(
                "!", "Performance snapshot skipped",
                "Select a device and enter an application ID first.",
                severity = Severity.Warning,
            )
            return
        }
        scope.launch {
            deviceState.busy = true

            runCatching {
                withContext(Dispatchers.IO) {
                    val memory = adb.run(listOf("-s", device.serial(), "shell", "dumpsys", "meminfo", deviceState.packageName.trim()))
                    val graphics = adb.run(listOf("-s", device.serial(), "shell", "dumpsys", "gfxinfo", deviceState.packageName.trim()))
                    "MEMORY\n${memory.stdout.take(16_000)}\n\nRENDERING\n${graphics.stdout.take(16_000)}"
                }
            }.onSuccess { raw ->
                session.previousPerformance = session.performance
                session.performance = parsePerformance(raw)
                addEvent("↗", "Performance snapshot", deviceState.packageName.trim())
            }.onFailure {
                addEvent(
                    "×", "Performance snapshot failed",
                    it.message ?: "Performance collection failed",
                    severity = Severity.Error,
                )
            }
            deviceState.busy = false
        }
    }

    fun toggleMirror() {
        val current = deviceState.mirrorSession
        if (current != null) {
            scope.launch {
                withContext(Dispatchers.IO) { current.close() }
                if (deviceState.mirrorSession === current) deviceState.mirrorSession = null
                deviceState.mirrorStatus = "Interactive mirror stopped"
                addEvent("□", "Interactive mirror stopped", "The inline evidence preview remains active")
            }
            return
        }

        val device = deviceState.selected
        when {
            device == null -> deviceState.mirrorStatus = "Connect and select an Android device first"
            !scrcpy.available() -> deviceState.mirrorStatus = "Install scrcpy or set SCRCPY; screenshot fallback is still active"
            else -> scope.launch {
                deviceState.mirrorStatus = "Opening scrcpy for ${device.displayName()}…"
                runCatching { withContext(Dispatchers.IO) { scrcpy.start(device.serial(), false) } }
                    .onSuccess { activeRecording ->
                        deviceState.mirrorSession = activeRecording
                        deviceState.mirrorStatus = "Interactive mirror open · input enabled"
                        addEvent("▣", "Interactive mirror opened", "scrcpy · ${device.serial()}")
                        scope.launch(Dispatchers.IO) {
                            val exitCode = activeRecording.waitFor()
                            withContext(Dispatchers.Main) {
                                if (deviceState.mirrorSession === activeRecording) {
                                    deviceState.mirrorSession = null
                                    deviceState.mirrorStatus = if (exitCode == 0) {
                                        "Interactive mirror closed"
                                    } else "scrcpy exited with code $exitCode · screenshot fallback active"
                                    addEvent("□", "Interactive mirror closed", "scrcpy exit code $exitCode")
                                }
                            }
                        }
                    }
                    .onFailure {
                        deviceState.mirrorStatus = it.message ?: "Could not open scrcpy"
                        addEvent("×", "Mirror failed", deviceState.mirrorStatus, severity = Severity.Error)
                    }
            }
        }
    }

    /**
     * Prepares the editor for a new recording: persists whatever was open, then writes the template
     * for the new test and points the buffer at it. Never refuses to start, and never loses work.
     */
    suspend fun startRecordingBuffer() {
        // Record is reachable before the restore finishes; without this the first test of a
        // session lands in ~/.flowscope instead of the project that is still being reopened.
        awaitProjectRestore()
        val previous = authoring.currentFile
        if (authoring.source.isNotBlank()) {
            val target = previous ?: deriveTarget(authoring.source, authoring.kind)
            if (authoring.dirty || previous == null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        Files.createDirectories(target.parent)
                        Files.writeString(target, flowFileContents(authoring.source),
                            StandardCharsets.UTF_8)
                    }
                }
            }
        }

        val packageName = deviceState.packageName.trim()
        val defaults = projectState.manifest?.defaults()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val testName = "Recorded ${packageName.substringAfterLast('.')} $stamp"
        val template = flowTemplate(
            testName,
            packageName,
            deviceState.captureMode,
            defaults?.timeoutMillis() ?: DefaultTimeoutMillis,
        )

        val directory = projectState.testsDirectory
        val fileName = uniqueFileName(
            takenNames(directory),
            suggestedFileName("recording $stamp", "flow"),
        )
        val target = directory.resolve(fileName)
        val written = withContext(Dispatchers.IO) {
            runCatching {
                Files.createDirectories(directory)
                Files.writeString(target, template, StandardCharsets.UTF_8)
                target
            }
        }
        authoring.adopt(written.getOrNull(), template)
        authoring.lastRun = null
        authoring.status = written.fold(
            onSuccess = { "Recording into ${shown(it)}" },
            onFailure = { it.message ?: "Recording into an unsaved buffer" },
        )
        refreshProjectListing()
    }

    fun toggleRecording() {
        if (session.recording != null) {
            val current = session.recording
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { current?.close() } }
                    .onSuccess {
                        addEvent("■", "Recording stopped", current?.root()?.toString() ?: "Session saved")
                        session.status = "Session saved"
                        current?.root()?.let { refreshSessionArtifacts(it) }
                    }
                    .onFailure { session.status = it.message ?: "Could not stop recording" }
                session.recording = null
            }
            return
        }

        val device = deviceState.selected
        when {
            device == null -> session.status = "Connect and select an Android device first"
            deviceState.packageName.isBlank() -> session.status = "Enter the app package name"
            else -> scope.launch {
                deviceState.busy = true
                session.status = "Starting capture…"
                // A recording used to overwrite the buffer outright. With real files behind it that
                // would destroy work, so unsaved work is written first and the new recording gets
                // its own file on disk immediately — visible in the rail from the first second, and
                // recoverable if the IDE dies mid-session.
                startRecordingBuffer()
                runCatching {
                    withContext(Dispatchers.IO) {
                        SessionRecorder(
                            adb,
                            device,
                            deviceState.packageName.trim(),
                            FlowScopeHome.resolve("sessions"),
                            deviceState.captureMode,
                        ).observe(::observeRecorderLine).also { it.start() }
                    }
                }.onSuccess { activeRecording ->
                    session.recording = activeRecording
                    session.lastRoot = activeRecording.root()
                    session.status = "Recording live"
                    refreshSessionArtifacts(activeRecording.root(), preferLatest = true)
                    addEvent(
                        "●",
                        "Recording started",
                        "${device.displayName()} · ${deviceState.packageName.trim()} · ${deviceState.captureMode.cliName()}",
                    )
                }.onFailure {
                    session.status = it.message ?: "Could not start recording"
                    addEvent("×", "Recording failed", session.status, severity = Severity.Error)
                }
                deviceState.busy = false
            }
        }
    }

    LaunchedEffect(deviceState.tapFeedback?.id) {
        if (deviceState.tapFeedback == null) return@LaunchedEffect
        delay(TapFeedbackMillis.toLong())
        deviceState.tapFeedback = null
    }


    fun openSessionFolder() {
        session.lastRoot?.let { root ->
            scope.launch(Dispatchers.IO) {
                revealInFileManager(root)
            }
        }
    }

    fun captureCheckpoint() {
        val activeRecording = session.recording ?: return
        scope.launch {
            session.status = "Capturing checkpoint…"
            runCatching { withContext(Dispatchers.IO) { activeRecording.captureCheckpoint("manual-ui") } }
                .onSuccess {
                    session.status = "Checkpoint captured"
                    appendStep("step screenshot name=checkpoint-${activeRecording.checkpointCount() - 1}")
                    refreshSessionArtifacts(activeRecording.root(), preferLatest = true)
                    addEvent("\u25C7", "Checkpoint captured", "Screenshot, hierarchy and window state")
                }
                .onFailure { session.status = it.message ?: "Checkpoint failed" }
        }
    }

    // Subsystem state is derived once and rendered as dots in the active recording bar.
    val health: Map<Subsystem, HealthEntry> = buildMap {
        put(
            Subsystem.Adb,
            if (deviceState.devices.isEmpty()) {
                HealthEntry(HealthLevel.Down, "No authorized device detected", "Rescan") {
                    scope.launch { refreshDevices() }
                }
            } else {
                HealthEntry(HealthLevel.Ok, "${deviceState.devices.size} device(s) over ADB")
            },
        )
        put(
            Subsystem.Device,
            when {
                deviceState.selected == null -> HealthEntry(HealthLevel.Down, "No device selected")
                !deviceState.packageInstalled -> HealthEntry(
                    HealthLevel.Down,
                    "${deviceState.packageName} is not installed on this device",
                    "Clear target",
                ) { deviceState.packageName = "" }
                else -> HealthEntry(
                    HealthLevel.Ok,
                    "${deviceState.selected?.displayName()} · ${deviceState.selected?.serial()}",
                )
            },
        )
        put(
            Subsystem.Stream,
            when {
                deviceState.streaming -> HealthEntry(HealthLevel.Ok, "Embedded scrcpy · interactive")
                deviceState.mirroring -> HealthEntry(HealthLevel.Degraded, "External mirror only · ${deviceState.mirrorStatus}")
                else -> HealthEntry(HealthLevel.Degraded, deviceState.embeddedStatus, "Open external mirror", ::toggleMirror)
            },
        )
        put(
            Subsystem.Agent,
            when {
                !deviceState.debuggable -> HealthEntry(
                    HealthLevel.Down,
                    "${deviceState.packageName} is not debuggable · capture will fail",
                )
                deviceState.captureMode == CaptureMode.INSTRUMENTED && deviceState.agentAvailable ->
                    HealthEntry(HealthLevel.Ok, "Instrumented · agent events seen")
                deviceState.captureMode == CaptureMode.INSTRUMENTED -> HealthEntry(
                    HealthLevel.Degraded,
                    "Instrumented selected but no agent events seen yet",
                )
                deviceState.agentAvailable -> HealthEntry(
                    HealthLevel.Degraded,
                    "Agent events available · instrumented mode would capture more",
                )
                else -> HealthEntry(HealthLevel.NotApplicable, "Zero-code · no agent required")
            },
        )
        put(
            Subsystem.Session,
            when {
                session.isRecording && deviceState.interaction == InteractionMode.Mirror ->
                    HealthEntry(
                        HealthLevel.Degraded,
                        "Recording in mirror mode · taps are logged but generate no steps",
                        "Switch to inspect",
                    ) { deviceState.interaction = InteractionMode.Inspect }
                session.isRecording -> HealthEntry(HealthLevel.Ok, "Recording · ${session.evidence.size} checkpoint(s)")
                session.lastRoot != null -> HealthEntry(
                    HealthLevel.Ok,
                    "Saved · ${session.lastRoot}",
                    "Open folder",
                    ::openSessionFolder,
                )
                else -> HealthEntry(HealthLevel.NotApplicable, session.status)
            },
        )
    }

    // Quality of life: if nothing is typed, adopt whatever app is actually on screen. Never
    // overwrite a package the user chose, and never while recording. A package this loop adopted
    // keeps following the foreground app until the user edits it, so it cannot go stale.
    LaunchedEffect(deviceState.selected?.serial()) {
        val serial = deviceState.selected?.serial() ?: return@LaunchedEffect
        while (isActive) {
            val following = deviceState.packageName.isEmpty() ||
                deviceState.packageName == deviceState.autoDetectedPackage
            if (following && session.recording == null) {
                val foreground = runCatching {
                    withContext(Dispatchers.IO) { adb.foregroundPackage(serial) }
                }.getOrNull()
                // Launchers and system UI are never the app under test.
                if (foreground != null && !isSystemShell(foreground) &&
                    foreground in deviceState.installedPackages &&
                    foreground != deviceState.packageName
                ) {
                    deviceState.packageName = foreground
                    deviceState.autoDetectedPackage = foreground
                    addEvent("◎", "Detected foreground app", foreground, channel = Channel.Lifecycle)
                }
            }
            delay(2_000)
        }
    }

    // Probe the chosen package: debuggable (required at all) and whether the debug agent is
    // actually emitting events (the only honest evidence that instrumented mode will work).
    LaunchedEffect(deviceState.packageName, deviceState.selected?.serial()) {
        val serial = deviceState.selected?.serial()
        val target = deviceState.packageName.trim()
        if (serial == null || target.isEmpty()) {
            deviceState.agentAvailable = false
            deviceState.debuggable = true
            return@LaunchedEffect
        }
        delay(300)
        val debuggable = runCatching {
            withContext(Dispatchers.IO) { adb.isDebuggable(serial, target) }
        }.getOrDefault(true)
        val agent = runCatching {
            withContext(Dispatchers.IO) { adb.hasAgentEvidence(serial, target) }
        }.getOrDefault(false)
        deviceState.debuggable = debuggable
        deviceState.agentAvailable = agent
        if (agent && !deviceState.captureModeChosen &&
            deviceState.captureMode != CaptureMode.INSTRUMENTED
        ) {
            deviceState.captureMode = CaptureMode.INSTRUMENTED
            addEvent(
                "◆", "Instrumented agent detected",
                "$target is emitting FlowScopeAgent events · switched to instrumented",
                channel = Channel.Session,
            )
        }
    }

    LaunchedEffect(deviceState.lostSerial) {
        val lost = deviceState.lostSerial ?: return@LaunchedEffect
        deviceState.lostSerial = null
        val current = session.recording ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { current.close() } }
        session.recording = null
        session.status = "Device disconnected · session saved"
        addEvent(
            "×", "Device disconnected",
            "$lost went away mid-recording · session saved to ${current.root()}",
            channel = Channel.Session, severity = Severity.Error,
        )
        current.root().let { refreshSessionArtifacts(it) }
    }

    // Owns the embedded stream end to end: first connect and every reconnect. Keeping this in one
    // place matters — when a separate initial start also existed, a slow first connect (push +
    // server boot + two socket connects) outlasted this loop's check and both started a server,
    // leaving two scrcpy servers contending for one device.
    LaunchedEffect(deviceState.selected?.serial(), deviceState.showDisplay) {
        if (!deviceState.showDisplay) return@LaunchedEffect
        val serial = deviceState.selected?.serial() ?: return@LaunchedEffect
        while (isActive) {
            if (deviceState.streaming) {
                deviceState.streamAttempts = 0
                delay(2_000)
                continue
            }
            if (!embeddedScrcpy.available()) {
                deviceState.embeddedStatus =
                    "${embeddedScrcpy.availabilitySummary()} · screenshot fallback active"
                delay(5_000)
                continue
            }
            val attempt = deviceState.streamAttempts
            if (attempt > 0) {
                // Exponential backoff, capped — a device that is simply busy must not be hammered.
                delay((1L shl attempt.coerceAtMost(3)) * 2_000L)
                if (deviceState.selected?.serial() != serial || deviceState.streaming) continue
            }
            deviceState.embeddedStatus =
                if (attempt == 0) "Starting embedded scrcpy…" else "Reconnecting embedded stream…"
            runCatching {
                withContext(Dispatchers.IO) {
                    embeddedScrcpy.start(serial) { frame -> embeddedFrames.trySend(frame) }
                }
            }.onSuccess { session ->
                deviceState.embeddedSession = session
                deviceState.embeddedStatus = "Embedded scrcpy · interactive"
                if (attempt > 0) addEvent("⇄", "Stream reconnected", serial, channel = Channel.Session)
                deviceState.streamAttempts = 0
            }.onFailure {
                deviceState.streamAttempts = attempt + 1
                deviceState.embeddedStatus =
                    "Stream unavailable · screenshot fallback (retry ${attempt + 1})"
            }
        }
    }

    // 5. Recall the package last used against this device before falling back to the foreground app.
    LaunchedEffect(deviceState.selected?.serial(), deviceState.installedPackages) {
        val serial = deviceState.selected?.serial() ?: return@LaunchedEffect
        if (deviceState.packageName.isNotEmpty() || deviceState.installedPackages.isEmpty()) {
            return@LaunchedEffect
        }
        val remembered = deviceState.recentPackages[serial]
        if (remembered != null && remembered in deviceState.installedPackages) {
            deviceState.packageName = remembered
            addEvent("◎", "Recalled last target", "$remembered on $serial", channel = Channel.Session)
        }
    }

    fun exportTimeline(toClipboard: Boolean) {
        val lines = timeline.events.asReversed().joinToString("\n") { it.toJsonLine() }
        if (lines.isBlank()) {
            session.status = "No events to export"
            return
        }
        if (toClipboard) {
            copyToClipboard(lines)
            session.status = "Timeline copied (${timeline.events.size} events)"
            return
        }
        scope.launch(Dispatchers.IO) {
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            val written = Preferences.writeExport("timeline-$stamp.jsonl", lines + "\n")
            withContext(Dispatchers.Main) {
                session.status = written?.let { "Timeline exported · $it" } ?: "Export failed"
                written?.let {
                    addEvent("↗", "Timeline exported", it.toString(), channel = Channel.Session)
                }
            }
        }
    }

    // design.md §6.1 — one table drives both the keymap and the palette, so they cannot drift.
    val commands = listOf(
        Command("record", if (session.recording != null) "Stop recording" else "Start recording",
            shortcutFor("record"),
            session.recording != null || (deviceState.selected != null &&
                deviceState.packageName.isNotBlank() && deviceState.packageInstalled),
            ::toggleRecording),
        Command("checkpoint", "Capture checkpoint", shortcutFor("checkpoint"),
            session.recording != null, ::captureCheckpoint),
        Command(
            "save",
            // Disabled (dimmed) already says there is nothing to save; the label stays stable.
            "Save flow",
            shortcutFor("save"),
            authoring.source.isNotBlank() && authoring.dirty,
        ) { saveTest(false) },
        Command("run", "Run flow on device", shortcutFor("run"),
            authoring.source.isNotBlank() && deviceState.selected != null && session.recording == null) { saveTest(true) },
        Command("file.saveAs", "Save flow as a new file", shortcutFor("file.saveAs"),
            authoring.source.isNotBlank()) {
            saveTest(runAfterSave = false, saveAs = true)
        },
        Command("file.new", "New test", shortcutFor("file.new"), true) { newFile(EditorKind.Flow) },
        Command("file.newSuite", "New suite", shortcutFor("file.newSuite"), true) { newFile(EditorKind.Suite) },
        Command("file.rename", "Rename the open file…", "", authoring.currentFile != null) {
            shell.selection = Selection.RenameFile(authoring.fileName.orEmpty())
            shell.drawerOpen = true
        },
        Command("run.suite", "Run suite on device", shortcutFor("run.suite"),
            authoring.kind == EditorKind.Suite && deviceState.selected != null &&
                session.recording == null) { saveTest(true) },
        Command("project.open", "Open project…", shortcutFor("project.open"), true) {
            // Must stay on the EDT: Compose Desktop's main dispatcher *is* the EDT, and an AWT
            // modal dialog moved onto Dispatchers.IO never paints.
            chooseDirectory(window, "Open FlowScope project")?.let(::openOrInitialise)
        },
        Command("project.new", "New project…", "", true) {
            chooseDirectory(window, "Create a FlowScope project")?.let { createProject(it, null) }
        },
        Command("project.settings", "Project settings", shortcutFor("project.settings"),
            projectState.isOpen) {
            shell.selection = Selection.ProjectSettings
            shell.drawerOpen = true
        },
        Command("project.reload", "Reload project", "", projectState.isOpen, ::reloadProject),
        Command("project.reveal", "Reveal project folder", "", true, ::revealProjectFolder),
        Command("project.close", "Close project", "", projectState.isOpen, ::closeProject),
        Command("project.manifest", "Open flowscope.json", "", projectState.isOpen) {
            val manifestPath = projectState.project?.manifestPath() ?: return@Command
            scope.launch(Dispatchers.IO) { openWithDefaultApplication(manifestPath) }
        },
        Command("rail.toggle", if (projectState.railOpen) "Hide file browser" else "Show file browser",
            shortcutFor("rail.toggle"), true) { projectState.railOpen = !projectState.railOpen },
        Command("surface.timeline", "Go to Timeline", shortcutFor("surface.timeline"), true) { shell.surface = WorkSurface.Timeline },
        Command("surface.flow", "Go to Flow", shortcutFor("surface.flow"), true) { shell.surface = WorkSurface.Flow },
        Command("surface.evidence", "Go to Evidence", shortcutFor("surface.evidence"), true) { shell.surface = WorkSurface.Evidence },
        Command("surface.tree", "Go to Tree", shortcutFor("surface.tree"), true) { shell.surface = WorkSurface.Tree },
        Command("surface.perf", "Go to Performance", shortcutFor("surface.perf"), true) { shell.surface = WorkSurface.Perf },
        Command(
            "surface.settings",
            if (shell.surface == WorkSurface.Settings) "Close Settings" else "Open Settings",
            "",
            true,
            shell::toggleSettings,
        ),
        Command(
            "interaction",
            if (deviceState.interaction == InteractionMode.Inspect) {
                "Switch to mirror mode (raw passthrough)"
            } else "Switch to inspect mode (resolve selectors)",
            shortcutFor("interaction"),
            deviceState.selected != null,
        ) {
            deviceState.interaction = if (deviceState.interaction == InteractionMode.Inspect) {
                InteractionMode.Mirror
            } else InteractionMode.Inspect
        },
        Command("collapse.drawer", if (shell.drawerOpen) "Hide detail drawer" else "Show detail drawer",
            shortcutFor("collapse.drawer"),
            true) { shell.drawerOpen = !shell.drawerOpen },
        Command("display.toggle", if (deviceState.showDisplay) "Hide device display" else "Show device display",
            "", true, ::toggleDisplay),
        Command("perf.collect", "Collect performance snapshot", "", !deviceState.busy, ::collectPerformance),
        Command("open.folder", "Open activeRecording folder", "", session.lastRoot != null, ::openSessionFolder),
        Command("mirror", "Toggle external mirror", "", deviceState.selected != null, ::toggleMirror),
        Command("export.file", "Export timeline as JSONL", "", timeline.events.isNotEmpty()) {
            exportTimeline(toClipboard = false)
        },
        Command("export.clipboard", "Copy timeline to clipboard", "", timeline.events.isNotEmpty()) {
            exportTimeline(toClipboard = true)
        },
    )

    val flowRail = FlowRail(
        open = projectState.railOpen,
        projectOpen = projectState.isOpen,
        tests = projectState.tests,
        suites = projectState.suites,
        runs = projectState.runs,
        scratch = projectState.scratch,
        openFile = authoring.currentFile,
        onToggle = { projectState.railOpen = !projectState.railOpen },
        onOpen = ::openFile,
        onNewTest = { newFile(EditorKind.Flow) },
        onNewSuite = { newFile(EditorKind.Suite) },
        onRunSuite = ::runSuiteFile,
        onOpenProject = { chooseDirectory(window, "Open FlowScope project")?.let(::openOrInitialise) },
        onCreateProject = {
            chooseDirectory(window, "Create a FlowScope project")?.let { createProject(it, null) }
        },
        onOpenRun = { directory ->
            scope.launch(Dispatchers.IO) {
                val report = listOf(directory.resolve("suite-report.html"), directory.resolve("report.html"))
                    .firstOrNull { Files.isRegularFile(it) }
                if (report != null) openWithDefaultApplication(report)
                else revealInFileManager(directory)
            }
        },
        onRevealRun = { directory ->
            scope.launch(Dispatchers.IO) { revealInFileManager(directory) }
        },
    )

    val projectChrome = ProjectChrome(
        label = projectState.label,
        openName = authoring.fileName,
        dirty = authoring.dirty,
        isOpen = projectState.isOpen,
        recents = projectState.recent,
        onOpen = { chooseDirectory(window, "Open FlowScope project")?.let(::openOrInitialise) },
        onCreate = { chooseDirectory(window, "Create a FlowScope project")?.let { createProject(it, null) } },
        onOpenRecent = ::openProject,
        onOpenPath = { raw -> openOrInitialise(expandUserPath(raw)) },
        onSettings = {
            shell.selection = Selection.ProjectSettings
            shell.drawerOpen = true
        },
        onManifest = {
            projectState.project?.manifestPath()?.let { manifestPath ->
                scope.launch(Dispatchers.IO) { openWithDefaultApplication(manifestPath) }
            }
        },
        onReload = ::reloadProject,
        onReveal = ::revealProjectFolder,
        onClose = ::closeProject,
    )

    // The key handler is published once but must never act on a stale command table: `commands`
    // is rebuilt whenever enablement changes, and a key pressed between a state change and the
    // next publish would otherwise be judged against the previous composition's list — which is
    // how ⌘R came to be ignored while a device was plainly connected.
    val latestCommands by rememberUpdatedState(commands)

    fun dispatch(id: String): Boolean {
        if (id == "palette") {
            shell.paletteOpen = !shell.paletteOpen
            return true
        }
        val command = latestCommands.firstOrNull { it.id == id } ?: return false
        if (!command.enabled) return true
        command.run()
        return true
    }

    /** Stable across recompositions; everything it touches is read when the key is pressed. */
    val handleShortcut: (KeyEvent) -> Boolean = handler@{ event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && shell.paletteOpen) {
            shell.paletteOpen = false
            return@handler true
        }
        commandIdFor(event)?.let(::dispatch) ?: false
    }

    DisposableEffect(Unit) {
        shortcuts.value = handleShortcut
        onDispose { shortcuts.value = { false } }
    }

    CompositionLocalProvider(LocalReduceMotion provides shell.reduceMotion) {
    Surface(
        Modifier.fillMaxSize(),
        color = AppBackground,
    ) {
        Column {
            SessionBar(
                devices = deviceState.devices,
                selected = deviceState.selected,
                onDeviceSelected = { deviceState.selected = it },
                onRefresh = { scope.launch { refreshDevices(); refreshAvds() } },
                avds = deviceState.avds,
                emulatorAvailable = deviceState.emulatorAvailable,
                onStartAvd = ::startAvd,
                onStopAvd = ::stopAvd,
                packageName = deviceState.packageName,
                onPackageNameChanged = { deviceState.packageName = it },
                installedPackages = deviceState.installedPackages,
                favourites = deviceState.favourites,
                onToggleFavourite = { candidate ->
                    val next = if (candidate in deviceState.favourites) {
                        deviceState.favourites - candidate
                    } else deviceState.favourites + candidate
                    deviceState.favourites = next
                    scope.launch(Dispatchers.IO) { Preferences.saveFavourites(next) }
                },
                captureMode = deviceState.captureMode,
                onCaptureModeChanged = {
                    deviceState.captureMode = it
                    deviceState.captureModeChosen = true
                },
                recording = session.isRecording,
                recordingSeconds = session.seconds,
                busy = deviceState.busy,
                health = health,
                packageInstalled = deviceState.packageInstalled,
                checkpointCount = session.evidence.size,
                settingsSelected = shell.surface == WorkSurface.Settings,
                project = projectChrome,
                onRecord = ::toggleRecording,
                onCheckpoint = ::captureCheckpoint,
                onSettings = shell::toggleSettings,
            )
            HorizontalDivider(color = Border)
            BoxWithConstraints(Modifier.weight(1f)) {
                if (shell.surface == WorkSurface.Settings) {
                    Column(Modifier.fillMaxSize()) {
                        SurfaceTabs(
                            active = shell.surface,
                            onSelect = { shell.surface = it },
                            stepCount = authoring.stepCount,
                            evidenceCount = session.evidence.size,
                            eventCount = timeline.events.size,
                            drawerOpen = false,
                            onToggleDrawer = {},
                        )
                        HorizontalDivider(color = Border)
                        SettingsSurface(
                            commands = commands,
                            showDisplay = deviceState.showDisplay,
                            onShowDisplayChanged = ::setShowDisplay,
                            mode = themeMode,
                            onModeChanged = { mode ->
                                onThemeModeChanged(mode)
                                scope.launch(Dispatchers.IO) { Preferences.saveThemeMode(mode) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                // design.md §4.3 — the device column is sized first; the work surface takes the
                // remainder and never drops below its minimum.
                val metrics = shellMetrics(maxWidth, maxHeight, deviceState.aspect, shell.drawerOpen)
                Row(Modifier.fillMaxSize()) {
                    // design.md §4.2 — present on every surface, not just the recorder page.
                    if (!deviceState.showDisplay) {
                        HiddenDeviceStrip(
                            recording = session.isRecording,
                            onShow = ::toggleDisplay,
                        )
                    } else if (metrics.deviceCollapsed) {
                        CollapsedDeviceStrip(
                            liveImage = deviceState.liveImage,
                            recording = session.isRecording,
                            onExpand = { shell.drawerOpen = false },
                        )
                    } else {
                        DeviceColumn(
                        modifier = Modifier.width(metrics.deviceWidth).fillMaxHeight(),
                        liveImage = deviceState.liveImage,
                        aspect = deviceState.aspect,
                        device = deviceState.selected,
                        recording = session.isRecording,
                        streaming = deviceState.streaming,
                        feedback = deviceState.tapFeedback,
                        interaction = deviceState.interaction,
                        onInteractionChange = { deviceState.interaction = it },
                        mirrorAvailable = scrcpy.available(),
                        mirrorRunning = deviceState.mirroring,
                        onDeviceTap = { normalized ->
                            val device = deviceState.selected ?: return@DeviceColumn
                            // Input must use the same coordinate space as the image on screen.
                            // scrcpy rejects positional events whose declared frame size differs
                            // from the active video stream, so never prefer the fallback screenshot
                            // while an embedded frame is being rendered.
                            val reference = deviceState.liveImage ?: return@DeviceColumn
                            val x = (normalized.x * reference.width).toInt().coerceIn(0, reference.width - 1)
                            val y = (normalized.y * reference.height).toInt().coerceIn(0, reference.height - 1)
                            if (deviceState.interaction == InteractionMode.Mirror) {
                                // Pure passthrough: no uiautomator dump, which is what makes this
                                // feel like scrcpy rather than an inspector. A bare ripple still
                                // confirms the click landed — without it there is no way to tell a
                                // delivered tap from a dead view.
                                deviceState.feedbackSequence += 1
                                deviceState.tapFeedback = TapFeedback(
                                    id = deviceState.feedbackSequence,
                                    point = normalized,
                                    bounds = null,
                                    label = "",
                                    stability = SelectorStability.Unresolved,
                                )
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        embeddedScrcpy.tap(
                                            device.serial(), x, y, reference.width, reference.height,
                                        )
                                        session.recording?.recordTap(
                                            x, y, reference.width, reference.height, null,
                                        )
                                    }.onFailure { failure ->
                                        withContext(Dispatchers.Main) {
                                            addEvent(
                                                "×", "Tap failed",
                                                failure.message ?: "input injection failed",
                                                channel = Channel.Ui, severity = Severity.Error,
                                            )
                                        }
                                    }
                                }
                                return@DeviceColumn
                            }
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    val activeRecording = session.recording
                                    // While session.recording, the activeRecording owns the dump — it pauses the
                                    // accessibility stream around it. Otherwise resolve directly so
                                    // selectors can be inspected before session.recording starts.
                                    val target = if (activeRecording != null) {
                                        runCatching { activeRecording.resolveTap(x, y) }.getOrNull()
                                    } else {
                                        runCatching {
                                            UiSelectorResolver.resolveOnDevice(adb, device.serial(), x, y)
                                        }.getOrNull()
                                    }
                                    embeddedScrcpy.tap(device.serial(), x, y, reference.width, reference.height)
                                    activeRecording?.recordTap(x, y, reference.width, reference.height, target)
                                    withContext(Dispatchers.Main) {
                                        deviceState.feedbackSequence += 1
                                        deviceState.tapFeedback = buildTapFeedback(
                                            deviceState.feedbackSequence, normalized, target,
                                            reference.width, reference.height,
                                        )
                                        if (activeRecording != null) target?.flowStep()?.let(::appendStep)
                                    }
                                }
                            }
                        },
                        onDeviceSwipe = { start, end ->
                            val device = deviceState.selected ?: return@DeviceColumn
                            val reference = deviceState.liveImage ?: return@DeviceColumn
                            val startX = (start.x * reference.width).toInt().coerceIn(0, reference.width - 1)
                            val startY = (start.y * reference.height).toInt().coerceIn(0, reference.height - 1)
                            val endX = (end.x * reference.width).toInt().coerceIn(0, reference.width - 1)
                            val endY = (end.y * reference.height).toInt().coerceIn(0, reference.height - 1)
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    embeddedScrcpy.swipe(
                                        device.serial(), startX, startY, endX, endY, 280,
                                        reference.width, reference.height,
                                    )
                                    session.recording?.recordSwipe(
                                        startX, startY, endX, endY, 280, reference.width, reference.height,
                                    )
                                    if (session.recording != null) {
                                        val step = InteractionSteps.scrollStep(startX, startY, endX, endY)
                                        withContext(Dispatchers.Main) { appendStep(step) }
                                    }
                                }
                            }
                        },
                        onDeviceScroll = { direction ->
                            val device = deviceState.selected ?: return@DeviceColumn
                            val reference = deviceState.liveImage ?: return@DeviceColumn
                            val centerX = reference.width / 2
                            val centerY = reference.height / 2
                            val distance = reference.height / 3
                            val startY = if (direction == "up") centerY + distance else centerY - distance
                            val endY = if (direction == "up") centerY - distance else centerY + distance
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    embeddedScrcpy.swipe(
                                            device.serial(), centerX, startY, centerX, endY, 280,
                                            reference.width, reference.height,
                                        )
                                    session.recording?.recordSwipe(
                                        centerX, startY, centerX, endY, 280, reference.width, reference.height,
                                    )
                                    if (session.recording != null) withContext(Dispatchers.Main) {
                                        appendStep("step scroll direction=$direction")
                                    }
                                }
                            }
                        },
                        onDeviceKey = { key ->
                            val device = deviceState.selected ?: return@DeviceColumn
                            scope.launch(Dispatchers.IO) {
                                runCatching { embeddedScrcpy.keyEvent(device.serial(), key) }
                                    .onFailure {
                                        withContext(Dispatchers.Main) {
                                            session.status = it.message ?: "Key input failed"
                                        }
                                    }
                            }
                        },
                        onDeviceText = { text ->
                            val device = deviceState.selected ?: return@DeviceColumn
                            scope.launch(Dispatchers.IO) {
                                runCatching { embeddedScrcpy.typeText(device.serial(), text) }
                                    .onFailure {
                                        withContext(Dispatchers.Main) {
                                            session.status = it.message ?: "Text input failed"
                                        }
                                    }
                            }
                        },
                            onToggleMirror = ::toggleMirror,
                        onHideDisplay = { setShowDisplay(false) },
                        )
                    }
                    VerticalDivider(Modifier.fillMaxHeight().width(1.dp), color = Border)

                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        SurfaceTabs(
                            active = shell.surface,
                            onSelect = { shell.surface = it },
                            stepCount = authoring.stepCount,
                            evidenceCount = session.evidence.size,
                            eventCount = timeline.events.size,
                            drawerOpen = metrics.drawerVisible,
                            onToggleDrawer = { shell.drawerOpen = !shell.drawerOpen },
                        )
                        HorizontalDivider(color = Border)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (shell.surface) {
                                WorkSurface.Timeline -> TimelineSurface(
                                    modifier = Modifier.fillMaxSize(),
                                    events = timeline.events,
                                    channel = timeline.channel,
                                    onChannel = { timeline.channel = it },
                                    query = timeline.query,
                                    onQuery = { timeline.query = it },
                                    follow = timeline.follow,
                                    onFollow = { timeline.follow = it },
                                    recording = session.isRecording,
                                    selectedId = (shell.selection as? Selection.Event)?.event?.id,
                                    onSelect = { shell.selection = Selection.Event(it) },
                                    onExport = { exportTimeline(toClipboard = false) },
                                )
                                WorkSurface.Flow -> FlowEditorSurface(
                                    modifier = Modifier.fillMaxSize(),
                                    source = authoring.source,
                                    onSourceChanged = { authoring.source = it },
                                    status = authoring.status,
                                    busy = deviceState.busy,
                                    canRun = session.recording == null && deviceState.selected != null,
                                    device = deviceState.selected,
                                    lastResult = authoring.lastRun,
                                    kind = authoring.kind,
                                    fileName = authoring.fileName,
                                    dirty = authoring.dirty,
                                    rail = flowRail,
                                    onSave = { saveTest(false) },
                                    onRun = { saveTest(true) },
                                    onSelectStep = { number, text ->
                                        shell.selection = Selection.Step(number, text)
                                    },
                                )
                                WorkSurface.Evidence -> EvidenceSurface(
                                    modifier = Modifier.fillMaxSize(),
                                    evidence = session.evidence,
                                    selected = session.selectedEvidence,
                                    image = session.evidenceImage,
                                    nodes = session.hierarchyNodes,
                                    onSelect = { artifact ->
                                        session.selectedEvidence = artifact
                                        shell.selection = Selection.Checkpoint(artifact)
                                        scope.launch {
                                            session.evidenceImage = withContext(Dispatchers.IO) { loadImage(artifact.screenshot) }
                                            session.hierarchyNodes = withContext(Dispatchers.IO) {
                                                loadHierarchyNodes(artifact.hierarchy)
                                            }
                                        }
                                    },
                                    onRefresh = {
                                        session.lastRoot?.let { root -> scope.launch { refreshSessionArtifacts(root) } }
                                    },
                                    onOpenFolder = { openSessionFolder() },
                                )
                                WorkSurface.Tree -> TreeSurface(
                                    modifier = Modifier.fillMaxSize(),
                                    nodes = session.hierarchyNodes,
                                    subtitle = session.selectedEvidence?.let { "Checkpoint ${it.sequence} · ${it.reason}" }
                                        ?: "Latest captured hierarchy",
                                    onRefresh = {
                                        session.lastRoot?.let { root -> scope.launch { refreshSessionArtifacts(root) } }
                                    },
                                    webSnapshot = session.webViewSnapshot,
                                    webLoading = session.webViewLoading,
                                    webError = session.webViewError,
                                    onRefreshWeb = { refreshWebViewInspection() },
                                    webTargets = session.webViewTargets,
                                    selectedWebTarget = session.selectedWebViewTarget,
                                    onSelectWebTarget = { target -> refreshWebViewInspection(target.pageMatch()) },
                                    onSelectWeb = { title, fields ->
                                        shell.selection = Selection.WebMetadata(title, fields)
                                    },
                                )
                                WorkSurface.Perf -> PerformanceSurface(
                                    modifier = Modifier.fillMaxSize(),
                                    snapshot = session.performance,
                                    previous = session.previousPerformance,
                                    packageName = deviceState.packageName,
                                    busy = deviceState.busy,
                                    onCollect = ::collectPerformance,
                                )
                                WorkSurface.Settings -> Unit
                            }
                        }
                    }

                    if (metrics.drawerVisible) {
                        VerticalDivider(Modifier.fillMaxHeight().width(1.dp), color = Border)
                        ContextDrawer(
                            selection = shell.selection,
                            onClear = { shell.selection = Selection.None },
                            onOpenFolder = { openSessionFolder() },
                            onAddStep = { step ->
                                appendStep(step)
                                shell.surface = WorkSurface.Flow
                            },
                            manifest = projectState.manifest,
                            installedPackages = deviceState.installedPackages,
                            suites = projectState.suites.map { it.label },
                            onManifestChanged = ::writeManifest,
                            onOpenManifest = { dispatch("project.manifest") },
                            onRename = { name ->
                                renameOpenFile(name)
                                shell.selection = Selection.None
                            },
                        )
                    }
                }
                }
            }
            TelemetryStrip(
                health = health,
                packageInstalled = deviceState.packageInstalled,
                deviceCount = deviceState.devices.size,
                recording = session.isRecording,
                // Both are rendered `~`-shortened rather than truncated: the strip is one line,
                // and the tail of a long absolute path is the part worth reading.
                sessionPath = session.activeRoot?.let { displayPath(it, projectState.root) },
                projectPath = projectState.root?.let { displayPath(it, null) },
                // Shown shortened, revealed in full: the strip is the one place a path is always
                // on screen, so it is the natural handle for "where did that actually go?".
                onRevealSession = session.activeRoot?.let { root -> { revealInFileManager(root) } },
                onRevealProject = projectState.root?.let { root -> { revealInFileManager(root) } },
            )
        }
        if (shell.paletteOpen) {
            CommandPalette(commands = commands, onDismiss = { shell.paletteOpen = false })
        }
    }
    }
}

// design.md §5.5 — one virtualised, time-ordered lane. Channels filter it; they do not split it.
@Composable
private fun TimelineSurface(
    modifier: Modifier,
    events: List<CapturedEvent>,
    channel: Channel,
    onChannel: (Channel) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    follow: Boolean,
    onFollow: (Boolean) -> Unit,
    recording: Boolean,
    selectedId: Long?,
    onSelect: (CapturedEvent) -> Unit,
    onExport: () -> Unit,
) {
    val visible = remember(events.toList(), channel, query) {
        events.filter { (channel == Channel.All || it.channel == channel) && it.matches(query) }
    }
    val listState = rememberLazyListState()
    val newestId = visible.firstOrNull()?.id
    // The lane is newest-first, so "away from the head" is simply a non-zero scroll index.
    val atHead by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var acknowledgedId by remember { mutableStateOf(newestId) }
    LaunchedEffect(newestId, follow, atHead) {
        if (newestId == null) return@LaunchedEffect
        if (follow && !atHead) listState.animateScrollToItem(0)
        if (atHead) acknowledgedId = newestId
    }
    val unseen = if (atHead || acknowledgedId == null) 0
    else visible.count { it.id > (acknowledgedId ?: 0L) }

    Column(modifier.background(Panel)) {
        TimelineHeader(
            events = events,
            channel = channel,
            onChannel = onChannel,
            query = query,
            onQuery = onQuery,
            follow = follow,
            onFollow = onFollow,
            recording = recording,
            onExport = onExport,
        )
        HorizontalDivider(color = Border)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) {
                EmptyTimeline(channel, query, recording, Modifier.fillMaxSize())
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(visible, key = { it.id }) { event ->
                        EventRow(
                            event = event,
                            selected = event.id == selectedId,
                            onClick = { onSelect(event) },
                        )
                        HorizontalDivider(color = Border)
                    }
                }
            }
            if (unseen > 0) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Ink250)
                        .border(1.dp, Ink400, RoundedCornerShape(12.dp))
                        .clickable { onFollow(true) }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        "$unseen new",
                        color = TextHi,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineHeader(
    events: List<CapturedEvent>,
    channel: Channel,
    onChannel: (Channel) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    follow: Boolean,
    onFollow: (Boolean) -> Unit,
    recording: Boolean,
    onExport: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TextHi,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                    ),
                    cursorBrush = SolidColor(SignalIce),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    "Filter events",
                                    color = TextLow,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Text(
                if (follow) "FOLLOW" else "PAUSED",
                color = if (follow) SignalGreen else TextLow,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable { onFollow(!follow) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
            Text(
                if (recording) "STREAMING" else "IDLE",
                color = if (recording) SignalGreen else TextLow,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            )
            if (events.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "EXPORT",
                    color = TextMid,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Ink200)
                        .clickable(onClick = onExport)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Channel.entries.forEach { candidate ->
                val count = if (candidate == Channel.All) events.size
                else events.count { it.channel == candidate }
                val selected = candidate == channel
                Row(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(if (selected) Ink250 else Color.Transparent)
                        .clickable { onChannel(candidate) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        candidate.label,
                        color = if (selected) TextHi else TextMid,
                        fontSize = 11.sp,
                    )
                    if (count > 0) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            count.toString(),
                            color = TextLow,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTimeline(channel: Channel, query: String, recording: Boolean, modifier: Modifier) {
    EmptyState(
        caps = if (query.isNotBlank()) "NO MATCHES" else "AWAITING EVENTS",
        message = when {
            query.isNotBlank() -> "No ${channel.label.lowercase()} events match \"$query\"."
            recording -> "Interact with the device to capture ${channel.label.lowercase()} events."
            else -> "Start recording to capture ${channel.label.lowercase()} events."
        },
        hint = if (recording || query.isNotBlank()) null else "⌘R",
        modifier = modifier,
    )
}

@Composable
private fun EventRow(event: CapturedEvent, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(34.dp)
            .background(if (selected) Ink250 else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading channel rule; severity colours it.
        Box(Modifier.width(2.dp).fillMaxHeight().background(event.severity.rule()))
        Spacer(Modifier.width(10.dp))
        Text(
            event.time,
            color = TextMid,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(78.dp),
        )
        Text(event.symbol, color = TextMid, fontSize = 11.sp, modifier = Modifier.width(18.dp))
        Text(
            event.title,
            color = TextHi,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            event.detail,
            color = TextMid,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (event.duration.isNotEmpty()) {
            Text(
                event.duration,
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
    }
}


// design.md §5.6 — the `.flow` file is the product's core artifact, so it gets a real editor.
@Composable
private fun FlowEditorSurface(
    modifier: Modifier,
    source: String,
    onSourceChanged: (String) -> Unit,
    status: String,
    busy: Boolean,
    canRun: Boolean,
    device: Device?,
    lastResult: FlowRunResult?,
    kind: EditorKind,
    fileName: String?,
    dirty: Boolean,
    rail: FlowRail,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onSelectStep: (Int, String) -> Unit,
) {
    // design.md §5.6 — validation on a 400ms debounce, through the parser the CLI uses.
    var cardView by remember { mutableStateOf(false) }
    var validation by remember(kind) { mutableStateOf(validateSource(source, kind)) }
    LaunchedEffect(source, kind) {
        delay(400)
        validation = validateSource(source, kind)
    }

    val scroll = rememberScrollState()
    val lines = remember(source) { source.split("\n") }

    Column(modifier.background(Panel)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoverTip(if (rail.open) "Hide file browser" else "Show file browser", "\u2318\u21e7E") {
                Text(
                    if (rail.open) "◧" else "◫",
                    color = TextMid,
                    fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = rail.onToggle)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (kind == EditorKind.Suite) "Suite" else "Flow",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextHi,
            )
            Spacer(Modifier.width(10.dp))
            fileName?.let { name ->
                Text(
                    name,
                    color = TextMid,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                if (dirty) {
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.size(5.dp).clip(CircleShape).background(SignalAmber))
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (kind == EditorKind.Suite) {
                    "${validation.stepCount} test(s) · replays without an AI agent"
                } else "${validation.stepCount} step(s) · runs without an AI agent",
                color = TextMid,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            // The step cards are `.flow`-specific; a suite has no steps to reorder.
            if (kind == EditorKind.Flow)
            listOf(false to "SOURCE", true to "STEPS").forEach { (wantsCards, label) ->
                Text(
                    label,
                    color = if (cardView == wantsCards) TextHi else TextMid,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .background(if (cardView == wantsCards) Ink250 else Color.Transparent)
                        .clickable { cardView = wantsCards }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Spacer(Modifier.width(8.dp))
            ValidationChip(validation)
        }
        HorizontalDivider(color = Border)

        Row(Modifier.weight(1f).fillMaxWidth()) {
        if (rail.open) {
            FlowFileRail(
                tests = rail.tests,
                suites = rail.suites,
                runs = rail.runs,
                scratch = rail.scratch,
                projectOpen = rail.projectOpen,
                openFile = rail.openFile,
                onOpen = rail.onOpen,
                onNewTest = rail.onNewTest,
                onNewSuite = rail.onNewSuite,
                onRunSuite = rail.onRunSuite,
                onOpenProject = rail.onOpenProject,
                onCreateProject = rail.onCreateProject,
                onOpenRun = rail.onOpenRun,
                onRevealRun = rail.onRevealRun,
            )
            VerticalDivider(Modifier.fillMaxHeight().width(1.dp), color = Border)
        }
        if (cardView && kind == EditorKind.Flow) {
            StepCardList(
                source = source,
                onSourceChanged = onSourceChanged,
                onSelectStep = onSelectStep,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else
        Row(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)) {
            // Gutter: line number plus a per-line marker.
            Column(Modifier.width(44.dp).background(Ink050).padding(vertical = 10.dp)) {
                lines.forEachIndexed { index, line ->
                    Row(
                        Modifier.fillMaxWidth().height(EditorLineHeight.dp)
                            .then(
                                if (line.trim().startsWith("step ")) {
                                    Modifier.clickable { onSelectStep(index + 1, line.trim()) }
                                } else Modifier,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            color = TextLow,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(26.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.size(5.dp).clip(CircleShape)
                                .background(validation.marks.getOrElse(index) { LineMark.None }.color()),
                        )
                    }
                }
            }

            Column(Modifier.weight(1f)) {
                BasicTextField(
                    value = source,
                    onValueChange = onSourceChanged,
                    textStyle = TextStyle(
                        color = TextHi,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = EditorLineHeight.sp,
                    ),
                    visualTransformation = FlowSyntax(LocalFlowColors.current),
                    cursorBrush = SolidColor(SignalIce),
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (source.isEmpty()) {
                                Text(
                                    "Start a recording, or write a flowscope 1 test here",
                                    color = TextLow,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
                // Errors render inline, never as a modal.
                validation.errorDetail?.let { detail ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(4.dp)).background(SignalRedDim)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        validation.errorLine?.let {
                            Text(
                                "line $it",
                                color = SignalRed,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(detail, color = SignalRed, fontSize = 11.5.sp)
                    }
                }
            }
        }
        }

        HorizontalDivider(color = Border)
        FlowRunBar(
            busy = busy,
            canRun = canRun && validation.isValid,
            valid = validation.isValid,
            device = device,
            status = status,
            lastResult = lastResult,
            kind = kind,
            dirty = dirty,
            onSave = onSave,
            onRun = onRun,
        )
    }
}

@Composable
private fun ValidationChip(validation: FlowValidation) {
    val warnings = validation.marks.count { it == LineMark.Warning }
    val (tint, label) = when {
        !validation.isValid -> SignalRed to "INVALID"
        warnings == 1 -> SignalAmber to "1 WARNING"
        warnings > 1 -> SignalAmber to "$warnings WARNINGS"
        else -> SignalGreen to "VALID"
    }
    val tip = when {
        !validation.isValid -> validation.errorDetail ?: "This file does not parse"
        warnings > 0 -> "Weak selectors or long timeouts — valid, but fragile to re-run"
        else -> "Parses exactly as `flowscope test validate` would"
    }
    HoverTip(tip) {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).background(Ink200)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(7.dp))
            // softWrap=false: this chip must never break across lines when the header is narrow.
            Text(
                label,
                color = tint,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// design.md §5.6 / §6.2 — the run bar names the target device so you never fire at the wrong one.
@Composable
private fun FlowRunBar(
    busy: Boolean,
    canRun: Boolean,
    valid: Boolean,
    device: Device?,
    status: String,
    lastResult: FlowRunResult?,
    kind: EditorKind = EditorKind.Flow,
    dirty: Boolean = true,
    onSave: () -> Unit,
    onRun: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(Ink150).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lastResult?.let { result ->
            Row(
                Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (result.passed) Ink200 else SignalRedDim)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(if (result.passed) SignalGreen else SignalRed),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    result.label,
                    color = if (result.passed) SignalGreen else SignalRed,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Text(
            status,
            color = TextMid,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Nothing to write is worth saying out loud: a permanently live Save button gives no
        // signal about whether the buffer matches the file.
        HoverTip(
            if (dirty) "Write the buffer to its file" else "The file already matches the editor",
            "\u2318S",
        ) {
            OutlinedButton(onClick = onSave, enabled = !busy && dirty) {
                Text(if (dirty) "Save" else "Saved", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Button(onClick = onRun, enabled = !busy && canRun) {
                Text(
                    when {
                        busy -> "Running…"
                        kind == EditorKind.Suite -> "Run suite on device"
                        else -> "Run on device"
                    },
                    fontSize = 12.sp,
                )
            }
            device?.let {
                Text(
                    "${it.displayName()} · ${shortSerial(it.serial())}",
                    color = TextLow,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            } ?: Text(
                if (!valid) "fix errors to run" else "no device selected",
                color = TextLow,
                fontSize = 9.sp,
            )
        }
    }
}

// Card view of parsed flow steps for direct authoring operations.
@Composable
private fun StepCardList(
    source: String,
    onSourceChanged: (String) -> Unit,
    onSelectStep: (Int, String) -> Unit,
    modifier: Modifier,
) {
    val steps = remember(source) { flowStepLines(source) }
    if (steps.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "No steps yet. Record a flow, or write one on the SOURCE tab.",
                color = TextLow,
                fontSize = 12.sp,
            )
        }
        return
    }
    LazyColumn(modifier.padding(12.dp)) {
        items(steps, key = { it.lineIndex }) { step ->
            StepCard(
                step = step,
                isFirst = step.lineIndex == steps.first().lineIndex,
                isLast = step.lineIndex == steps.last().lineIndex,
                onClick = { onSelectStep(step.number, step.body) },
                onMove = { delta -> onSourceChanged(moveStep(source, step.lineIndex, delta)) },
                onDuplicate = { onSourceChanged(duplicateStep(source, step.lineIndex)) },
                onToggle = { onSourceChanged(toggleStep(source, step.lineIndex)) },
                onDelete = { onSourceChanged(deleteStep(source, step.lineIndex)) },
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun StepCard(
    step: StepLine,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val stability = stabilityOfStep(step.body)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(8.dp))
            .background(Ink150).border(1.dp, Ink300, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .alpha(if (step.enabled) 1f else .45f)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${step.number}",
            color = TextLow,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                describeStep(step.body),
                color = TextHi,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                step.body,
                color = TextLow,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        selectorOfStep(step.body)?.let {
            SelectorChip(it, stability)
            Spacer(Modifier.width(10.dp))
        }
        CardAction("Move up", enabled = !isFirst) { onMove(-1) }
        CardAction("Move down", enabled = !isLast) { onMove(1) }
        CardAction(if (step.enabled) "Disable" else "Enable", enabled = true, onClick = onToggle)
        CardAction("Duplicate", enabled = true, onClick = onDuplicate)
        CardAction("Delete", enabled = true, onClick = onDelete)
    }
}

@Composable
private fun CardAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = when {
        !enabled -> Ink400
        label == "Delete" -> SignalRed.copy(alpha = .8f)
        else -> TextMid
    }
    HoverTip(label) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(5.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(12.dp)) {
                val stroke = 1.4.dp.toPx()
                when (label) {
                "Move up", "Move down" -> {
                    val up = label == "Move up"
                    val tipY = if (up) 0f else size.height
                    val baseY = if (up) size.height else 0f
                    drawLine(tint, Offset(size.width / 2, baseY), Offset(size.width / 2, tipY), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(size.width / 2, tipY), Offset(size.width * .2f, tipY + (if (up) 1 else -1) * size.height * .35f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(size.width / 2, tipY), Offset(size.width * .8f, tipY + (if (up) 1 else -1) * size.height * .35f), stroke, StrokeCap.Round)
                }
                "Disable" -> drawCircle(tint, size.minDimension * .4f, style = Stroke(width = stroke))
                "Enable" -> {
                    drawCircle(tint, size.minDimension * .4f, style = Stroke(width = stroke))
                    drawCircle(tint, size.minDimension * .18f)
                }
                "Duplicate" -> {
                    drawRect(tint, Offset(0f, size.height * .25f), Size(size.width * .7f, size.height * .7f), style = Stroke(width = stroke))
                    drawRect(tint, Offset(size.width * .3f, 0f), Size(size.width * .7f, size.height * .7f), style = Stroke(width = stroke))
                }
                    else -> {
                        drawLine(tint, Offset(0f, size.height * .22f), Offset(size.width, size.height * .22f), stroke, StrokeCap.Round)
                        drawRect(tint, Offset(size.width * .15f, size.height * .22f), Size(size.width * .7f, size.height * .78f), style = Stroke(width = stroke))
                    }
                }
            }
        }
    }
}

private const val EditorLineHeight = 19

/** Launchers, system UI and similar are never the app under test. */
private fun isSystemShell(packageName: String): Boolean =
    packageName.contains("launcher", ignoreCase = true) ||
        packageName == "com.android.systemui" ||
        packageName.startsWith("com.google.android.apps.nexuslauncher") ||
        packageName.startsWith("com.samsung.android.app.cocktail")

private const val RunListLimit = 10

/** Long enough for a project to reopen, short enough that a wedged restore cannot block a record. */
private const val RestoreWaitMillis = 4_000L
private const val DefaultTimeoutMillis = 10_000L

/** What `saveTest` learned about the file it wrote. */
private data class SaveOutcome(
    val target: Path,
    val passed: Boolean,
    val reportRoot: Path?,
    val label: String?,
    val completionNotice: CompletionNotice?,
    val webChanges: List<String> = emptyList(),
)

private fun suiteLabel(suite: FlowSuite, passed: Boolean): String {
    val total = suite.entries().size
    return if (passed) "PASSED $total/$total" else "FAILED"
}

/** The rail's label for a file: its path relative to the section's directory. */
private fun relativeLabel(base: Path, file: Path): String =
    runCatching { base.relativize(file).toString().replace('\\', '/') }
        .getOrDefault(file.fileName.toString())

private fun listFlowFiles(directory: Path): List<ProjectEntry> = runCatching {
    if (!Files.isDirectory(directory)) return emptyList()
    Files.list(directory).use { children ->
        children.filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().endsWith(".flow") }
            .map { ProjectEntry(it, it.fileName.toString()) }
            .toList()
            .sortedBy { it.label }
    }
}.getOrDefault(emptyList())

private fun suiteTemplate(projectName: String): String = """
    flowscope-suite 1
    name "$projectName regression"
    continue-on-failure false

    # test tests/login.flow
""".trimIndent() + "\n"

/**
 * The header a new recording or new test starts from. `flowscope.json`'s defaults pre-fill it, and
 * it is then written into the file in full — so the saved `.flow` stays self-contained and runs
 * with no project present.
 */
private fun flowTemplate(
    testName: String,
    packageName: String,
    mode: CaptureMode,
    timeoutMillis: Long,
): String = """
    flowscope 1
    name "$testName"
    package $packageName
    mode ${mode.cliName()}
    timeout $timeoutMillis

    step launch
""".trimIndent() + "\n"

private fun loadEvidence(root: Path): List<EvidenceArtifact> {
    val checkpointRoot = root.resolve("checkpoints")
    if (!Files.isDirectory(checkpointRoot)) return emptyList()
    return Files.list(checkpointRoot).use { paths ->
        paths.filter(Files::isDirectory).sorted().map { directory ->
            val sequence = directory.fileName.toString().toIntOrNull() ?: 0
            val metadata = directory.resolve("metadata.json").takeIf(Files::exists)
                ?.let { Files.readString(it, StandardCharsets.UTF_8) }.orEmpty()
            val reason = jsonString(metadata, "reason") ?: "checkpoint"
            EvidenceArtifact(sequence, reason, directory)
        }.toList()
    }
}

/** Structured hierarchy nodes for a checkpoint, or an empty list when none was captured. */
private fun loadHierarchyNodes(path: Path?): List<UiHierarchy.UiNode> = runCatching {
    if (path == null || !Files.exists(path)) emptyList()
    else UiHierarchy.parse(Files.readAllBytes(path))
}.getOrDefault(emptyList())

/** Wraps a decoder frame's packed BGRA pixels as a Compose bitmap without re-encoding. */
private fun EmbeddedScrcpyClient.Frame.toComposeBitmap(): ImageBitmap {
    val info = ImageInfo(
        width = width(),
        height = height(),
        colorType = ColorType.BGRA_8888,
        alphaType = ColorAlphaType.OPAQUE,
    )
    return SkiaImage.makeRaster(info, pixels(), width() * 4).toComposeImageBitmap()
}

private fun loadImage(path: Path): ImageBitmap? = runCatching {
    if (!Files.exists(path)) null else SkiaImage.makeFromEncoded(Files.readAllBytes(path)).toComposeImageBitmap()
}.getOrNull()

/** Maps a host resolution onto normalised overlay geometry (design.md §5.3.2). */
private fun buildTapFeedback(
    id: Long,
    point: Offset,
    target: UiSelectorResolver.Resolution?,
    imageWidth: Int,
    imageHeight: Int,
): TapFeedback {
    if (target == null || target.selector().isEmpty()) {
        return TapFeedback(id, point, null, "unresolved · coordinate only", SelectorStability.Unresolved)
    }
    val bounds = Rect(
        target.left().toFloat() / imageWidth,
        target.top().toFloat() / imageHeight,
        target.right().toFloat() / imageWidth,
        target.bottom().toFloat() / imageHeight,
    )
    val label = target.selector().entries.joinToString(" ") { "${it.key}=${it.value}" }
    return TapFeedback(id, point, bounds, label, stabilityOf(target.stability()))
}

private fun jsonString(json: String, key: String): String? {
    val match = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").find(json) ?: return null
    return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
}

private fun jsonInt(json: String, key: String): Int? =
    Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

private fun agentSymbol(kind: String): String = when {
    kind == "ui.tap" -> "●"
    kind == "ui.scroll" -> "↕"
    kind.startsWith("network.") -> "⇄"
    kind.startsWith("background.") -> "⚙"
    kind == "semantic.action" -> "◆"
    else -> "•"
}

private fun agentDetail(json: String, kind: String): String = when {
    kind.startsWith("network.") -> listOfNotNull(jsonString(json, "method"), jsonString(json, "routeTemplate")).joinToString(" ")
    kind.startsWith("background.") -> listOfNotNull(jsonString(json, "workName"), jsonString(json, "state")).joinToString(" · ")
    kind == "ui.tap" -> jsonString(json, "resourceName") ?: jsonString(json, "className") ?: "Touch"
    kind == "semantic.action" -> listOfNotNull(jsonString(json, "action"), jsonString(json, "componentId")).joinToString(" · ")
    kind == "activity.lifecycle" -> listOfNotNull(jsonString(json, "activity"), jsonString(json, "state")).joinToString(" · ")
    else -> json.take(180)
}

private fun suggestedFlowStep(json: String, kind: String): String? = when (kind) {
    "semantic.action" -> jsonString(json, "componentId")?.takeIf { it.isNotBlank() }?.let { "step tap id=$it" }
    "ui.tap" -> jsonString(json, "resourceName")?.takeIf { it.isNotBlank() }?.let { "step tap id=$it" }
    "ui.scroll" -> {
        val deltaX = jsonInt(json, "deltaX") ?: 0
        val deltaY = jsonInt(json, "deltaY") ?: 0
        val direction = if (kotlin.math.abs(deltaY) >= kotlin.math.abs(deltaX)) {
            if (deltaY < 0) "up" else "down"
        } else if (deltaX < 0) "left" else "right"
        "step scroll direction=$direction"
    }
    else -> null
}

private fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

internal fun shortSerial(serial: String): String = if (serial.length <= 12) serial else "…${serial.takeLast(10)}"

internal fun formatDuration(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
