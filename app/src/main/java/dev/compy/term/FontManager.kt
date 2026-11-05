package dev.compy.term

import android.content.Context
import android.graphics.Typeface
import java.io.File

/** The terminal's typefaces: the bundled Nerd Font, or a font file the person imported. */
class TerminalFonts(val regular: Typeface, val bold: Typeface, val italic: Typeface, val boldItalic: Typeface) {
    fun pick(bold: Boolean, italic: Boolean): Typeface = when {
        bold && italic -> boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }

    companion object {
        @Volatile private var bundled: TerminalFonts? = null

        fun bundled(context: Context): TerminalFonts = bundled ?: synchronized(this) {
            bundled ?: run {
                fun load(name: String) = Typeface.createFromAsset(context.assets, "fonts/JetBrainsMonoNerdFontMono-$name.ttf")
                TerminalFonts(load("Regular"), load("Bold"), load("Italic"), load("BoldItalic")).also { bundled = it }
            }
        }

        /** Loads an imported font, deriving bold and italic from it. Null if the file won't load. */
        fun fromFile(file: File): TerminalFonts? = runCatching {
            val base = Typeface.createFromFile(file)
            TerminalFonts(
                base,
                Typeface.create(base, Typeface.BOLD),
                Typeface.create(base, Typeface.ITALIC),
                Typeface.create(base, Typeface.BOLD_ITALIC),
            )
        }.getOrNull()
    }
}
