package dev.flowscope.ide

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import dev.flowscope.host.FlowProject
import java.nio.file.Path

/**
 * Small user preferences kept under `~/.flowscope/`, per CLAUDE.md: never relative to the app
 * bundle or the process working directory. Plain text, one entry per line — no schema to version.
 */
internal object Preferences {
    private val favouritesFile: Path get() = FlowScopeHome.resolve("favourite-packages.txt")
    private val recentFile: Path get() = FlowScopeHome.resolve("recent-packages.txt")
    private val themeFile: Path get() = FlowScopeHome.resolve("theme.txt")
    private val recentProjectsFile: Path get() = FlowScopeHome.resolve("recent-projects.txt")

    fun loadThemeMode(): ThemeMode = runCatching {
        if (!Files.exists(themeFile)) ThemeMode.System
        else parseThemeMode(Files.readString(themeFile, StandardCharsets.UTF_8))
    }.getOrDefault(ThemeMode.System)

    fun saveThemeMode(mode: ThemeMode) {
        runCatching {
            Files.createDirectories(themeFile.parent)
            Files.writeString(themeFile, formatThemeMode(mode), StandardCharsets.UTF_8)
        }
    }

    fun loadFavourites(): Set<String> = runCatching {
        if (!Files.exists(favouritesFile)) emptySet()
        else Files.readAllLines(favouritesFile, StandardCharsets.UTF_8)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }.getOrDefault(emptySet())

    fun saveFavourites(favourites: Set<String>) {
        runCatching {
            Files.createDirectories(favouritesFile.parent)
            Files.writeString(
                favouritesFile,
                favourites.sorted().joinToString("\n", postfix = "\n"),
                StandardCharsets.UTF_8,
            )
        }
    }

    /** Last package used against each device serial. */
    fun loadRecentPackages(): Map<String, String> = runCatching {
        if (!Files.exists(recentFile)) emptyMap()
        else parseRecentPackages(Files.readAllLines(recentFile, StandardCharsets.UTF_8))
    }.getOrDefault(emptyMap())

    fun saveRecentPackage(serial: String, packageName: String) {
        runCatching {
            val updated = loadRecentPackages() + (serial to packageName)
            Files.createDirectories(recentFile.parent)
            Files.writeString(
                recentFile,
                updated.entries.sortedBy { it.key }
                    .joinToString("\n", postfix = "\n") { "${it.key}\t${it.value}" },
                StandardCharsets.UTF_8,
            )
        }
    }

    /**
     * Recently opened projects, newest first. The project's *name* is deliberately not cached
     * here — `flowscope.json` is its single source of truth, and a copy would go stale.
     */
    fun loadRecentProjects(): List<RecentProject> = runCatching {
        if (!Files.exists(recentProjectsFile)) emptyList()
        else parseRecentProjects(Files.readAllLines(recentProjectsFile, StandardCharsets.UTF_8))
            // A project the user has since moved or deleted must not linger in the menu.
            .filter { Files.isRegularFile(it.root.resolve(FlowProject.MANIFEST_NAME)) }
    }.getOrDefault(emptyList())

    fun rememberRecentProject(root: Path, openFile: Path?) {
        runCatching {
            val relative = openFile?.let { file ->
                runCatching { root.relativize(file).toString().replace('\\', '/') }.getOrNull()
            }
            val entry = listOfNotNull(root.toAbsolutePath().normalize().toString(), relative)
                .joinToString("\t")
            val existing = if (Files.exists(recentProjectsFile)) {
                Files.readAllLines(recentProjectsFile, StandardCharsets.UTF_8)
            } else emptyList()
            Files.createDirectories(recentProjectsFile.parent)
            Files.writeString(
                recentProjectsFile,
                mergeRecentProject(existing, entry).joinToString("\n", postfix = "\n"),
                StandardCharsets.UTF_8,
            )
        }
    }

