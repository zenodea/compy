package dev.compy.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionShapeTest {
    /** Rows of text where `#` is a selected cell. */
    private fun shape(vararg rows: String, radius: Float = 4f) =
        SelectionShape.build(rows[0].length, 0, rows.size, 10f, 20f, radius) { col, row -> rows[row][col] == '#' }

    @Test fun nothingSelectedIsNoShape() {
        assertTrue(shape("....", "....").isEmpty)
    }

    @Test fun oneLineIsRoundOnEveryCorner() {
        val s = shape("..###.")
        assertEquals(listOf(SelectionShape.Piece(20f, 0f, 50f, 20f, 4f, 4f, 4f, 4f)), s.pieces)
        assertTrue(s.fillets.isEmpty())
    }

    @Test fun aBlockIsRoundOnlyOnItsOutside() {
        val s = shape("####", "####", "####")
        assertEquals(listOf(4f, 4f, 0f, 0f), s.pieces[0].run { listOf(topLeft, topRight, bottomRight, bottomLeft) })
        assertEquals(listOf(0f, 0f, 0f, 0f), s.pieces[1].run { listOf(topLeft, topRight, bottomRight, bottomLeft) })
        assertEquals(listOf(0f, 0f, 4f, 4f), s.pieces[2].run { listOf(topLeft, topRight, bottomRight, bottomLeft) })
        assertTrue(s.fillets.isEmpty())
    }

    @Test fun textSelectedAcrossLinesRoundsItsInsideCornersToo() {
        // From the middle of the first line to the middle of the last, as a drag selects.
        val s = shape("...###", "######", "##....")
        // The first line's start is a step in from the second's: its lower left is square, and
        // the empty corner above and before it is filled in.
        assertEquals(0f, s.pieces[0].bottomLeft)
        assertEquals(4f, s.pieces[0].topLeft)
        assertEquals(4f, s.pieces[1].topLeft)
        assertEquals(4f, s.pieces[1].bottomRight)
        assertEquals(
            listOf(SelectionShape.Fillet(30f, 20f, -1, -1, 4f), SelectionShape.Fillet(20f, 40f, 1, 1, 4f)),
            s.fillets,
        )
    }

    @Test fun aNarrowRunHasASmallerRadius() {
        assertEquals(5f, shape(".#.", radius = 8f).pieces[0].topLeft)
        // A step one cell wide can't take a corner wider than half of it.
        assertEquals(5f, shape(".###", "####", radius = 8f).fillets[0].radius)
    }

    @Test fun linesThatDoNotTouchHaveNoCornerBetweenThem() {
        assertTrue(shape("##....", "....##").fillets.isEmpty())
    }

    @Test fun twoRunsOnALineAreEachRounded() {
        val s = shape("##..##")
        assertEquals(2, s.pieces.size)
        assertEquals(4f, s.pieces[1].topLeft)
    }

    @Test fun rowsAboveTheScreenAreCountedBelowZero() {
        val s = SelectionShape.build(3, -1, 1, 10f, 20f, 4f) { _, _ -> true }
        assertEquals(-20f, s.pieces[0].top)
        assertEquals(0f, s.pieces[0].bottomLeft)
        assertEquals(4f, s.pieces[1].bottomLeft)
    }
}
