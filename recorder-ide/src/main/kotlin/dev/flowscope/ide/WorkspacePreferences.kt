package dev.flowscope.ide

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Local roots, device selections and assignment metadata; work never resumes automatically. */
internal data class WorkspaceLocation(val root: Path, val serial: String?, val active: Boolean, val role: String? = null, val jobs: List<Path> = emptyList(), val continueOnFailure: Boolean = false)
internal fun formatWorkspaceLocations(rows: List<WorkspaceLocation>): String = rows.joinToString("\n", "v1\n", "\n") {
    fun encode(text: String) = Base64.getUrlEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    "${encode(it.root.toString())}\t${encode(it.serial.orEmpty())}\t${it.active}\t${encode(it.role.orEmpty())}\t${encode(it.jobs.joinToString("\u0000"))}\t${it.continueOnFailure}"
}
internal fun parseWorkspaceLocations(text: String): List<WorkspaceLocation> {
    if (text.lineSequence().firstOrNull() != "v1") return emptyList()
    return text.lineSequence().drop(1).mapNotNull { row -> runCatching {
        val fields = row.split('\t')
        require(fields.size == 3 || fields.size == 6)
        fun decode(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        val root = Path.of(decode(fields[0])).toAbsolutePath().normalize()
        WorkspaceLocation(root, decode(fields[1]).ifBlank { null }, fields[2] == "true",
            if (fields.size == 6) decode(fields[3]).ifBlank { null } else null,
            if (fields.size == 6) decode(fields[4]).split('\u0000').filter { it.isNotEmpty() }.map(Path::of) else emptyList(),
            fields.getOrNull(5) == "true")
    }.getOrNull() }.take(64).toList()
}
internal fun loadWorkspaceLocations(): List<WorkspaceLocation> = runCatching {
    parseWorkspaceLocations(Files.readString(FlowScopeHome.resolve("desktop-workspaces.txt")))
        .filter { Files.isRegularFile(it.root.resolve("flowscope.json")) }
}.getOrDefault(emptyList())
internal fun saveWorkspaceLocations(rows: List<WorkspaceLocation>) {
    val file = FlowScopeHome.resolve("desktop-workspaces.txt")
    Files.createDirectories(file.parent)
    val staging = Files.createTempFile(file.parent, ".workspaces-", ".tmp")
    try {
        Files.writeString(staging, formatWorkspaceLocations(rows))
        Files.move(staging, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(staging) }
}
