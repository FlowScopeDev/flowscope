package dev.flowscope.ide

import dev.flowscope.host.RunArtifacts
import dev.flowscope.host.RunReport
import dev.flowscope.host.RunSteps
import java.nio.file.Files
import java.nio.file.Path

/** Reads only existing runner output; never collects device data or persists runtime values. */
internal data class RunInspection(
    val name: String = "Selected run",
    val running: Boolean = false,
    val instrumented: Boolean? = null,
    val events: List<CapturedEvent> = emptyList(),
    val artifacts: List<EvidenceArtifact> = emptyList(),
    val timings: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** Also rejects symlink escapes. A child report's artifacts must stay inside that child's folder. */
internal fun containedFile(root: Path, reference: String): Path? = runCatching {
    RunArtifacts.containedFile(root, reference)
}.getOrNull()

private fun readObject(root: Path, reference: String, notes: MutableList<String>): Map<String, Any?> {
    val lexical = root.resolve(reference)
    val file = containedFile(root, reference)
    if (file == null) {
        if (Files.exists(lexical, java.nio.file.LinkOption.NOFOLLOW_LINKS)) notes += "Unavailable or unsafe file: $reference"
        return emptyMap()
    }
    return runCatching { RunArtifacts.readObject(root, reference) }.getOrElse {
        notes += "Incomplete or unreadable file: $reference"
        emptyMap()
    }
}

private fun Any?.obj(): Map<*, *> = this as? Map<*, *> ?: emptyMap<Any, Any>()
private fun Any?.objects(): List<Map<*, *>> = (this as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
private fun Map<*, *>.text(key: String): String = this[key] as? String ?: ""
private fun Map<*, *>.number(key: String): Long? = this[key] as? Long

internal fun actionChannel(action: String): Channel = when (action) {
    "api-request" -> Channel.Network
    "launch", "force-stop", "clear-data", "grant-permission", "revoke-permission", "open-link", "install-apk", "uninstall-app" -> Channel.Lifecycle
    "tap", "tap-if-visible", "type", "scroll", "scroll-to", "swipe", "long-press", "back", "press-key", "clear-text", "hide-keyboard", "wait-visible", "assert-visible", "assert-not-visible", "if-visible", "screenshot", "screenshot-if-visible" -> Channel.Ui
    else -> if (action.startsWith("web-")) Channel.Ui else Channel.Session
}

internal fun agentChannel(kind: String): Channel = when {
    kind.startsWith("network.") -> Channel.Network
    kind.startsWith("background.") -> Channel.Background
    kind.startsWith("activity.") || kind.startsWith("navigation") -> Channel.Lifecycle
    kind.startsWith("ui.") || kind == "semantic.action" -> Channel.Ui
    else -> Channel.Session
}

internal fun channelGuidance(channel: Channel, run: Boolean, instrumented: Boolean?, recording: Boolean): String = when {
    channel == Channel.Logs && run -> "Logcat is not collected by test runs. Start a recording to capture app warnings and errors."
    channel in setOf(Channel.Network, Channel.Background) && instrumented == false ->
        "Zero-code does not observe app ${channel.label.lowercase()}. App events require instrumented recording and ${if (channel == Channel.Network) "network" else "background-work"} hooks.${if (channel == Channel.Network && run) " Host API steps appear here as runner actions." else ""}"
    channel in setOf(Channel.Network, Channel.Background) ->
        "App ${channel.label.lowercase()} events require explicit debug-agent hooks.${if (run) " Captured events appear after each test report is written." else ""}"
    run -> "No recorded ${channel.label.lowercase()} events for this run. Runner steps update live; agent events appear after each test report is written."
    recording -> "Waiting for ${channel.label.lowercase()} events. Capture failures appear in Session."
    else -> "Start recording to capture ${channel.label.lowercase()} events."
}

private fun groupLabel(group: RunSteps.Group): String =
    if (group.id().startsWith("test:")) "Test ${group.id().substringAfter(':')} · ${group.name()}" else group.name()

internal fun readRunInspection(directory: Path): RunInspection {
    val notes = mutableListOf<String>()
    if (!Files.isDirectory(directory)) return RunInspection(notes = listOf("Run folder is unavailable."))
    // RunSteps already checks child references; reject unsafe root inputs before handing them over.
    for (name in listOf("run-plan.json", "progress.jsonl", RunReport.TEST_REPORT, RunReport.SUITE_REPORT)) {
        if (Files.exists(directory.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS) && containedFile(directory, name) == null) {
            return RunInspection(notes = listOf("Unsafe run metadata: $name"))
        }
    }
    val summary = RunReport.read(directory).orElse(null)
    val steps = RunSteps.read(directory, summary)
    val events = mutableListOf<CapturedEvent>()
    val artifacts = mutableListOf<EvidenceArtifact>()
    val timings = mutableListOf<String>()
    val modes = mutableSetOf<String>()
    val reports = linkedMapOf<Path, String>()
    if (summary?.isSuite == true || steps.groups().any { it.id().startsWith("test:") }) {
        for (group in steps.groups()) {
            if (group.report().isBlank()) continue
            val file = containedFile(directory, group.report())
            if (file != null && file.fileName.toString() == RunReport.TEST_REPORT) reports[file.parent] = groupLabel(group)
            else if (group.status() !in setOf("pending", "running", "skipped")) notes += "${groupLabel(group)}: report missing or unsafe."
            // Plans point at a not-yet-written report; allow scanning its existing, contained folder.
            if (file == null) runCatching {
                val ref = Path.of(group.report())
                require(!ref.isAbsolute && ref.none { it.toString() == ".." } && '\\' !in group.report())
                val child = directory.resolve(ref).parent.toRealPath()
                require(child.startsWith(directory.toRealPath()) && ref.fileName.toString() == RunReport.TEST_REPORT)
                reports[child] = groupLabel(group)
            }
        }
    } else reports[directory] = summary?.name() ?: "Test"
    for ((groupIndex, group) in steps.groups().withIndex()) {
        for (step in group.steps()) {
            events += CapturedEvent(
                id = (groupIndex + 1L) * 1_000_000 + step.number(), time = "—", epochMillis = 0,
                symbol = when (step.status()) { "passed" -> "✓"; "failed" -> "×"; "running" -> "▶"; else -> "·" },
                title = "Runner · ${step.action()}",
                detail = "${groupLabel(group)} · ${step.phase()} #${step.number()} · ${step.status()} · line ${step.line()}",
                channel = actionChannel(step.action()),
                severity = if (step.status() == "failed") Severity.Error else Severity.Normal,
            )
        }
    }
    var agentId = 1_000_000_000L
    for ((root, label) in reports) {
        val report = readObject(root, RunReport.TEST_REPORT, notes)
        report.text("mode").takeIf(String::isNotBlank)?.let(modes::add)
        val used = mutableSetOf<Path>()
        fun addArtifact(imageRef: String, treeRef: String, reason: String) {
            val image = imageRef.takeIf(String::isNotBlank)?.let { containedFile(root, it) }
            val tree = treeRef.takeIf(String::isNotBlank)?.let { containedFile(root, it) }
            if (image == null && tree == null) return
            if (image != null && image in used && (tree == null || tree in used)) return
            listOfNotNull(image, tree).forEach { used.add(it) }
            artifacts += EvidenceArtifact(artifacts.size + 1, "$label · $reason", root, image, tree)
        }
        for (step in report["steps"].objects()) {
            val details = step["details"].obj()
            val number = step.number("number")
            val image = details.text("evidence")
            val tree = details.text("hierarchy")
            for (ref in listOf(image, tree).filter(String::isNotBlank)) {
                if (containedFile(root, ref) == null) notes += "$label · step $number: artifact missing or unsafe."
            }
            addArtifact(image, tree, "step $number · ${step.text("action")}")
            if (step.text("status") == "failed" && number != null) {
                val base = "evidence/failure-%02d".format(number)
                addArtifact("$base.png", "$base.xml", "step $number · failure")
            }
            if (details.text("hierarchyError").isNotEmpty()) notes += "$label · step $number: hierarchy capture failed."
        }
        val evidence = root.resolve("evidence")
        if (Files.isDirectory(evidence) && runCatching { evidence.toRealPath().startsWith(root.toRealPath()) }.getOrDefault(false)) {
            Files.list(evidence).use { paths ->
                paths.sorted().forEach { path ->
                    val name = path.fileName.toString()
                    val safe = containedFile(root, "evidence/$name")
                    if (safe == null) notes += "$label: unsafe artifact ignored."
                    else if (safe !in used) when {
                        name.endsWith(".png") -> addArtifact("evidence/$name", "", name)
                        name.endsWith(".xml") -> addArtifact("", "evidence/$name", name)
                    }
                }
            }
        }
        val perf = report["performance"].obj()
        if (perf.isNotEmpty()) {
            timings += "$label · ${perf.number("totalMillis") ?: "—"} ms · ${perf.number("uiDumps") ?: "—"} hierarchy dumps · ${perf.number("uiDumpMillis") ?: "—"} ms dumping"
            perf["slowestSteps"].objects().forEach {
                timings += "  #${it.number("number")} ${it.text("action")} · ${it.number("durationMillis")} ms"
            }
        }
        val agent = report["agentEvents"].obj()
        if (agent["captured"] == false) notes += "$label: ${agent.text("note").ifBlank { "agent events unavailable" }}"
        for (event in agent["events"].objects()) {
            val offset = event.number("offsetMillis")
            val kind = event.text("kind")
            events += CapturedEvent(++agentId, offset?.let { "+${it}ms" } ?: "—", 0, "•",
                "App · $kind", "$label · ${event.text("summary")}", channel = agentChannel(kind))
        }
    }
    if (summary == null) notes += "Report not yet available or unreadable."
    if (summary?.isRunning == true) notes += "Steps update live. Agent events and timing appear when each test report is written."
    return RunInspection(summary?.name() ?: "Selected run", summary?.isRunning == true,
        if (modes.isEmpty()) null else "instrumented" in modes, events, artifacts, timings, notes.distinct())
}
