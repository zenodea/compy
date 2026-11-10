package dev.compy.ui

import android.app.Activity
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.compy.data.Settings
import dev.compy.rex.TerminalColors

private val FallbackLight = lightColorScheme(primary = Color(0xFF006A75))
private val FallbackDark = darkColorScheme(primary = Color(0xFF6FD5E3))

private val CompyTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 15.sp, letterSpacing = 0.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, letterSpacing = 0.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, letterSpacing = 0.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.sp),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
    )
}

/** Compy's theme: colors from the Rex host when known, otherwise Material You. */
@Composable
fun CompyTheme(settings: Settings, hostColors: TerminalColors?, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val context = LocalContext.current
    val target = remember(dark, settings.dynamicColor, settings.amoled, hostColors) {
        if (hostColors != null) {
            schemeFromTerminal(hostColors, settings.amoled)
        } else {
            wallpaperScheme(context, dark, settings.dynamicColor, settings.amoled)
        }
    }
    // Fade to new colors instead of snapping, such as when a host's theme arrives.
    var scheme by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        val from = scheme
        if (from == target) return@LaunchedEffect
        val progress = Animatable(0f)
        progress.animateTo(1f, tween(MOTION_LONG)) { scheme = lerpScheme(from, target, value) }
        scheme = target
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        val lightBars = target.background.luminance() > 0.5f
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = CompyTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(14.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

private fun lerpScheme(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme = b.copy(
    primary = lerp(a.primary, b.primary, t),
    onPrimary = lerp(a.onPrimary, b.onPrimary, t),
    primaryContainer = lerp(a.primaryContainer, b.primaryContainer, t),
    onPrimaryContainer = lerp(a.onPrimaryContainer, b.onPrimaryContainer, t),
    secondary = lerp(a.secondary, b.secondary, t),
    onSecondary = lerp(a.onSecondary, b.onSecondary, t),
    secondaryContainer = lerp(a.secondaryContainer, b.secondaryContainer, t),
    onSecondaryContainer = lerp(a.onSecondaryContainer, b.onSecondaryContainer, t),
    tertiary = lerp(a.tertiary, b.tertiary, t),
    onTertiary = lerp(a.onTertiary, b.onTertiary, t),
    tertiaryContainer = lerp(a.tertiaryContainer, b.tertiaryContainer, t),
    onTertiaryContainer = lerp(a.onTertiaryContainer, b.onTertiaryContainer, t),
    background = lerp(a.background, b.background, t),
    onBackground = lerp(a.onBackground, b.onBackground, t),
    surface = lerp(a.surface, b.surface, t),
    onSurface = lerp(a.onSurface, b.onSurface, t),
    surfaceVariant = lerp(a.surfaceVariant, b.surfaceVariant, t),
    onSurfaceVariant = lerp(a.onSurfaceVariant, b.onSurfaceVariant, t),
    error = lerp(a.error, b.error, t),
    onError = lerp(a.onError, b.onError, t),
    errorContainer = lerp(a.errorContainer, b.errorContainer, t),
    onErrorContainer = lerp(a.onErrorContainer, b.onErrorContainer, t),
    outline = lerp(a.outline, b.outline, t),
    outlineVariant = lerp(a.outlineVariant, b.outlineVariant, t),
    surfaceContainerLowest = lerp(a.surfaceContainerLowest, b.surfaceContainerLowest, t),
    surfaceContainerLow = lerp(a.surfaceContainerLow, b.surfaceContainerLow, t),
    surfaceContainer = lerp(a.surfaceContainer, b.surfaceContainer, t),
    surfaceContainerHigh = lerp(a.surfaceContainerHigh, b.surfaceContainerHigh, t),
    surfaceContainerHighest = lerp(a.surfaceContainerHighest, b.surfaceContainerHighest, t),
    surfaceBright = lerp(a.surfaceBright, b.surfaceBright, t),
    surfaceDim = lerp(a.surfaceDim, b.surfaceDim, t),
    surfaceTint = lerp(a.surfaceTint, b.surfaceTint, t),
    inverseSurface = lerp(a.inverseSurface, b.inverseSurface, t),
    inverseOnSurface = lerp(a.inverseOnSurface, b.inverseOnSurface, t),
    inversePrimary = lerp(a.inversePrimary, b.inversePrimary, t),
    scrim = lerp(a.scrim, b.scrim, t),
)

private fun wallpaperScheme(context: Context, dark: Boolean, dynamic: Boolean, amoled: Boolean): ColorScheme {
    val base = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    val canvas = if (dark && amoled) Color.Black else base.surfaceContainerLowest
    return base.copy(background = canvas, surface = canvas)
}

private fun saturation(c: Color): Float {
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    return if (max == 0f) 0f else (max - min) / max
}

private fun contrast(a: Color, b: Color): Float {
    val l1 = a.luminance() + 0.05f
    val l2 = b.luminance() + 0.05f
    return if (l1 > l2) l1 / l2 else l2 / l1
}

private fun onColor(c: Color) = if (c.luminance() > 0.45f) Color(0xFF101010) else Color.White

/**
 * Builds the app's colors from a terminal's. The canvas sits just outside the terminal's own
 * background, so panes read as floating cards, and accents come from the terminal's palette.
 */
internal fun schemeFromTerminal(c: TerminalColors, amoled: Boolean = false): ColorScheme {
    val bg = if (c.background != 0) Color(c.background) else Color.Black
    val fg = if (c.foreground != 0) Color(c.foreground) else if (bg.luminance() > 0.5f) Color(0xFF1C1C1C) else Color(0xFFE6E6E6)
    val dark = when (c.scheme) {
        "light" -> false
        "dark" -> true
        else -> bg.luminance() < 0.4f
    }
    val palette = c.palette ?: TerminalColors.BUILTIN_PALETTE
    val canvas = when {
        dark && amoled -> Color.Black
        dark -> lerp(bg, Color.Black, 0.4f)
        else -> lerp(bg, Color.Black, 0.05f)
    }
    val candidates = buildList {
        if (c.cursor != 0) add(Color(c.cursor))
        addAll(listOf(12, 4, 13, 5, 14, 6, 10, 2).map { Color(palette[it]) })
    }
    val accent = candidates.firstOrNull { saturation(it) > 0.25f && contrast(it, bg) >= 3f } ?: fg
    val magenta = Color(palette[5])
    val red = Color(palette[if (dark) 9 else 1])
    fun tone(amount: Float) = lerp(canvas, fg, amount)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onColor(accent),
        primaryContainer = lerp(canvas, accent, 0.28f),
        onPrimaryContainer = lerp(fg, accent, 0.2f),
        inversePrimary = accent,
        secondary = lerp(fg, bg, 0.25f),
        onSecondary = bg,
        secondaryContainer = tone(0.14f),
        onSecondaryContainer = fg,
        tertiary = magenta,
        onTertiary = onColor(magenta),
        tertiaryContainer = lerp(canvas, magenta, 0.25f),
        onTertiaryContainer = fg,
        background = canvas,
        onBackground = fg,
        surface = canvas,
        onSurface = fg,
        surfaceVariant = tone(0.1f),
        onSurfaceVariant = lerp(fg, bg, 0.35f),
        surfaceTint = accent,
        inverseSurface = fg,
        inverseOnSurface = bg,
        error = red,
        onError = onColor(red),
        errorContainer = lerp(canvas, red, 0.25f),
        onErrorContainer = fg,
        outline = lerp(fg, bg, 0.6f),
        outlineVariant = lerp(fg, bg, 0.8f),
        surfaceBright = tone(0.18f),
        surfaceDim = canvas,
        surfaceContainerLowest = canvas,
        surfaceContainerLow = tone(0.035f),
        surfaceContainer = tone(0.065f),
        surfaceContainerHigh = tone(0.1f),
        surfaceContainerHighest = tone(0.15f),
    )
}

/** The terminal themes Compy offers. "rex" uses whatever colors the host says. */
object TerminalThemes {
    data class Option(val id: String, val name: String)

    val options = listOf(
        Option("rex", "Match the Rex host"),
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

    fun fixedColors(id: String): TerminalColors? = when (id) {
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

    /** Null means follow what the host says. */
    fun colors(id: String, scheme: ColorScheme): TerminalColors? = when (id) {
        "rex" -> null
        "material" -> TerminalColors(
            scheme.onSurface.toArgb(), scheme.surfaceContainerLow.toArgb(), scheme.primary.toArgb(), null,
        )
        else -> fixedColors(id)
    }
}
