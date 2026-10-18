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
