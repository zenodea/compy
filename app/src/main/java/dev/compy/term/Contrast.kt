package dev.compy.term

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Keeps terminal text readable against its background, whatever colors a program picked. */
object Contrast {
    /** The least contrast text is left with, the ratio WCAG asks of large text. */
    private const val ENOUGH = 3.0

    /** A color's relative luminance, from 0 for black to 1 for white. */
    fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val x = (v and 0xFF) / 255.0
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color shr 16) + 0.7152 * channel(color shr 8) + 0.0722 * channel(color)
    }

    fun ratio(a: Double, b: Double): Double = (max(a, b) + 0.05) / (min(a, b) + 0.05)

    /**
     * [fg] unchanged when it reads well on [bg]; otherwise moved toward white on a dark background
     * or black on a light one, only as far as it takes.
     */
    fun readable(fg: Int, bg: Int): Int {
        val back = luminance(bg)
        if (ratio(luminance(fg), back) >= ENOUGH) return fg
        val target = if (back > 0.4) 0x000000 else 0xFFFFFF
        var t = 0.2
        while (t < 1.0) {
            val mixed = mix(fg, target, t)
            if (ratio(luminance(mixed), back) >= ENOUGH) return mixed
            t += 0.2
        }
        return (0xFF shl 24) or target
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            out = out or ((x + (y - x) * t).roundToInt() shl shift)
        }
        return out
    }
}
