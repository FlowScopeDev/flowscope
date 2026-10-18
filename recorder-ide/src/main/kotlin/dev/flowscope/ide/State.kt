package dev.flowscope.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.flowscope.host.CaptureMode
import dev.flowscope.host.Device
import dev.flowscope.host.EmbeddedScrcpyClient
import dev.flowscope.host.ScrcpyClient
import dev.flowscope.host.SessionRecorder
import dev.flowscope.host.UiHierarchy
import dev.flowscope.host.WebViewSnapshot
import dev.flowscope.host.WebViewTarget
import java.nio.file.Path

/**
 * design.md §8.6 — state holders, so the shell is not thirty-odd `remember { mutableStateOf(…) }`
 * declarations in one composable. These are plain Compose-observable classes: no logic lives here,
 * only the state each area of the UI owns.
 */

/** Devices, the selected target, and everything about the live feed from it. */
internal class DeviceState {
    var devices by mutableStateOf<List<Device>>(emptyList())
    var selected by mutableStateOf<Device?>(null)
    var packageName by mutableStateOf("")

    /** The package adopted from the foreground app; while it is unedited it keeps following the screen. */
    var autoDetectedPackage by mutableStateOf<String?>(null)
    var installedPackages by mutableStateOf<List<String>>(emptyList())
    var captureMode by mutableStateOf(CaptureMode.ZERO_CODE)
    var busy by mutableStateOf(false)

    /** Installed AVDs, what ADB sees running (serial to AVD), and the ones launched from here. */
    var emulatorAvailable by mutableStateOf(false)
    var avdNames by mutableStateOf<List<String>>(emptyList())
    var runningAvds by mutableStateOf<Map<String, String>>(emptyMap())
    var ownedAvds by mutableStateOf<Set<String>>(emptySet())
    var bootingAvds by mutableStateOf<Set<String>>(emptySet())
    val avds: List<AvdEntry> get() = mergeAvds(avdNames, runningAvds, bootingAvds, ownedAvds)

    var screenshot by mutableStateOf<ImageBitmap?>(null)
    var embeddedFrame by mutableStateOf<ImageBitmap?>(null)
    var embeddedSession by mutableStateOf<EmbeddedScrcpyClient.Session?>(null)
    var embeddedStatus by mutableStateOf("")
    var mirrorSession by mutableStateOf<ScrcpyClient.Session?>(null)
    var mirrorStatus by mutableStateOf("")

    var favourites by mutableStateOf<Set<String>>(emptySet())
    /** True once the user picks a capture mode by hand; auto-detection then stops overriding it. */
    var captureModeChosen by mutableStateOf(false)
    var agentAvailable by mutableStateOf(false)
    /** Consecutive failed embedded-stream starts; drives reconnect backoff. */
    var streamAttempts by mutableStateOf(0)
    var recentPackages by mutableStateOf<Map<String, String>>(emptyMap())
    /** Set when the attached device vanishes mid-recording. */
    var lostSerial by mutableStateOf<String?>(null)
    var debuggable by mutableStateOf(true)

    var interaction by mutableStateOf(InteractionMode.Inspect)
    var tapFeedback by mutableStateOf<TapFeedback?>(null)
    var feedbackSequence by mutableStateOf(0L)

    val serial: String? get() = selected?.serial()

    /**
     * False when a target is set but the selected device does not have it installed. Recording
     * would fail later with a vague error, so the UI blocks it up front.
     */
    val packageInstalled: Boolean
        get() = packageName.isBlank() || installedPackages.isEmpty() ||
            packageName.trim() in installedPackages
    val streaming: Boolean get() = embeddedSession?.isAlive() == true
    val mirroring: Boolean get() = mirrorSession?.isAlive() == true
    /** The frame the stage renders: the live stream when available, otherwise the screenshot. */
    val liveImage: ImageBitmap? get() = embeddedFrame ?: screenshot
    val aspect: Float
        get() = liveImage?.let { it.width.toFloat() / it.height.toFloat() } ?: FallbackAspect
}

