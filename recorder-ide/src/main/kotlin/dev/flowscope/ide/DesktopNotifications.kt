package dev.flowscope.ide

import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/** A sanitized, user-facing summary of a finished IDE run. */
internal data class CompletionNotice(
    val title: String,
    val message: String,
    val failed: Boolean,
)

internal fun testCompletionNotice(name: String, passed: Boolean, steps: Int): CompletionNotice =
    CompletionNotice(
        title = "FlowScope · Test ${if (passed) "passed" else "failed"}",
        message = if (passed) {
            "$name · $steps/$steps steps passed"
        } else {
            "$name · Open FlowScope for failure details"
        },
        failed = !passed,
    )

internal fun regressionCompletionNotice(
    name: String,
    passed: Boolean,
    total: Int,
    passedCount: Int,
    failedCount: Int,
): CompletionNotice {
    val skipped = (total - passedCount - failedCount).coerceAtLeast(0)
    val counts = buildList {
        add("$passedCount passed")
        if (failedCount > 0) add("$failedCount failed")
        if (skipped > 0) add("$skipped skipped")
    }.joinToString(" · ")
    return CompletionNotice(
        title = "FlowScope · Regression ${if (passed) "passed" else "failed"}",
        message = "$name · $counts",
        failed = !passed,
    )
}

internal fun runErrorNotice(name: String, regression: Boolean): CompletionNotice = CompletionNotice(
    title = "FlowScope · ${if (regression) "Regression" else "Test"} failed",
    message = "$name · Run ended with an error; open FlowScope for details",
    failed = true,
)

/**
 * Best-effort native notifications for runs started in the IDE. A notification problem must never
 * change a test result, so every platform operation is isolated and failures are deliberately
 * ignored. macOS uses Notification Center directly; Windows and other desktop platforms use the
 * AWT system tray when it is available.
 */
internal object DesktopNotifications : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "flowscope-notifications").apply { isDaemon = true }
    }
    private var trayIcon: TrayIcon? = null

    fun show(notice: CompletionNotice) {
        runCatching {
            if (System.getProperty("os.name", "").startsWith("Mac", ignoreCase = true)) {
                executor.execute { showMac(notice) }
            } else {
                EventQueue.invokeLater { showTray(notice) }
            }
        }
    }

    private fun showMac(notice: CompletionNotice) {
        runCatching {
            // Values are script arguments, not interpolated AppleScript, so names cannot inject
            // commands or break quoting.
            ProcessBuilder(
                "osascript",
                "-e", "on run argv",
                "-e", "display notification (item 2 of argv) with title (item 1 of argv)",
                "-e", "end run",
                notice.title,
                notice.message,
            )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
    }

    private fun showTray(notice: CompletionNotice) {
        runCatching {
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return
            val icon = trayIcon ?: createTrayIcon().also {
                SystemTray.getSystemTray().add(it)
                trayIcon = it
            }
            icon.displayMessage(
                notice.title,
                notice.message,
                if (notice.failed) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.INFO,
            )
        }
    }

    private fun createTrayIcon(): TrayIcon {
        val stream = DesktopNotifications::class.java.getResourceAsStream("/icons/FlowScope.png")
            ?: error("FlowScope icon is missing")
        val image = stream.use(ImageIO::read)
            .getScaledInstance(32, 32, Image.SCALE_SMOOTH)
        return TrayIcon(image, "FlowScope").apply { isImageAutoSize = true }
    }

    override fun close() {
        runCatching { executor.shutdownNow() }
        EventQueue.invokeLater {
            trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
            trayIcon = null
        }
    }
}
