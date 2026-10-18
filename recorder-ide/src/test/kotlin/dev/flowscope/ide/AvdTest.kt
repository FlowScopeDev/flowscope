package dev.flowscope.ide

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
