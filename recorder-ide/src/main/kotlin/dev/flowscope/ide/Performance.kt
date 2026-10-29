package dev.flowscope.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * design.md §5.8 — `dumpsys` output distilled into a few readable numbers. The raw text is still
 * available behind the RAW toggle; this only decides what is worth showing first.
 */
internal data class PerformanceSnapshot(
    val javaHeapKb: Long?,
    val nativeHeapKb: Long?,
    val totalPssKb: Long?,
    val totalFrames: Long?,
    val jankyFrames: Long?,
    val jankPercent: Double?,
    val percentiles: Map<Int, Int>,
    val raw: String,
    val target: PerformanceTarget? = null,
    val process: String? = null,
    val collectedAt: String? = null,
) {
    val isEmpty: Boolean
        get() = javaHeapKb == null && nativeHeapKb == null && totalPssKb == null && totalFrames == null
}

private fun Long?.orNull() = this

/** `App Summary` rows look like `           Java Heap:    15908     27368` — the first number is PSS. */
private fun memoryRow(output: String, label: String): Long? {
    val pattern = Regex("""^\s*${Regex.escape(label)}:\s*(\d+)""", RegexOption.MULTILINE)
    return pattern.find(output)?.groupValues?.get(1)?.toLongOrNull()
}

internal fun parsePerformance(output: String): PerformanceSnapshot {
    val totalPss = Regex("""TOTAL PSS:\s*(\d+)""").find(output)?.groupValues?.get(1)?.toLongOrNull()
        ?: memoryRow(output, "TOTAL")

    val totalFrames = Regex("""Total frames rendered:\s*(\d+)""")
        .find(output)?.groupValues?.get(1)?.toLongOrNull()

    // `Janky frames: 1636 (10.41%)`. The "(legacy)" variant is a different metric — ignore it.
    val janky = Regex("""Janky frames:\s*(\d+)\s*\(([\d.]+)%\)""").find(output)

    val percentiles = Regex("""(\d+)th percentile:\s*(\d+)ms""").findAll(output)
        .mapNotNull { match ->
            val p = match.groupValues[1].toIntOrNull()
            val ms = match.groupValues[2].toIntOrNull()
            if (p == null || ms == null) null else p to ms
        }
        .toMap()

    return PerformanceSnapshot(
        javaHeapKb = memoryRow(output, "Java Heap").orNull(),
        nativeHeapKb = memoryRow(output, "Native Heap").orNull(),
        totalPssKb = totalPss,
        totalFrames = totalFrames,
        jankyFrames = janky?.groupValues?.get(1)?.toLongOrNull(),
        jankPercent = janky?.groupValues?.get(2)?.toDoubleOrNull(),
        percentiles = percentiles,
        raw = output,
    )
}

/** Megabytes with one decimal, or an em dash when the value is absent. */
internal fun formatMegabytes(kb: Long?): String =
    kb?.let { String.format("%.1f", it / 1024.0) } ?: "—"

internal fun formatPercent(value: Double?): String =
    value?.let { String.format("%.1f", it) } ?: "—"

/** design.md §5.8 — frame-time severity: green under 16ms, amber to 32ms, red beyond. */
internal enum class FrameBand { Good, Warn, Bad }

internal fun bandOf(milliseconds: Int): FrameBand = when {
    milliseconds < 16 -> FrameBand.Good
    milliseconds <= 32 -> FrameBand.Warn
    else -> FrameBand.Bad
}

internal data class PerformanceTarget(val serial: String, val packageName: String)
internal enum class CollectionStatus { Complete, Partial, Unavailable, Failed }
internal data class PerformanceCollection(
    val status: CollectionStatus,
    val snapshot: PerformanceSnapshot? = null,
    val message: String,
)

internal fun collectPerformanceSnapshot(adb: dev.flowscope.host.AdbClient, target: PerformanceTarget): PerformanceCollection {
    fun command(vararg words: String) = adb.run(listOf("-s", target.serial, "shell") + words, 15_000)
    return try {
        val pid = command("pidof", target.packageName).stdout().trim()
        if (pid.isEmpty()) return PerformanceCollection(CollectionStatus.Unavailable, message = "App is not running.")
        val memory = command("dumpsys", "meminfo", target.packageName)
        val graphics = command("dumpsys", "gfxinfo", target.packageName)
        if (command("pidof", target.packageName).stdout().trim() != pid) {
            return PerformanceCollection(CollectionStatus.Unavailable, message = "App process changed during collection. Collect again.")
        }
        // Failed commands never contribute metrics or arbitrary stderr to the snapshot.
        val raw = "MEMORY\n${if (memory.exitCode() == 0) memory.stdout().take(64_000) else "Unavailable"}\n\nRENDERING\n${if (graphics.exitCode() == 0) graphics.stdout().take(64_000) else "Unavailable"}"
        val parsed = parsePerformance(raw).copy(target = target, process = pid, collectedAt = java.time.Instant.now().toString())
        val hasMemory = parsed.totalPssKb != null || parsed.javaHeapKb != null || parsed.nativeHeapKb != null
        val hasFrames = parsed.totalFrames != null
        val status = when {
            hasMemory && hasFrames -> CollectionStatus.Complete
            hasMemory || hasFrames -> CollectionStatus.Partial
            memory.exitCode() != 0 || graphics.exitCode() != 0 -> CollectionStatus.Failed
            else -> CollectionStatus.Unavailable
        }
        PerformanceCollection(status, parsed.takeUnless { it.isEmpty }, when (status) {
            CollectionStatus.Complete -> "Memory and rendering collected."
            CollectionStatus.Partial -> if (hasMemory) "Partial snapshot: rendering data unavailable." else "Partial snapshot: memory data unavailable."
            CollectionStatus.Unavailable -> "The app returned no supported memory or rendering metrics."
            CollectionStatus.Failed -> "Device commands failed; no performance data collected."
        })
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        PerformanceCollection(CollectionStatus.Failed, message = "Performance collection interrupted.")
    } catch (_: Exception) {
        PerformanceCollection(CollectionStatus.Failed, message = "Could not collect performance data from the device.")
    }
}

/** Request generations prevent target switches and delayed results from contaminating comparisons. */
internal class PerformanceState {
    var target by androidx.compose.runtime.mutableStateOf<PerformanceTarget?>(null)
        private set
    var busy by androidx.compose.runtime.mutableStateOf(false)
        private set
    var result by androidx.compose.runtime.mutableStateOf<PerformanceCollection?>(null)
        private set
    var previous by androidx.compose.runtime.mutableStateOf<PerformanceSnapshot?>(null)
        private set
    private var generation = 0L

    fun select(value: PerformanceTarget?) {
        if (target == value) return
        target = value
        generation++
        busy = false
        result = null
        previous = null
    }
    fun begin(): Long { busy = true; return ++generation }
    fun complete(ticket: Long, value: PerformanceCollection) {
        if (ticket != generation) return
        val before = result?.snapshot
        previous = before?.takeIf { it.target == value.snapshot?.target && it.process == value.snapshot?.process }
        result = value
        busy = false
    }
}
