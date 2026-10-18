package dev.flowscope.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemePreferenceTest {

    @Test
    fun `parses all persisted theme modes case insensitively`() {
        assertEquals(ThemeMode.System, parseThemeMode(" system\n"))
        assertEquals(ThemeMode.Light, parseThemeMode("LIGHT"))
        assertEquals(ThemeMode.Dark, parseThemeMode("dark"))
    }

    @Test
    fun `missing and invalid preferences fall back to system`() {
        assertEquals(ThemeMode.System, parseThemeMode(null))
        assertEquals(ThemeMode.System, parseThemeMode(""))
        assertEquals(ThemeMode.System, parseThemeMode("sepia"))
    }

    @Test
    fun `serializes stable lowercase values with a trailing newline`() {
        assertEquals("system\n", formatThemeMode(ThemeMode.System))
        assertEquals("light\n", formatThemeMode(ThemeMode.Light))
        assertEquals("dark\n", formatThemeMode(ThemeMode.Dark))
    }

    @Test
    fun `system follows the operating system and overrides are explicit`() {
        assertTrue(ThemeMode.System.isDark(systemDark = true))
        assertFalse(ThemeMode.System.isDark(systemDark = false))
        assertFalse(ThemeMode.Light.isDark(systemDark = true))
        assertTrue(ThemeMode.Dark.isDark(systemDark = false))
    }
}

class LightPaletteContrastTest {

    @Test
    fun `informational text and signals meet AA on light work and chrome surfaces`() {
        val foregrounds = listOf(
            LightFlowColors.textHi,
            LightFlowColors.textMid,
            LightFlowColors.signalAmber,
            LightFlowColors.signalRed,
            LightFlowColors.signalGreen,
            LightFlowColors.signalIce,
        )
        val backgrounds = listOf(LightFlowColors.ink100, LightFlowColors.ink150)
        foregrounds.forEach { foreground ->
            backgrounds.forEach { background ->
                assertTrue(
                    contrastRatio(foreground, background) >= 4.5,
                    "Expected AA contrast for $foreground on $background",
                )
            }
        }
    }
}
