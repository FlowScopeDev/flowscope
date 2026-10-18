package dev.flowscope.ide

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.nio.file.Path

internal enum class ThemeMode(val persistedValue: String, val label: String, val detail: String) {
    System("system", "System", "Follow the operating system appearance"),
    Light("light", "Light", "Use the light Flow Scope palette"),
    Dark("dark", "Dark", "Use the dark Flow Scope palette"),
}

internal fun parseThemeMode(value: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.persistedValue == value?.trim()?.lowercase() }
        ?: ThemeMode.System

internal fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

internal data class FlowColors(
    val ink000: Color, val ink050: Color, val ink100: Color, val ink150: Color,
    val ink200: Color, val ink250: Color, val ink300: Color, val ink400: Color,
    val textHi: Color, val textMid: Color, val textLow: Color,
    val signalAmber: Color, val signalAmberDim: Color,
    val signalRed: Color, val signalRedDim: Color,
    val signalGreen: Color, val signalIce: Color, val signalIceDim: Color,
    val dark: Boolean,
)

internal val DarkFlowColors = FlowColors(
    Color(0xFF000000), Color(0xFF06070A), Color(0xFF0B0D11), Color(0xFF11141A),
    Color(0xFF171B22), Color(0xFF1E232C), Color(0xFF262C36), Color(0xFF39414E),
    Color(0xFFEDF0F4), Color(0xFF99A1AD), Color(0xFF5E6673),
    Color(0xFFFFB224), Color(0xFF2A1F08), Color(0xFFFF4F56), Color(0xFF2A1012),
    Color(0xFF3ECF8E), Color(0xFF9DBCFF), Color(0xFF121A2B), true,
)

internal val LightFlowColors = FlowColors(
    Color(0xFFFFFFFF), Color(0xFFF8F9FB), Color(0xFFF4F6F8), Color(0xFFEEF1F5),
    Color(0xFFE6EAF0), Color(0xFFDAE0E8), Color(0xFFC6CED8), Color(0xFF98A3B2),
    Color(0xFF171B22), Color(0xFF46505E), Color(0xFF687382),
    Color(0xFF9A5B00), Color(0xFFFFF0D1), Color(0xFFC9323C), Color(0xFFFDE8EA),
    Color(0xFF147A50), Color(0xFF315FB5), Color(0xFFE6ECF8), false,
)

internal val LocalFlowColors = staticCompositionLocalOf { DarkFlowColors }

@Composable
internal fun FlowScopeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val colors = if (mode.isDark(isSystemInDarkTheme())) DarkFlowColors else LightFlowColors
    val scheme = if (colors.dark) {
        darkColorScheme(
            primary = colors.textHi, onPrimary = colors.ink050,
            background = colors.ink050, onBackground = colors.textHi,
            surface = colors.ink100, onSurface = colors.textHi,
            surfaceVariant = colors.ink150, onSurfaceVariant = colors.textMid,
            outline = colors.ink300, error = colors.signalRed,
        )
    } else {
        lightColorScheme(
            primary = colors.signalIce, onPrimary = Color.White,
            background = colors.ink050, onBackground = colors.textHi,
            surface = colors.ink100, onSurface = colors.textHi,
            surfaceVariant = colors.ink150, onSurfaceVariant = colors.textMid,
            outline = colors.ink300, error = colors.signalRed,
        )
    }
    CompositionLocalProvider(LocalFlowColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// Terse aliases resolve through the active composition; they are not fixed process-global colors.
internal val Ink000: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.ink000
internal val Ink050: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.ink050
internal val Ink100: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.ink100
internal val Ink150: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.ink150
internal val Ink200: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.ink200
internal val Ink250: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.ink250
internal val Ink300: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.ink300
internal val Ink400: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.ink400
internal val TextHi: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.textHi
internal val TextMid: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.textMid
internal val TextLow: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.textLow
internal val SignalAmber: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.signalAmber
internal val SignalAmberDim: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.signalAmberDim
internal val SignalRed: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.signalRed
internal val SignalRedDim: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.signalRedDim
internal val SignalGreen: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.signalGreen
internal val SignalIce: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.signalIce
internal val SignalIceDim: Color @Composable @ReadOnlyComposable get() = LocalFlowColors.current.signalIceDim
internal val AppBackground: Color @Composable @ReadOnlyComposable get() = Ink050
internal val Panel: Color @Composable @ReadOnlyComposable get() = Ink100
internal val PanelRaised: Color @Composable @ReadOnlyComposable get() = Ink150
internal val Border: Color @Composable @ReadOnlyComposable get() = Ink300
internal val TextPrimary: Color @Composable @ReadOnlyComposable get() = TextHi
internal val TextSecondary: Color @Composable @ReadOnlyComposable get() = TextMid
internal val Accent: Color @Composable @ReadOnlyComposable get() = TextHi
internal val AccentSoft: Color @Composable @ReadOnlyComposable get() = Ink250
internal val Success: Color @Composable @ReadOnlyComposable get() = SignalGreen
internal val Danger: Color @Composable @ReadOnlyComposable get() = SignalRed

// design.md §4.1 — macOS absorbs the title bar; Windows keeps its frame and matches the surface.
internal val IsMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
// Keep a generous native-title-bar safe area around the macOS traffic lights. Their exact position
// is owned by AppKit, so the Compose content gives the controls breathing room instead of crowding
// them with the session bar immediately below.
internal val TitleStripHeight: Dp = if (IsMac) 36.dp else 0.dp

internal val FlowScopeHome: Path = Path.of(System.getProperty("user.home"), ".flowscope")

internal fun contrastRatio(foreground: Color, background: Color): Double {
    fun channel(value: Float): Double =
        if (value <= 0.04045f) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    val first = luminance(foreground)
    val second = luminance(background)
    return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
}
