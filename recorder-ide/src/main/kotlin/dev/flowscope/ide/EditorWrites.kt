package dev.flowscope.ide

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** All desktop editors share a write boundary: a stale session must not overwrite another editor. */
internal object EditorWrites {
    @Synchronized
    fun writeManifest(project: dev.flowscope.host.FlowProject, manifest: dev.flowscope.host.ProjectManifest) {
        check(dev.flowscope.host.ProjectManifest.read(project.manifestPath()) == project.manifest()) {
            "Project settings changed in another session. Reload the project before saving settings."
        }
        dev.flowscope.host.ProjectManifest.write(project.manifestPath(), manifest)
    }

    @Synchronized
    fun write(target: Path, contents: String, expected: String?) {
        Files.createDirectories(target.parent)
        if (expected == null) {
            Files.writeString(target, contents, StandardOpenOption.CREATE_NEW)
            return
        }
        check(Files.isRegularFile(target) && flowFileContents(Files.readString(target)) == flowFileContents(expected)) {
            "File changed in another session or on disk. Reopen it, or use Save as to keep these edits."
        }
        Files.writeString(target, contents)
    }
}
