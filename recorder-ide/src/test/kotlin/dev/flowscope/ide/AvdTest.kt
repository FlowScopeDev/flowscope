package dev.flowscope.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvdTest {
    private val names = listOf("Pixel_7", "Tablet")

    @Test
    fun `an AVD with no emulator is stopped`() {
        val merged = mergeAvds(names, emptyMap(), emptySet(), emptySet())
        assertEquals(listOf(AvdStatus.Stopped, AvdStatus.Stopped), merged.map { it.status })
    }

    @Test
    fun `a running emulator carries its serial`() {
        val merged = mergeAvds(names, mapOf("emulator-5554" to "Pixel_7"), emptySet(), setOf("Pixel_7"))
        assertEquals(AvdEntry("Pixel_7", AvdStatus.Running("emulator-5554"), owned = true), merged[0])
        assertEquals(AvdEntry("Tablet", AvdStatus.Stopped, owned = false), merged[1])
    }

    @Test
    fun `booting wins while ADB already lists the emulator`() {
        val merged = mergeAvds(names, mapOf("emulator-5554" to "Pixel_7"), setOf("Pixel_7"), emptySet())
        assertEquals(AvdStatus.Booting, merged[0].status)
    }

    /**
     * With the display hidden there is nothing to show an emulator window in, so headless leads.
     * Both actions stay in the row either way — this only sets the order.
     */
    @Test
    fun `headless leads only when the device display is hidden`() {
        assertTrue(primaryAvdStartIsHeadless(showDisplay = false))
        assertFalse(primaryAvdStartIsHeadless(showDisplay = true))
    }
}
