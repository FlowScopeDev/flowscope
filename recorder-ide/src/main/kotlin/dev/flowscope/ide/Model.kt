package dev.flowscope.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import java.nio.file.Path

// design.md §5.5 — channels filter one time-ordered lane; they are not separate streams.
// `Session` is an addition to the documented set: recorder-level events (device connected,
// recording started, test results) are not Android lifecycle events and do not belong there.
internal enum class Channel(val label: String) {
    All("All"),
    Ui("UI"),
    Network("Network"),
    Background("Background"),
    Lifecycle("Lifecycle"),
    Logs("Logs"),
    Session("Session"),
}

internal enum class Severity { Normal, Warning, Error }

@Composable
@ReadOnlyComposable
internal fun Severity.rule(): Color = when (this) {
    Severity.Normal -> Ink400
    Severity.Warning -> SignalAmber
    Severity.Error -> SignalRed
}

// design.md §4.2 — five views of one session, exposed as tabs on the work surface itself.
internal enum class WorkSurface(val label: String) {
    Timeline("Timeline"), Flow("Flow"), Evidence("Evidence"), Tree("Tree"), Perf("Perf"),
    Settings("Settings"),
}

// design.md §4.2 — whatever the context drawer is currently inspecting.
internal sealed interface Selection {
    data object None : Selection
    data class Event(val event: CapturedEvent) : Selection
    data class Step(val number: Int, val source: String) : Selection
    data class Checkpoint(val artifact: EvidenceArtifact) : Selection
    data class WebMetadata(val title: String, val fields: List<Pair<String, String>>) : Selection

    /** The open project's `flowscope.json`, edited as a form. */
    data object ProjectSettings : Selection

    /** Renaming the open file; carries the name the field starts with. */
    data class RenameFile(val currentName: String) : Selection
}

/**
 * An action that would discard unsaved work, parked until the user answers.
 *
 * Holds the continuation rather than an enum of known actions: the prompt does not care what is
 * being switched to, only that the buffer must be dealt with first, and keeping the follow-up here
 * means a new caller cannot forget to wire its own branch into the dialog.
 */
internal class PendingConfirm(val fileName: String, val proceed: suspend () -> Unit)

/**
 * The run control in the session bar. A holder for the same reason as [ProjectChrome]: `SessionBar`
 * is already well past the parameter count where positional arguments stop being readable.
 */
internal data class RunControl(
    val enabled: Boolean,
    val running: Boolean,
    val kind: EditorKind,
    val shortcut: String,
    val onRun: () -> Unit,
) {
    val label: String get() = if (kind == EditorKind.Suite) "Run suite on device" else "Run flow on device"
}

/**
 * Everything the file rail inside the Flow surface needs. One holder, for the same reason as
 * [ProjectChrome]: the editor's parameter list is already long.
 */
internal data class FlowRail(
    val open: Boolean,
    val projectOpen: Boolean,
    val tests: List<ProjectEntry>,
    /** Rows the listing no longer backs, shown marked so a deleted file does not just vanish. */
    val missing: List<ProjectEntry>,
    val suites: List<ProjectEntry>,
    val runs: List<dev.flowscope.host.RunReport>,
    val scratch: List<ProjectEntry>,
    val openFile: Path?,
    val onToggle: () -> Unit,
    val onOpen: (Path) -> Unit,
    val onNewTest: () -> Unit,
    val onNewSuite: () -> Unit,
    val onRunSuite: (Path) -> Unit,
    val onOpenProject: () -> Unit,
    val onCreateProject: () -> Unit,
    val onOpenRun: (Path) -> Unit,
    val onRevealRun: (Path) -> Unit,
)

/**
 * Everything the project chip in the session bar needs. Passed as one holder because `SessionBar`
 * already takes 22 parameters and the window minimum is 1040dp.
 */
