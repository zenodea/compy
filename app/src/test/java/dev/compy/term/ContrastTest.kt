package dev.compy.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun ratio(a: Int, b: Int) = Contrast.ratio(Contrast.luminance(a), Contrast.luminance(b))

    @Test
    fun leavesReadableTextAlone() {
        assertEquals(white, Contrast.readable(white, black))
        assertEquals(0xFF5FD75F.toInt(), Contrast.readable(0xFF5FD75F.toInt(), black))
    }

    @Test
    fun lightensDarkTextOnADarkBackground() {
        val blue = 0xFF00005F.toInt()
        val fixed = Contrast.readable(blue, black)
        assertTrue(ratio(blue, black) < 3.0)
        assertTrue(ratio(fixed, black) >= 3.0)
    }

    @Test
    fun darkensLightTextOnALightBackground() {
        val yellow = 0xFFFFFF87.toInt()
        val fixed = Contrast.readable(yellow, white)
        assertTrue(ratio(fixed, white) >= 3.0)
        assertTrue(Contrast.luminance(fixed) < Contrast.luminance(yellow))
    }

    @Test
    fun movesOnlyAsFarAsNeeded() {
        // Dim grey borders on black are a little short of readable, and stay grey rather than turn white.
        val grey = 0xFF585858.toInt()
        val fixed = Contrast.readable(grey, black)
        assertTrue(ratio(fixed, black) >= 3.0)
        assertTrue(Contrast.luminance(fixed) < 0.5)
    }

    @Test
    fun keepsTheResultOpaque() {
        assertEquals(0xFF, Contrast.readable(black, black) ushr 24)
    }
}
