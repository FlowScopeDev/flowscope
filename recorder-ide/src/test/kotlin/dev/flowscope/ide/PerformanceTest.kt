package dev.flowscope.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerformanceTest {

    // Shape taken from a real device (SM_A075F, Android 14).
    private val sample = """
        MEMORY
         App Summary
                               Pss(KB)                        Rss(KB)
                                ------                         ------
                   Java Heap:    15908                          27368
                 Native Heap:    16016                          17196
                        Code:   147732                         239196
                   TOTAL PSS:   218710            TOTAL RSS:   311196       TOTAL SWAP PSS:      353

        RENDERING
        Total frames rendered: 15718
        Janky frames: 1636 (10.41%)
        Janky frames (legacy): 10003 (63.64%)
        50th percentile: 13ms
        90th percentile: 30ms
        95th percentile: 48ms
    """.trimIndent()

    @Test
    fun `reads the app summary rows`() {
        val snapshot = parsePerformance(sample)
        assertEquals(15908L, snapshot.javaHeapKb)
        assertEquals(16016L, snapshot.nativeHeapKb)
        assertEquals(218710L, snapshot.totalPssKb)
    }

    @Test
    fun `reads frame statistics`() {
        val snapshot = parsePerformance(sample)
        assertEquals(15718L, snapshot.totalFrames)
        assertEquals(1636L, snapshot.jankyFrames)
        assertEquals(10.41, snapshot.jankPercent)
    }

    @Test
    fun `ignores the legacy janky metric`() {
        // 63.64% is a different measurement; reporting it as jank would be wrong.
        assertEquals(10.41, parsePerformance(sample).jankPercent)
    }

    @Test
    fun `collects percentiles`() {
        val snapshot = parsePerformance(sample)
        assertEquals(mapOf(50 to 13, 90 to 30, 95 to 48), snapshot.percentiles)
    }

    @Test
    fun `survives empty or unrelated output`() {
        val snapshot = parsePerformance("no data returned")
        assertTrue(snapshot.isEmpty)
        assertNull(snapshot.javaHeapKb)
        assertEquals(emptyMap(), snapshot.percentiles)
    }

    @Test
    fun `formats values and absences`() {
        assertEquals("15.5", formatMegabytes(15908))
        assertEquals("—", formatMegabytes(null))
        assertEquals("—", formatPercent(null))
    }

    @Test
    fun `bands frame times at the 16 and 32ms boundaries`() {
        assertEquals(FrameBand.Good, bandOf(15))
        assertEquals(FrameBand.Warn, bandOf(16))
        assertEquals(FrameBand.Warn, bandOf(32))
        assertEquals(FrameBand.Bad, bandOf(33))
    }
}