/** The recording session and the evidence it produces. */
internal class SessionState {
    var recording by mutableStateOf<SessionRecorder?>(null)
    var lastRoot by mutableStateOf<Path?>(null)
    var seconds by mutableStateOf(0L)
    var status by mutableStateOf("Looking for ADB devices…")

    var evidence by mutableStateOf<List<EvidenceArtifact>>(emptyList())
    var selectedEvidence by mutableStateOf<EvidenceArtifact?>(null)
    var evidenceImage by mutableStateOf<ImageBitmap?>(null)
    var hierarchyNodes by mutableStateOf<List<UiHierarchy.UiNode>>(emptyList())
    var webViewSnapshot by mutableStateOf<WebViewSnapshot?>(null)
    var webViewTargets by mutableStateOf<List<WebViewTarget>>(emptyList())
    var selectedWebViewTarget by mutableStateOf<WebViewTarget?>(null)
    var webViewLoading by mutableStateOf(false)
    var webViewError by mutableStateOf<String?>(null)

    var performance by mutableStateOf<PerformanceSnapshot?>(null)
    var previousPerformance by mutableStateOf<PerformanceSnapshot?>(null)

    val isRecording: Boolean get() = recording != null
    /** Where the user should look for artifacts right now. */
    val activeRoot: Path? get() = recording?.root() ?: lastRoot
}

/** The event lane and its filters. */
internal class TimelineState {
    val events = mutableStateListOf<CapturedEvent>()
    var sequence by mutableStateOf(0L)
    var channel by mutableStateOf(Channel.All)
    var query by mutableStateOf("")
    var follow by mutableStateOf(true)

    fun add(event: CapturedEvent) {
        events.add(0, event)
        while (events.size > EventCap) events.removeLast()
    }

    private companion object {
        /** Safe now that the lane is virtualised (design.md §5.5). */
        const val EventCap = 2_000
    }
}

/** The `.flow` buffer being authored and the outcome of the last run. */
internal class AuthoringState {
    var source by mutableStateOf("")
    var status by mutableStateOf("No test saved yet")
    var lastRun by mutableStateOf<FlowRunResult?>(null)

    /** The file this buffer came from, and the text that is actually on disk for it. */
    var currentFile by mutableStateOf<Path?>(null)
    var savedSource by mutableStateOf("")

    fun appendStep(step: String) {
        source = source.trimEnd() + "\n$step\n"
    }

    /** Points the buffer at a file and marks it clean. */
    fun adopt(file: Path?, contents: String) {
        currentFile = file
        source = contents
        savedSource = contents
    }

    /**
     * Records what was actually written. Takes the contents rather than reading [source] again:
     * a run can take tens of seconds, and the user may have typed since the write.
     */
    fun markSaved(file: Path, contents: String) {
        currentFile = file
        savedSource = contents
    }

    val stepCount: Int get() = source.lineSequence().count { it.trim().startsWith("step ") }

    /** Derived, never stored: five step-card actions and every recorded event mutate `source`. */
    val dirty: Boolean get() = isDirty(source, savedSource)
    val kind: EditorKind get() = editorKindOf(currentFile)
    val fileName: String? get() = currentFile?.fileName?.toString()
}

/** Which surface is showing, what the drawer is inspecting, and the palette. */
internal class ShellState {
    var surface by mutableStateOf(WorkSurface.Timeline)
    private var surfaceBeforeSettings = WorkSurface.Timeline
    var drawerOpen by mutableStateOf(true)
    var selection by mutableStateOf<Selection>(Selection.None)
    var paletteOpen by mutableStateOf(false)
    var reduceMotion by mutableStateOf(false)

    fun toggleSettings() {
        if (surface == WorkSurface.Settings) {
            surface = surfaceBeforeSettings
        } else {
            surfaceBeforeSettings = surface
            surface = WorkSurface.Settings
        }
    }
}
