package dev.flowscope.ide

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * Picks a directory with the host's own dialog. FlowScope had no file dialog at all before
 * projects; this is the whole of it.
 *
 * <p>**Must be called on the Swing event thread.** Compose Desktop's main dispatcher *is* the EDT,
 * so call this directly from a command lambda and never inside `withContext(Dispatchers.IO)` —
 * every other blocking call in the IDE goes the other way, and getting this one wrong hangs the
 * app on a modal dialog that can never paint.
 */
internal fun chooseDirectory(parent: Frame?, title: String): Path? =
    if (IsMac) chooseWithAwt(parent, title) else chooseWithSwing(parent, title)

/** Selects one existing file without reading it into IDE state. */
internal fun chooseFile(parent: Frame?, title: String): Path? = runCatching {
    if (IsMac) {
        val dialog = FileDialog(parent, title, FileDialog.LOAD)
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        Path.of(directory, file).toAbsolutePath().normalize()
    } else {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
        }
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null
        chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize()
    }
}.getOrNull()

/**
 * macOS: `FileDialog` is the native NSOpenPanel, and this client property is the only way to make
 * it select a directory rather than a file.
 */
private fun chooseWithAwt(parent: Frame?, title: String): Path? {
    val property = "apple.awt.fileDialogForDirectories"
    val previous = System.getProperty(property)
    return try {
        System.setProperty(property, "true")
        val dialog = FileDialog(parent, title, FileDialog.LOAD)
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        Path.of(directory, file).toAbsolutePath().normalize()
    } catch (failure: Exception) {
        null
    } finally {
        if (previous == null) System.clearProperty(property)
        else System.setProperty(property, previous)
    }
}

/** Everywhere else: `FileDialog` cannot select a directory, so Swing's chooser is the only option. */
private fun chooseWithSwing(parent: Frame?, title: String): Path? = runCatching {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = false
    }
    if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null
    chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize()
}.getOrNull()
