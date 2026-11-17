package dev.compy.term

import kotlin.math.abs
import kotlin.math.min

/**
 * The outline of a selection as one shape with every corner rounded, in place of a block per
 * line. Worked out here from which cells are selected, apart from the drawing, so it can be
 * checked without a screen.
 */
object SelectionShape {
    /** One row's run of selected cells, as a rectangle with a radius for each corner. 0 leaves a corner square. */
    data class Piece(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val topLeft: Float,
        val topRight: Float,
        val bottomRight: Float,
        val bottomLeft: Float,
    )

    /**
     * An inside corner of the outline, where one line of the selection reaches further than the
     * next. The corner is at ([x], [y]) and the empty quarter beside it lies [dx], [dy] from it,
     * each 1 or -1. That quarter is filled up to a quarter circle of [radius], which rounds it.
     */
    data class Fillet(val x: Float, val y: Float, val dx: Int, val dy: Int, val radius: Float)

    class Shape(val pieces: List<Piece>, val fillets: List<Fillet>) {
        val isEmpty get() = pieces.isEmpty()
    }

    /**
     * The shape of the selection in rows [firstRow] until [endRow], where a row's top is its
     * number times [cellH]. [selected] says whether a cell is selected, and is only asked about
     * cells inside those rows and [cols].
     */
    fun build(
        cols: Int,
        firstRow: Int,
        endRow: Int,
        cellW: Float,
        cellH: Float,
        radius: Float,
        selected: (col: Int, row: Int) -> Boolean,
    ): Shape {
        fun has(col: Int, row: Int) = row in firstRow until endRow && col in 0 until cols && selected(col, row)
        val pieces = ArrayList<Piece>()
        val fillets = ArrayList<Fillet>()
        // The one run on each row, for the corners between rows; null for a row with none or several.
        val spans = arrayOfNulls<IntArray>(endRow - firstRow)
        for (row in firstRow until endRow) {
            var runs = 0
            var x = 0
            while (x < cols) {
                if (!has(x, row)) {
                    x++
                    continue
                }
                val start = x
                while (x < cols && has(x, row)) x++
                runs++
                spans[row - firstRow] = if (runs == 1) intArrayOf(start, x) else null
                val r = min(radius, min((x - start) * cellW, cellH) / 2f)
                // A corner is round where nothing selected lies above or below it.
                pieces += Piece(
                    start * cellW, row * cellH, x * cellW, (row + 1) * cellH,
                    topLeft = if (has(start, row - 1)) 0f else r,
                    topRight = if (has(x - 1, row - 1)) 0f else r,
                    bottomRight = if (has(x - 1, row + 1)) 0f else r,
                    bottomLeft = if (has(start, row + 1)) 0f else r,
                )
            }
        }
        for (row in firstRow until endRow - 1) {
            val upper = spans[row - firstRow] ?: continue
            val lower = spans[row + 1 - firstRow] ?: continue
            // Lines whose selections don't touch have no corner between them.
            if (upper[1] <= lower[0] || lower[1] <= upper[0]) continue
            val y = (row + 1) * cellH
            if (upper[0] != lower[0]) {
                // The line that starts later leaves a corner before its start, above or below.
                val r = min(radius, min(abs(upper[0] - lower[0]) * cellW, cellH) / 2f)
                fillets += Fillet(maxOf(upper[0], lower[0]) * cellW, y, -1, if (upper[0] > lower[0]) -1 else 1, r)
            }
            if (upper[1] != lower[1]) {
                // The line that ends sooner leaves a corner past its end, above or below.
                val r = min(radius, min(abs(upper[1] - lower[1]) * cellW, cellH) / 2f)
                fillets += Fillet(minOf(upper[1], lower[1]) * cellW, y, 1, if (lower[1] > upper[1]) -1 else 1, r)
            }
        }
        return Shape(pieces, fillets)
    }
}
