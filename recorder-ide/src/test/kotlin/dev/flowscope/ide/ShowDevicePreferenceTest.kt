package dev.flowscope.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShowDevicePreferenceTest {
    @Test
    fun `only an explicit false hides the display`() {
        assertFalse(parseShowDevice(" FALSE\n"))
        assertTrue(parseShowDevice("true"))
        assertTrue(parseShowDevice(null))
        assertTrue(parseShowDevice("garbage"))
    }

    @Test
    fun `format round trips`() {
        assertEquals(false, parseShowDevice(formatShowDevice(false)))
        assertEquals(true, parseShowDevice(formatShowDevice(true)))
    }
}
