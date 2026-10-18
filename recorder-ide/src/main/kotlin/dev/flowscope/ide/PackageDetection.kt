package dev.flowscope.ide

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Finds the application ID a FlowScope project is about, so opening a project targets the right app
 * without the user retyping it.
 *
 * `flowscope.json` stays the source of truth — this only answers the case where it has no
 * `defaults.package`, which is exactly what `flowscope project init` writes when `--package` is
 * omitted. Gradle is the only usable signal: modern AGP projects declare `applicationId` in the
 * build script and leave `package` out of `AndroidManifest.xml` entirely, so parsing the manifest
 * would find nothing in any project created in the last several years.
 *
 * Nothing here writes: a detected ID pre-fills the target and is *offered* for `flowscope.json`,
 * never stored behind the user's back. The manifest is checked-in authored input.
 */

/** Directories that cannot contain an authored build script, so the walk never descends into them. */
private val IgnoredDirectories = setOf("build", "node_modules", "buildSrc", "out")

/** Deep enough for `root/app/build.gradle.kts` and a module one level further in. */
private const val MaxDetectionDepth = 4

/** An application ID is at least two dot-separated segments; a bare word is a false positive. */
private val ApplicationIdPattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

private val ApplicationIdDeclaration =
    Regex("""\bapplicationId\s*=?\s*["']([A-Za-z][A-Za-z0-9_.]*)["']""")

private val NamespaceDeclaration =
    Regex("""\bnamespace\s*=?\s*["']([A-Za-z][A-Za-z0-9_.]*)["']""")

/**
 * The application ID declared in one Gradle build script, or null.
 *
 * `applicationId` wins over `namespace`: they are usually equal, but when they differ it is
 * `applicationId` that names the installed package, which is what ADB and every `.flow` header
 * mean by "package". Line comments are stripped first so a commented-out declaration — the usual
 * way a flavour is parked — is not mistaken for a live one.
 */
internal fun applicationIdIn(gradleSource: String): String? {
    val source = stripLineComments(gradleSource)
    val applicationId = ApplicationIdDeclaration.find(source)?.groupValues?.get(1)
    if (applicationId != null && ApplicationIdPattern.matches(applicationId)) return applicationId
    val namespace = NamespaceDeclaration.find(source)?.groupValues?.get(1)
    return namespace?.takeIf { ApplicationIdPattern.matches(it) }
}

/**
 * Removes `//` comments without touching a `//` inside a string — a URL in a build script is
 * common enough that a naive strip would truncate the line that follows it.
 */
private fun stripLineComments(source: String): String = source.lineSequence().joinToString("\n") { line ->
    var quote: Char? = null
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            quote != null -> {
                if (char == '\\') index++
                else if (char == quote) quote = null
            }
            char == '"' || char == '\'' -> quote = char
            char == '/' && index + 1 < line.length && line[index + 1] == '/' ->
                return@joinToString line.substring(0, index)
        }
        index++
    }
    line
}

/**
 * The application ID declared by the Gradle build under [projectRoot], or null when there is none.
 *
 * A FlowScope project often sits beside — or inside — the Android project it tests, so the walk is
 * bounded and ordered rather than exhaustive: scripts under an `app/` directory are read first
 * because that is where the application module lives by convention, and a `build.gradle` that only
 * configures plugins for subprojects yields nothing and is skipped.
 */
internal fun detectApplicationId(projectRoot: Path): String? {
    val root = runCatching { projectRoot.toAbsolutePath().normalize() }.getOrNull() ?: return null
    val scripts = runCatching {
        Files.walk(root, MaxDetectionDepth).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { it.name == "build.gradle" || it.name == "build.gradle.kts" }
                .filter { script -> root.relativize(script).none { it.name.startsWith(".") || it.name in IgnoredDirectories } }
                .toList()
        }
    }.getOrNull().orEmpty()

    return scripts
        .sortedWith(compareBy({ if (it.parent?.name == "app") 0 else 1 }, { it.parent?.nameCount ?: 0 }, { it.toString() }))
        .firstNotNullOfOrNull { script ->
            runCatching { applicationIdIn(Files.readString(script)) }.getOrNull()
        }
}
