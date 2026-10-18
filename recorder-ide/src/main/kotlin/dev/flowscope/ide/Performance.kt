package dev.flowscope.ide

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
