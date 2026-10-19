package dev.flowscope.ide

import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure helpers for turning buffers into file names and back. Kept free of the filesystem so they
 * are unit-testable, matching the rest of the IDE's test surface: the caller supplies the directory
 * listing, these functions decide the names.
 */

/** Windows cannot open a file with these stems, and the IDE ships an MSI. */
private val WindowsDeviceNames = setOf(
    "con", "prn", "aux", "nul",
    "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
    "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
)

private const val MaxStemLength = 48

/** The `name "..."` value from a `.flow` or `.suite` buffer, or null when it has none. */
internal fun testNameFromSource(source: String): String? {
    for (raw in source.lineSequence()) {
        val line = raw.trim()
        if (!line.startsWith("name ")) continue
        val value = line.removePrefix("name ").trim()
        if (value.isEmpty()) continue
        return value.removeSurrounding("\"").trim().ifEmpty { null }
    }
    return null
}

/** A safe, readable file name for a test or suite called [testName]. */
internal fun suggestedFileName(testName: String?, extension: String = "flow"): String {
    val stem = (testName ?: "")
        .lowercase()
        .replace("\"", "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(MaxStemLength)
        .trim('-')
        .ifEmpty { "untitled" }
    // A bare device name is unopenable on Windows, so it is prefixed rather than rejected.
    val safe = if (stem in WindowsDeviceNames) "flow-$stem" else stem
    return "$safe.$extension"
}

/** [candidate] if free, otherwise the same name with `-2`, `-3`, … before the extension. */
internal fun uniqueFileName(taken: Set<String>, candidate: String): String {
    if (candidate !in taken) return candidate
    val dot = candidate.lastIndexOf('.')
    val stem = if (dot > 0) candidate.substring(0, dot) else candidate
    val suffix = if (dot > 0) candidate.substring(dot) else ""
    var counter = 2
    while ("$stem-$counter$suffix" in taken) counter++
    return "$stem-$counter$suffix"
}

/** Expands a leading `~` so the project path field accepts what a shell would. */
internal fun expandUserPath(raw: String): Path {
    val trimmed = raw.trim().removeSurrounding("\"")
    val home = System.getProperty("user.home")
    return when {
        trimmed == "~" -> Path.of(home)
        trimmed.startsWith("~/") -> Path.of(home, trimmed.removePrefix("~/"))
        else -> Path.of(trimmed)
    }.toAbsolutePath().normalize()
}

/**
 * The canonical on-disk form of a buffer. Saving writes this, so comparing against it is the only
 * way a dirty check can agree with what is actually on disk.
 */
internal fun flowFileContents(source: String): String = source.trimEnd() + "\n"

/**
 * Derived rather than stored: the buffer is mutated from the editor, every step-card action, and
 * every recorded event, so a stored flag would drift.
 */
internal fun isDirty(source: String, saved: String): Boolean =
    flowFileContents(source) != flowFileContents(saved)

/** `.suite` buffers validate and highlight differently from `.flow` buffers. */
internal fun editorKindOf(file: Path?): EditorKind =
    if (file?.fileName?.toString()?.endsWith(".suite") == true) EditorKind.Suite else EditorKind.Flow

/**
 * A path as the user would say it, for status lines and the telemetry strip.
 *
 * Inside the open project a path is shown relative to it (`reports/20261018-101500-Login/`), which
 * is both shorter and more meaningful than a truncated absolute path — the project root is already
 * named in the chrome, so repeating it in every status line spends the width that would have shown
 * the part that actually changed. Outside the project, `~` stands in for the home directory.
 */
internal fun displayPath(path: Path, projectRoot: Path?): String {
    val absolute = runCatching { path.toAbsolutePath().normalize() }.getOrElse { return path.toString() }
    val root = projectRoot?.let { runCatching { it.toAbsolutePath().normalize() }.getOrNull() }
    if (root != null && absolute.startsWith(root)) {
        val relative = root.relativize(absolute).toString().replace('\\', '/')
        return relative.ifEmpty { "." }
    }
    val home = runCatching { Path.of(System.getProperty("user.home")).normalize() }.getOrNull()
    if (home != null && absolute.startsWith(home) && absolute != home) {
        return "~/" + home.relativize(absolute).toString().replace('\\', '/')
    }
    return absolute.toString()
}

/**
 * Rewrites every `test <oldReference>` line to point at [newReference], leaving everything else —
 * indentation, comments, directives, blank lines — byte-identical.
 *
 * A textual rewrite rather than a parse-and-reserialise: a suite is a checked-in file someone
 * formatted deliberately, and renaming one test must not reflow the rest of it into a diff nobody
 * asked for.
 */
internal fun rewriteSuiteReference(
    source: String,
    oldReference: String,
    newReference: String,
): String {
    if (oldReference == newReference) return source
    val lines = source.split("\n")
    val rewritten = lines.map { line ->
        val indent = line.takeWhile { it.isWhitespace() }
        val body = line.substring(indent.length)
        if (!body.startsWith("test ") && !body.startsWith("test\t")) return@map line
        val value = body.removePrefix("test").trim()
        if (unquoteReference(value) != oldReference) return@map line
        indent + "test " + quoteReference(newReference)
    }
    return rewritten.joinToString("\n")
}

/** Does any `test` line in [source] name [reference]? */
internal fun suiteReferences(source: String, reference: String): Boolean =
    source.lineSequence().any { line ->
        val body = line.trimStart()
        (body.startsWith("test ") || body.startsWith("test\t")) &&
            unquoteReference(body.removePrefix("test").trim()) == reference
    }

private fun unquoteReference(raw: String): String =
    if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
        raw.substring(1, raw.length - 1).replace("\\\"", "\"")
    } else raw

