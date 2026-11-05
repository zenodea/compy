package dev.compy.term

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import java.io.File

/**
 * The terminal's typefaces: the bundled Nerd Font, or a font file the person imported. Both fall
 * back to bundled symbol fonts, because programs like Claude Code print symbols such as U+23F5 that
 * many fonts and phones don't have.
 */
class TerminalFonts(val regular: Typeface, val bold: Typeface, val italic: Typeface, val boldItalic: Typeface) {
    fun pick(bold: Boolean, italic: Boolean): Typeface = when {
        bold && italic -> boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }

    companion object {
        private val FALLBACKS = listOf("NotoSansSymbols2-Regular", "NotoSansSymbols", "NotoSansMath-Regular")

        @Volatile private var bundled: TerminalFonts? = null

        private fun withFallbacks(context: Context, font: Font, weight: Int, slant: Int): Typeface {
            val builder = Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                .setStyle(FontStyle(weight, slant))
            for (name in FALLBACKS) {
                val fallback = Font.Builder(context.assets, "fonts/$name.ttf").build()
                builder.addCustomFallback(FontFamily.Builder(fallback).build())
            }
            return builder.setSystemFallback("monospace").build()
        }

        fun bundled(context: Context): TerminalFonts = bundled ?: synchronized(this) {
            bundled ?: run {
                fun load(name: String, weight: Int, slant: Int): Typeface {
                    val font = Font.Builder(context.assets, "fonts/JetBrainsMonoNerdFontMono-$name.ttf")
                        .setWeight(weight).setSlant(slant).build()
                    return withFallbacks(context, font, weight, slant)
                }
                val upright = FontStyle.FONT_SLANT_UPRIGHT
                val slanted = FontStyle.FONT_SLANT_ITALIC
                TerminalFonts(
                    load("Regular", FontStyle.FONT_WEIGHT_NORMAL, upright),
                    load("Bold", FontStyle.FONT_WEIGHT_BOLD, upright),
                    load("Italic", FontStyle.FONT_WEIGHT_NORMAL, slanted),
                    load("BoldItalic", FontStyle.FONT_WEIGHT_BOLD, slanted),
                ).also { bundled = it }
            }
        }

        /** Loads an imported font, deriving bold and italic from it. Null if the file won't load. */
        fun fromFile(context: Context, file: File): TerminalFonts? = runCatching {
            val base = withFallbacks(context, Font.Builder(file).build(), FontStyle.FONT_WEIGHT_NORMAL, FontStyle.FONT_SLANT_UPRIGHT)
            TerminalFonts(
                base,
                Typeface.create(base, Typeface.BOLD),
                Typeface.create(base, Typeface.ITALIC),
                Typeface.create(base, Typeface.BOLD_ITALIC),
            )
        }.getOrNull()
    }
}