    fun forgetRecentProject(root: Path) {
        runCatching {
            if (!Files.exists(recentProjectsFile)) return@runCatching
            val target = root.toAbsolutePath().normalize().toString()
            val kept = Files.readAllLines(recentProjectsFile, StandardCharsets.UTF_8)
                .filter { it.split("\t", limit = 2).firstOrNull()?.trim() != target }
            Files.writeString(
                recentProjectsFile,
                kept.joinToString("\n", postfix = "\n"),
                StandardCharsets.UTF_8,
            )
        }
    }

    /** Writes an exported timeline and returns its path, or null when it could not be written. */
    fun writeExport(fileName: String, contents: String): Path? = runCatching {
        val target = FlowScopeHome.resolve("exports").resolve(fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, contents, StandardCharsets.UTF_8)
        target
    }.getOrNull()
}

internal fun formatThemeMode(mode: ThemeMode): String = "${mode.persistedValue}\n"

/** `serial<TAB>package` per line. Tab-separated because neither field can contain a tab. */
internal fun parseRecentPackages(lines: List<String>): Map<String, String> =
    lines.mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val parts = line.split("\t", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) null
        else parts[0].trim() to parts[1].trim()
    }.toMap()

/**
 * `absoluteRoot<TAB>lastOpenRelativePath` per line; the second field is optional. Only trailing
 * whitespace is stripped — trimming the whole line would swallow an empty first field and promote
 * the remembered file to the root position.
 */
internal fun parseRecentProjects(lines: List<String>): List<RecentProject> =
    lines.mapNotNull { raw ->
        val line = raw.trimEnd()
        if (line.isBlank() || line.trimStart().startsWith("#")) return@mapNotNull null
        val parts = line.split("\t", limit = 2)
        val root = parts[0]
        // A relative root is meaningless: the IDE's working directory is arbitrary.
        if (root.isBlank() || !Path.of(root).isAbsolute) return@mapNotNull null
        val openFile = parts.getOrNull(1)?.trim()?.ifEmpty { null }
        RecentProject(Path.of(root), openFile)
    }.distinctBy { it.root }

/** Moves [added] to the front, de-duplicates on the root path, and caps the list. */
internal fun mergeRecentProject(
    existing: List<String>,
    added: String,
    cap: Int = RecentProjectCap,
): List<String> {
    val addedRoot = added.split("\t", limit = 2).first()
    val kept = existing
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .filter { it.split("\t", limit = 2).first() != addedRoot }
    return (listOf(added) + kept).take(cap)
}

internal const val RecentProjectCap = 10

/**
 * Serialises one captured event as a single JSON object, so a session's timeline can be exported
 * as JSONL and consumed by an agent or a CI job without the IDE.
 */
internal fun CapturedEvent.toJsonLine(): String = buildString {
    append('{')
    appendJsonField("time", time)
    append(','); appendJsonField("epochMillis", epochMillis.toString(), quoted = false)
    append(','); appendJsonField("channel", channel.name)
    append(','); appendJsonField("severity", severity.name)
    append(','); appendJsonField("title", title)
    append(','); appendJsonField("detail", detail)
    if (duration.isNotEmpty()) { append(','); appendJsonField("duration", duration) }
    suggestedStep?.let { append(','); appendJsonField("suggestedStep", it) }
    payload?.let { append(','); appendJsonField("payload", it) }
    append('}')
}

private fun StringBuilder.appendJsonField(key: String, value: String, quoted: Boolean = true) {
    append('"').append(escapeJson(key)).append("\":")
    if (quoted) append('"').append(escapeJson(value)).append('"') else append(value)
}

internal fun escapeJson(value: String): String = buildString {
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
    }
}

/**
 * Orders package suggestions: favourites first, then prefix matches, then the rest. Pure so the
 * ordering can be tested without a device.
 */
internal fun rankPackages(
    packages: List<String>,
    query: String,
    favourites: Set<String>,
): List<String> {
    val q = query.trim().lowercase()
    return packages
        .filter { q.isEmpty() || it.lowercase().contains(q) }
        .sortedWith(
            compareBy(
                { if (it in favourites) 0 else 1 },
                { if (q.isNotEmpty() && it.lowercase().startsWith(q)) 0 else 1 },
                { it },
            ),
        )
}