internal data class ProjectChrome(
    val label: String,
    val openName: String?,
    val dirty: Boolean,
    val isOpen: Boolean,
    val recents: List<RecentProject>,
    val onOpen: () -> Unit,
    val onCreate: () -> Unit,
    val onOpenRecent: (Path) -> Unit,
    val onOpenPath: (String) -> Unit,
    val onSettings: () -> Unit,
    val onManifest: () -> Unit,
    val onReload: () -> Unit,
    val onReveal: () -> Unit,
    val onClose: () -> Unit,
)

internal data class EvidenceArtifact(
    val sequence: Int,
    val reason: String,
    val directory: Path,
) {
    val screenshot: Path get() = directory.resolve("screenshot.png")
    val hierarchy: Path get() = directory.resolve("hierarchy.xml")
}

// One structured health model feeds the session bar and its subsystem detail menu.
internal enum class Subsystem(val label: String) {
    Adb("ADB"), Device("DEVICE"), Stream("STREAM"), Agent("AGENT"), Session("SESSION")
}

internal enum class HealthLevel { Ok, Degraded, Down, NotApplicable }

internal data class HealthEntry(
    val level: HealthLevel,
    val detail: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

@Composable
@ReadOnlyComposable
internal fun HealthLevel.color(): Color = when (this) {
    HealthLevel.Ok -> SignalGreen
    HealthLevel.Degraded -> SignalAmber
    HealthLevel.Down -> SignalRed
    HealthLevel.NotApplicable -> Ink400
}

internal data class CapturedEvent(
    // Monotonic and unique — `time` is a formatted string and is neither (design.md §8.5).
    val id: Long,
    val time: String,
    val epochMillis: Long,
    val symbol: String,
    val title: String,
    val detail: String,
    val duration: String = "",
    val channel: Channel = Channel.Session,
    val severity: Severity = Severity.Normal,
    /** Raw agent/host JSON when the event came from a structured source. */
    val payload: String? = null,
    /** A `.flow` step this event could contribute, when one is derivable. */
    val suggestedStep: String? = null,
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return title.lowercase().contains(q) || detail.lowercase().contains(q)
    }
}


// design.md §5.4 — selector quality, graded by the host resolver.
internal enum class SelectorStability(val pips: Int, val label: String) {
    High(3, "Unique resource id"),
    Medium(2, "Unique semantic selector"),
    Low(1, "Weak selector — fragile across localisation"),
    Unresolved(0, "Not durable — coordinate only"),
}

internal fun stabilityOf(raw: String?): SelectorStability = when (raw) {
    "high" -> SelectorStability.High
    "medium" -> SelectorStability.Medium
    "low" -> SelectorStability.Low
    else -> SelectorStability.Unresolved
}

@Composable
@ReadOnlyComposable
internal fun SelectorStability.color(): Color = when (this) {
    SelectorStability.High, SelectorStability.Medium -> SignalGreen
    SelectorStability.Low -> SignalAmber
    SelectorStability.Unresolved -> SignalRed
}

// design.md §5.3.2 — transient feedback for one tap. Coordinates are normalised 0..1 against the
// displayed image so the overlay survives any stage resize.
internal data class TapFeedback(
    val id: Long,
    val point: Offset,
    val bounds: Rect?,
    val label: String,
    val stability: SelectorStability,
)


/** Outcome of the last `.flow` run, shown on the editor's run bar (design.md §5.6). */
internal data class FlowRunResult(
    val passed: Boolean,
    val label: String,
    val reportRoot: String?,
)


/**
 * How taps on the device stage behave.
 *
 * Inspect resolves the tapped node (a `uiautomator dump` per tap, hundreds of milliseconds) so the
 * overlay can show the selector and a durable step can be generated. Mirror passes input straight
 * to the control socket — the same experience as using scrcpy directly, with no dump, no overlay
 * and no step generation.
 */
internal enum class InteractionMode(val label: String, val detail: String) {
    Inspect("INSPECT", "Resolves selectors and generates steps"),
    Mirror("MIRROR", "Raw passthrough · no selector, no steps"),
}
