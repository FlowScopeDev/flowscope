package dev.flowscope.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.flowscope.host.FlowProject
import dev.flowscope.host.ProjectManifest
import dev.flowscope.host.RunReport
import java.nio.file.Path

/**
 * The open project and what it contains. A project is the authoring root: its `flows/tests` and
 * `flows/suites` are what the file rail lists, and its `reports/` is where runs land.
 *
 * <p>A project is never required — with none open the IDE falls back to `~/.flowscope/`, exactly as
 * it behaved before projects existed.
 */
internal class ProjectState {
    var project by mutableStateOf<FlowProject?>(null)
    var tests by mutableStateOf<List<ProjectEntry>>(emptyList())
    var suites by mutableStateOf<List<ProjectEntry>>(emptyList())
    var runs by mutableStateOf<List<RunReport>>(emptyList())
    var recent by mutableStateOf<List<RecentProject>>(emptyList())
    var scratch by mutableStateOf<List<ProjectEntry>>(emptyList())
    var status by mutableStateOf("No project open")
    var railOpen by mutableStateOf(true)

    /**
     * Files the rail listed that turned out to be gone when the user tried to open one.
     *
     * The listing is a snapshot: delete a `.flow` outside the IDE and its row stays, stays
     * clickable, and every click fails identically. Dropping the row on the next refresh would fix
     * the clicking but make the file vanish with no explanation, so the row is kept and marked
     * instead. Cleared when the project changes, and when the file comes back.
     */
    var missing by mutableStateOf<Set<Path>>(emptySet())

    /**
     * Marks [path] as gone, as one row.
     *
     * `plusElement`, never `+`: `java.nio.file.Path` is itself an `Iterable<Path>` over its name
     * elements, so `missing + path` binds to the `Iterable` overload and adds a row per segment —
     * which is exactly what happened, filling the rail with entries called `Users`, `Documents`
     * and `Personal`.
     */
    fun markMissing(path: Path) {
        missing = missing.plusElement(path)
    }

    /**
     * An application ID found in the project's own Gradle build, when `flowscope.json` names none.
     * Offered in project settings rather than written: the manifest is authored, checked-in input.
     */
    var detectedPackage by mutableStateOf<String?>(null)

    /**
     * True until the startup restore has finished deciding whether a project is open. Recording
     * started in the first seconds after launch must wait for this, or the new test is filed under
     * the no-project fallback while the project is still being reopened.
     */
    var restoring by mutableStateOf(true)

    val root: Path? get() = project?.root()
    val manifest: ProjectManifest? get() = project?.manifest()
    val label: String get() = manifest?.name() ?: "No project"
    val isOpen: Boolean get() = project != null

    /** Where a new or saved test belongs: the project's tests dir, or the no-project fallback. */
    val testsDirectory: Path get() = project?.testsDir() ?: FlowScopeHome.resolve("tests")
    val suitesDirectory: Path? get() = project?.suitesDir()
    val reportsDirectory: Path get() = project?.reportsDir() ?: FlowScopeHome.resolve("test-runs")
}

/** A `.flow` or `.suite` file, with the label the rail shows for it. */
internal data class ProjectEntry(val path: Path, val label: String)

internal data class RecentProject(val root: Path, val lastOpenFile: String?)

internal enum class EditorKind { Flow, Suite }