/** The `.flow` tokenizer splits on whitespace, so a path containing a space has to stay quoted. */
private fun quoteReference(reference: String): String =
    if (reference.any { it.isWhitespace() } || reference.contains('"')) {
        "\"" + reference.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    } else reference

/**
 * Shows [path] in the host file manager, selecting the item rather than only opening a folder.
 *
 * `Desktop.open` on a file launches whatever application owns it — opening a `.flow` in a text
 * editor when the user asked to see where it lives. The platform reveal commands select the item
 * in its parent instead, which is what "reveal" means on both systems. Falls back to `Desktop` on
 * anything else, and never throws: failing to open a file manager must not disturb a run.
 */
internal fun revealInFileManager(path: Path) {
    val target = path.toAbsolutePath().normalize()
    runCatching {
        when {
            IsMac -> ProcessBuilder("open", "-R", target.toString()).start()
            System.getProperty("os.name").orEmpty().startsWith("Windows") ->
                ProcessBuilder("explorer.exe", "/select,${target}").start()
            else -> {
                val folder = if (Files.isDirectory(target)) target else target.parent
                if (folder != null && java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(folder.toFile())
                }
                null
            }
        }
    }
}

/** Opens [path] with whatever application owns it (used for `flowscope.json`). */
internal fun openWithDefaultApplication(path: Path) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().open(path.toAbsolutePath().normalize().toFile())
        }
    }
}

/**
 * The path [text] names, when the whole string is one and it exists on disk — otherwise null.
 *
 * Deliberately strict: it must be absolute and the entire trimmed string, so a sentence that
 * merely mentions a directory never turns into a clickable affordance that reveals the wrong
 * thing, or nothing at all.
 */
internal fun existingPathIn(text: String): Path? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.length > 4096 || trimmed.any { it == '\n' }) return null
    val looksAbsolute = trimmed.startsWith("/") || trimmed.matches(Regex("^[A-Za-z]:[\\\\/].*"))
    if (!looksAbsolute) return null
    return runCatching { Path.of(trimmed).takeIf { Files.exists(it) } }.getOrNull()
}

/**
 * The file remembered for [root] in [recents], when it still belongs to that project.
 *
 * Deliberately strict about containment. A remembered path is only ever a *hint* about where the
 * user left off, and before this check the IDE would record the outgoing project's open file
 * against the incoming project's root — producing entries like
 * `…/examples/sample-app<TAB>../../../../Other/flows/tests/login.flow`, which then reopened another
 * project's test under the new project's name. An entry that escapes the root is dropped rather
 * than repaired: there is no way to tell which project it was meant for.
 *
 * Existence is the caller's job — this stays free of the filesystem so it can be unit-tested.
 */
internal fun recentOpenFileFor(root: Path, recents: List<RecentProject>): Path? {
    val normalisedRoot = runCatching { root.toAbsolutePath().normalize() }.getOrNull() ?: return null
    val entry = recents.firstOrNull {
        runCatching { it.root.toAbsolutePath().normalize() == normalisedRoot }.getOrDefault(false)
    } ?: return null
    val relative = entry.lastOpenFile?.trim()?.ifEmpty { null } ?: return null
    return runCatching {
        val candidate = Path.of(relative)
        if (candidate.isAbsolute) return null
        normalisedRoot.resolve(candidate).normalize().takeIf { it.startsWith(normalisedRoot) }
    }.getOrNull()
}

/**
 * Why [path] could not be opened, in words, with [shown] as its display label.
 *
 * `NoSuchFileException.getMessage()` is **only the path** — so a status line built from it reads
 * exactly like the `Opened · <path>` message it replaces, and the user watches one path turn into
 * another and concludes the click never landed. Any message that is just the path again is
 * therefore treated as no message at all.
 *
 * Pure, so the wording is unit-testable without a filesystem.
 */
internal fun openFailureMessage(path: Path, failure: Throwable, shown: String): String {
    val reason = when (failure) {
        is java.nio.file.NoSuchFileException -> "the file no longer exists"
        is java.nio.file.AccessDeniedException -> "permission denied"
        else -> failure.message
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != path.toString() && it != shown }
    }
    return if (reason == null) "Could not open $shown" else "Could not open $shown · $reason"
}

/** True when [failure] means the file is simply not there any more, so the rail is out of date. */
internal fun isMissingFile(failure: Throwable): Boolean = failure is java.nio.file.NoSuchFileException
