package dev.compy.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.compy.data.Settings
import dev.compy.rex.TerminalColors

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF006A75),
    secondary = Color(0xFF4A6268),
    tertiary = Color(0xFF525E7D),
)
private val FallbackDark = darkColorScheme(
    primary = Color(0xFF6FD5E3),
    secondary = Color(0xFFB1CBD1),
    tertiary = Color(0xFFBAC6EA),
)

@Composable
fun isDark(settings: Settings): Boolean = when (settings.themeMode) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun CompyTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val context = LocalContext.current
    val scheme = remember(dark, settings.dynamicColor, settings.amoled) {
        colorSchemeFor(context, dark, settings.dynamicColor, settings.amoled)
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
    ) {
        // Screens draw on their own backgrounds, so set the default text color for all of them.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

private fun colorSchemeFor(context: Context, dark: Boolean, dynamic: Boolean, amoled: Boolean): ColorScheme {
    val base = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    if (!(dark && amoled)) return base
    return base.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0B0B0B),
        surfaceContainer = Color(0xFF111111),
        surfaceContainerHigh = Color(0xFF181818),
        surfaceContainerHighest = Color(0xFF202020),
    )
}

/** The terminal themes Compy offers. "rex" uses whatever colors the server says. */
object TerminalThemes {
    data class Option(val id: String, val name: String)

    val options = listOf(
        Option("rex", "Follow Rex"),
        Option("material", "Material You"),
        Option("amoled", "AMOLED black"),
        Option("paper", "Paper"),
        Option("nord", "Nord"),
        Option("gruvbox", "Gruvbox"),
        Option("dracula", "Dracula"),
        Option("solarized-dark", "Solarized dark"),
        Option("solarized-light", "Solarized light"),
    )

    private fun c(rgb: Int) = rgb or (0xFF shl 24)

    private fun palette(vararg ansi: Int): IntArray =
        TerminalColors.BUILTIN_PALETTE.copyOf().also { p -> ansi.forEachIndexed { i, v -> p[i] = c(v) } }

    private fun fixed(id: String): TerminalColors? = when (id) {
        "amoled" -> TerminalColors(c(0xE6E6E6), c(0x000000), c(0xFFFFFF), null)
        "paper" -> TerminalColors(
            c(0x24292F), c(0xFAFAF8), c(0x24292F),
            palette(0x24292F, 0xCF222E, 0x116329, 0x7D4E00, 0x0969DA, 0x8250DF, 0x1B7C83, 0x6E7781,
                0x57606A, 0xA40E26, 0x1A7F37, 0x953800, 0x218BFF, 0xA475F9, 0x3192AA, 0x8C959F),
        )
        "nord" -> TerminalColors(
            c(0xD8DEE9), c(0x2E3440), c(0xD8DEE9),
            palette(0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
                0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4),
        )
        "gruvbox" -> TerminalColors(
            c(0xEBDBB2), c(0x282828), c(0xEBDBB2),
            palette(0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984,
                0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F, 0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2),
        )
        "dracula" -> TerminalColors(
            c(0xF8F8F2), c(0x282A36), c(0xF8F8F2),
            palette(0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
                0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF),
        )
        "solarized-dark" -> TerminalColors(
            c(0x839496), c(0x002B36), c(0x93A1A1),
            palette(0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
                0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3),
        )
        "solarized-light" -> TerminalColors(
            c(0x657B83), c(0xFDF6E3), c(0x586E75),
            palette(0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
                0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3),
        )
        else -> null
    }

    /** Null means follow what the server says. */
    fun colors(id: String, scheme: ColorScheme): TerminalColors? = when (id) {
        "rex" -> null
        "material" -> TerminalColors(
            scheme.onSurface.toArgb(), scheme.surfaceContainerLow.toArgb(), scheme.primary.toArgb(), null,
        )
        else -> fixed(id)
    }
}
