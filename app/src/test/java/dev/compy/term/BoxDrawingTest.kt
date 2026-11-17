package dev.compy.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxDrawingTest {
    @Test fun readsWhichSixthsASextantFills() {
        // Bit 0 is the upper left, 1 the upper right, and so on down the cell.
        assertEquals(0b000001, BoxDrawing.sextantBits(0x1FB00))
        assertEquals(0b000011, BoxDrawing.sextantBits(0x1FB02))
        assertEquals(0b010100, BoxDrawing.sextantBits(0x1FB13))
        // The left half block has a character of its own, so the numbering steps over it.
        assertEquals(0b010110, BoxDrawing.sextantBits(0x1FB14))
        assertEquals(0b101001, BoxDrawing.sextantBits(0x1FB27))
        // And over the right half block.
        assertEquals(0b101011, BoxDrawing.sextantBits(0x1FB28))
        assertEquals(0b111110, BoxDrawing.sextantBits(0x1FB3B))
    }

    @Test fun everySextantIsDifferentAndNoneIsAHalfBlock() {
        val all = (0x1FB00..0x1FB3B).map { BoxDrawing.sextantBits(it) }
        assertEquals(60, all.toSet().size)
        assertFalse(0b010101 in all)
        assertFalse(0b101010 in all)
        assertTrue(all.all { it in 1..62 })
    }
}
