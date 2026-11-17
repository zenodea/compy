package dev.compy.term

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Draws box-drawing and block characters to fill the cell exactly. Font glyphs rarely match the
 * cell height, which would leave gaps in the borders that terminal programs draw.
 */
internal object BoxDrawing {
    private const val NONE = 0
    private const val LIGHT = 1
    private const val HEAVY = 2
    private const val DOUBLE = 3

    private class Segs(val l: Int, val r: Int, val u: Int, val d: Int)

    private val table: Map<Int, Segs> = buildMap {
        fun put(cp: Int, l: Int, r: Int, u: Int, d: Int) = put(cp, Segs(l, r, u, d))
        val L = LIGHT; val H = HEAVY; val D = DOUBLE
        put(0x2500, L, L, 0, 0); put(0x2501, H, H, 0, 0); put(0x2502, 0, 0, L, L); put(0x2503, 0, 0, H, H)
        // Dashed lines, which are drawn as their own run of dashes.
        for (cp in intArrayOf(0x2504, 0x2508, 0x254C)) put(cp, L, L, 0, 0)
        for (cp in intArrayOf(0x2505, 0x2509, 0x254D)) put(cp, H, H, 0, 0)
        for (cp in intArrayOf(0x2506, 0x250A, 0x254E)) put(cp, 0, 0, L, L)
        for (cp in intArrayOf(0x2507, 0x250B, 0x254F)) put(cp, 0, 0, H, H)
        put(0x250C, 0, L, 0, L); put(0x250D, 0, H, 0, L); put(0x250E, 0, L, 0, H); put(0x250F, 0, H, 0, H)
        put(0x2510, L, 0, 0, L); put(0x2511, H, 0, 0, L); put(0x2512, L, 0, 0, H); put(0x2513, H, 0, 0, H)
        put(0x2514, 0, L, L, 0); put(0x2515, 0, H, L, 0); put(0x2516, 0, L, H, 0); put(0x2517, 0, H, H, 0)
        put(0x2518, L, 0, L, 0); put(0x2519, H, 0, L, 0); put(0x251A, L, 0, H, 0); put(0x251B, H, 0, H, 0)
        put(0x251C, 0, L, L, L); put(0x251D, 0, H, L, L); put(0x251E, 0, L, H, L); put(0x251F, 0, L, L, H)
        put(0x2520, 0, L, H, H); put(0x2521, 0, H, H, L); put(0x2522, 0, H, L, H); put(0x2523, 0, H, H, H)
        put(0x2524, L, 0, L, L); put(0x2525, H, 0, L, L); put(0x2526, L, 0, H, L); put(0x2527, L, 0, L, H)
        put(0x2528, L, 0, H, H); put(0x2529, H, 0, H, L); put(0x252A, H, 0, L, H); put(0x252B, H, 0, H, H)
        put(0x252C, L, L, 0, L); put(0x252D, H, L, 0, L); put(0x252E, L, H, 0, L); put(0x252F, H, H, 0, L)
        put(0x2530, L, L, 0, H); put(0x2531, H, L, 0, H); put(0x2532, L, H, 0, H); put(0x2533, H, H, 0, H)
        put(0x2534, L, L, L, 0); put(0x2535, H, L, L, 0); put(0x2536, L, H, L, 0); put(0x2537, H, H, L, 0)
        put(0x2538, L, L, H, 0); put(0x2539, H, L, H, 0); put(0x253A, L, H, H, 0); put(0x253B, H, H, H, 0)
        put(0x253C, L, L, L, L); put(0x253D, H, L, L, L); put(0x253E, L, H, L, L); put(0x253F, H, H, L, L)
        put(0x2540, L, L, H, L); put(0x2541, L, L, L, H); put(0x2542, L, L, H, H); put(0x2543, H, L, H, L)
        put(0x2544, L, H, H, L); put(0x2545, H, L, L, H); put(0x2546, L, H, L, H); put(0x2547, H, H, H, L)
        put(0x2548, H, H, L, H); put(0x2549, H, L, H, H); put(0x254A, L, H, H, H); put(0x254B, H, H, H, H)
        put(0x2550, D, D, 0, 0); put(0x2551, 0, 0, D, D)
        put(0x2552, 0, D, 0, L); put(0x2553, 0, L, 0, D); put(0x2554, 0, D, 0, D)
        put(0x2555, D, 0, 0, L); put(0x2556, L, 0, 0, D); put(0x2557, D, 0, 0, D)
        put(0x2558, 0, D, L, 0); put(0x2559, 0, L, D, 0); put(0x255A, 0, D, D, 0)
        put(0x255B, D, 0, L, 0); put(0x255C, L, 0, D, 0); put(0x255D, D, 0, D, 0)
        put(0x255E, 0, D, L, L); put(0x255F, 0, L, D, D); put(0x2560, 0, D, D, D)
        put(0x2561, D, 0, L, L); put(0x2562, L, 0, D, D); put(0x2563, D, 0, D, D)
        put(0x2564, D, D, 0, L); put(0x2565, L, L, 0, D); put(0x2566, D, D, 0, D)
        put(0x2567, D, D, L, 0); put(0x2568, L, L, D, 0); put(0x2569, D, D, D, 0)
        put(0x256A, D, D, L, L); put(0x256B, L, L, D, D); put(0x256C, D, D, D, D)
        put(0x2574, L, 0, 0, 0); put(0x2575, 0, 0, L, 0); put(0x2576, 0, L, 0, 0); put(0x2577, 0, 0, 0, L)
        put(0x2578, H, 0, 0, 0); put(0x2579, 0, 0, H, 0); put(0x257A, 0, H, 0, 0); put(0x257B, 0, 0, 0, H)
        put(0x257C, L, H, 0, 0); put(0x257D, 0, 0, L, H); put(0x257E, H, L, 0, 0); put(0x257F, 0, 0, H, L)
    }

    fun handles(cp: Int): Boolean = when (cp) {
        in 0x2500..0x257F -> cp in table || cp in 0x256D..0x2573
        in 0x2580..0x259F -> true
        in 0x2800..0x28FF -> true
        in 0xE0B0..0xE0B3 -> true
        in 0x1FB00..0x1FB3B -> true
        else -> false
    }

    /**
     * Which sixths of its cell a sextant fills, for U+1FB00 to U+1FB3B: bit 0 the upper left,
     * 1 the upper right, 2 the middle left, 3 the middle right, 4 the lower left and 5 the lower
     * right. Unicode numbers them by this pattern, and leaves out the two that are already the
     * left and right half blocks.
     */
    internal fun sextantBits(cp: Int): Int {
        var bits = cp - 0x1FB00 + 1
        if (bits >= LEFT_HALF) bits++
        if (bits >= RIGHT_HALF) bits++
        return bits
    }

    private const val LEFT_HALF = 0b010101
    private const val RIGHT_HALF = 0b101010

    // Straight edges are filled without smoothing, so they land on whole pixels and neighbouring
    // cells join with no seam. Curves and slopes are smoothed.
    private val fill = Paint()
    private val smooth = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** Draws the character in the cell at ([x], [y]), which is [w] wide and [h] tall. */
    fun draw(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.color = color
        smooth.color = color
        val thin = maxOf(1f, Math.round(h / 16f).toFloat())
        when (cp) {
            in 0x256D..0x2570 -> rounded(canvas, cp, x, y, w, h, thin)
            in 0x2571..0x2573 -> diagonal(canvas, cp, x, y, w, h, thin)
            in 0x2580..0x259F -> block(canvas, cp, x, y, w, h)
            in 0x2800..0x28FF -> braille(canvas, cp, x, y, w, h)
            in 0xE0B0..0xE0B3 -> powerline(canvas, cp, x, y, w, h, thin)
            in 0x1FB00..0x1FB3B -> sextant(canvas, cp, x, y, w, h)
            else -> {
                val dashes = dashCount(cp)
                val segs = table[cp] ?: return
                if (dashes != 0) dashed(canvas, segs, dashes, x, y, w, h, thin) else lines(canvas, segs, x, y, w, h, thin)
            }
        }
    }

    private fun width(weight: Int, thin: Float) = when (weight) {
        NONE -> 0f
        LIGHT -> thin
        HEAVY -> thin * 2
        else -> thin * 3
    }

    /** Where a stroke of this width starts so that it sits in the middle of [size], on a whole pixel. */
    private fun inset(size: Float, width: Float) = Math.floor(((size - width) / 2f).toDouble()).toFloat()

    /**
     * Collects the strokes into one shape and fills it once. Strokes overlap where they meet, and
     * filling each by itself would draw those places twice, which shows in faint text.
     */
    private fun lines(canvas: Canvas, s: Segs, x: Float, y: Float, w: Float, h: Float, thin: Float) {
        // The widest stroke in each direction decides how far the others reach into the middle.
        val across = maxOf(width(s.u, thin), width(s.d, thin))
        val along = maxOf(width(s.l, thin), width(s.r, thin))
        val midX = x + inset(w, 0f)
        val midY = y + inset(h, 0f)
        val left = if (across > 0f) x + inset(w, across) else midX
        val right = if (across > 0f) left + across else midX
        val top = if (along > 0f) y + inset(h, along) else midY
        val bottom = if (along > 0f) top + along else midY
        path.rewind()
        if (doubles(s)) {
            doubleLines(s, x, y, w, h, thin)
            canvas.drawPath(path, fill)
            return
        }
        fun horizontal(x0: Float, x1: Float, weight: Int) {
            if (weight == NONE) return
            val t = width(weight, thin)
            val at = y + inset(h, t)
            if (weight == DOUBLE) {
                path.addRect(x0, at, x1, at + thin, Path.Direction.CW)
                path.addRect(x0, at + thin * 2, x1, at + thin * 3, Path.Direction.CW)
            } else {
                path.addRect(x0, at, x1, at + t, Path.Direction.CW)
            }
        }
        fun vertical(y0: Float, y1: Float, weight: Int) {
            if (weight == NONE) return
            val t = width(weight, thin)
            val at = x + inset(w, t)
            if (weight == DOUBLE) {
                path.addRect(at, y0, at + thin, y1, Path.Direction.CW)
                path.addRect(at + thin * 2, y0, at + thin * 3, y1, Path.Direction.CW)
            } else {
                path.addRect(at, y0, at + t, y1, Path.Direction.CW)
            }
        }
        horizontal(x, right, s.l)
        horizontal(left, x + w, s.r)
        vertical(y, bottom, s.u)
        vertical(top, y + h, s.d)
        canvas.drawPath(path, fill)
    }

    /** True when every stroke of the character is a double line. */
    private fun doubles(s: Segs): Boolean {
        fun fits(weight: Int) = weight == NONE || weight == DOUBLE
        return fits(s.l) && fits(s.r) && fits(s.u) && fits(s.d)
    }

    /**
     * Double lines turn a corner as two nested corners, and pass a junction without crossing it.
     * The middle of the cell is a block three strokes wide and tall: its corners are always
     * filled, and each side is closed only where no line leaves through it.
     */
    private fun doubleLines(s: Segs, x: Float, y: Float, w: Float, h: Float, t: Float) {
        val bx = x + inset(w, t * 3)
        val by = y + inset(h, t * 3)
        fun add(x0: Float, y0: Float, x1: Float, y1: Float) = path.addRect(x0, y0, x1, y1, Path.Direction.CW)
        if (s.l != NONE) {
            add(x, by, bx, by + t)
            add(x, by + t * 2, bx, by + t * 3)
        } else {
            add(bx, by + t, bx + t, by + t * 2)
        }
        if (s.r != NONE) {
            add(bx + t * 3, by, x + w, by + t)
            add(bx + t * 3, by + t * 2, x + w, by + t * 3)
        } else {
            add(bx + t * 2, by + t, bx + t * 3, by + t * 2)
        }
        if (s.u != NONE) {
            add(bx, y, bx + t, by)
            add(bx + t * 2, y, bx + t * 3, by)
        } else {
            add(bx + t, by, bx + t * 2, by + t)
        }
        if (s.d != NONE) {
            add(bx, by + t * 3, bx + t, y + h)
            add(bx + t * 2, by + t * 3, bx + t * 3, y + h)
        } else {
            add(bx + t, by + t * 2, bx + t * 2, by + t * 3)
        }
        add(bx, by, bx + t, by + t)
        add(bx + t * 2, by, bx + t * 3, by + t)
        add(bx, by + t * 2, bx + t, by + t * 3)
        add(bx + t * 2, by + t * 2, bx + t * 3, by + t * 3)
    }

    /** How many dashes a dashed line character has, or 0 for a solid one. */
    private fun dashCount(cp: Int) = when (cp) {
        in 0x2504..0x2507 -> 3
        in 0x2508..0x250B -> 4
        in 0x254C..0x254F -> 2
        else -> 0
    }

    private fun dashed(canvas: Canvas, s: Segs, count: Int, x: Float, y: Float, w: Float, h: Float, thin: Float) {
        if (s.l != NONE) {
            val t = width(s.l, thin)
            val top = y + inset(h, t)
            val step = w / count
            val gap = maxOf(1f, Math.round(step / 3f).toFloat())
            for (i in 0 until count) {
                val from = x + Math.round(i * step + gap / 2f)
                val to = x + Math.round((i + 1) * step - gap / 2f)
                canvas.drawRect(from, top, maxOf(to, from + 1f), top + t, fill)
            }
        } else {
            val t = width(s.u, thin)
            val left = x + inset(w, t)
            val step = h / count
            val gap = maxOf(1f, Math.round(step / 3f).toFloat())
            for (i in 0 until count) {
                val from = y + Math.round(i * step + gap / 2f)
                val to = y + Math.round((i + 1) * step - gap / 2f)
                canvas.drawRect(left, from, left + t, maxOf(to, from + 1f), fill)
            }
        }
    }

    private fun stroke(thin: Float) {
        smooth.style = Paint.Style.STROKE
        smooth.strokeWidth = thin
    }

    private fun rounded(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, thin: Float) {
        // The middle of the lines the neighbouring cells draw, so the ends meet them exactly.
        val cx = x + inset(w, thin) + thin / 2
        val cy = y + inset(h, thin) + thin / 2
        val radius = minOf(w, h) / 2
        val right = cp == 0x256D || cp == 0x2570
        val down = cp == 0x256D || cp == 0x256E
        val endX = if (right) x + w else x
        val endY = if (down) y + h else y
        val turnX = if (right) cx + radius else cx - radius
        val turnY = if (down) cy + radius else cy - radius
        path.rewind()
        path.moveTo(endX, cy)
        path.lineTo(turnX, cy)
        path.quadTo(cx, cy, cx, turnY)
        path.lineTo(cx, endY)
        stroke(thin)
        canvas.drawPath(path, smooth)
    }

    private fun diagonal(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, thin: Float) {
        stroke(thin)
        if (cp == 0x2571 || cp == 0x2573) canvas.drawLine(x + w, y, x, y + h, smooth)
        if (cp == 0x2572 || cp == 0x2573) canvas.drawLine(x, y, x + w, y + h, smooth)
    }

    private fun block(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float) {
        // Parts of a cell end on whole pixels, so blocks stacked into bars and charts stay even.
        fun part(x0: Float, y0: Float, x1: Float, y1: Float) {
            canvas.drawRect(x + Math.round(w * x0), y + Math.round(h * y0), x + Math.round(w * x1), y + Math.round(h * y1), fill)
        }
        fun shade(alpha: Int) {
            val full = fill.alpha
            fill.alpha = full * alpha / 4
            part(0f, 0f, 1f, 1f)
            fill.alpha = full
        }
        when (cp) {
            0x2580 -> part(0f, 0f, 1f, 0.5f)
            in 0x2581..0x2588 -> part(0f, 1f - (cp - 0x2580) / 8f, 1f, 1f)
            in 0x2589..0x258F -> part(0f, 0f, (0x2590 - cp) / 8f, 1f)
            0x2590 -> part(0.5f, 0f, 1f, 1f)
            0x2591 -> shade(1)
            0x2592 -> shade(2)
            0x2593 -> shade(3)
            0x2594 -> part(0f, 0f, 1f, 0.125f)
            0x2595 -> part(0.875f, 0f, 1f, 1f)
            else -> {
                // Quarter blocks: upper left, upper right, lower left, lower right.
                val quarters = QUARTERS[cp - 0x2596]
                if (quarters and 8 != 0) part(0f, 0f, 0.5f, 0.5f)
                if (quarters and 4 != 0) part(0.5f, 0f, 1f, 0.5f)
                if (quarters and 2 != 0) part(0f, 0.5f, 0.5f, 1f)
                if (quarters and 1 != 0) part(0.5f, 0.5f, 1f, 1f)
            }
        }
    }

    /**
     * Sextants: the cell as two columns of three, which programs draw pictures and charts with.
     * Each sixth ends on the same whole pixels as its neighbours, so a picture has no gaps.
     */
    private fun sextant(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float) {
        val bits = sextantBits(cp)
        val middle = x + Math.round(w / 2f)
        val first = y + Math.round(h / 3f)
        val second = y + Math.round(h * 2f / 3f)
        for (i in 0 until 6) {
            if (bits and (1 shl i) == 0) continue
            val left = if (i % 2 == 0) x else middle
            val right = if (i % 2 == 0) middle else x + w
            val top = when (i / 2) {
                0 -> y
                1 -> first
                else -> second
            }
            val bottom = when (i / 2) {
                0 -> first
                1 -> second
                else -> y + h
            }
            canvas.drawRect(left, top, right, bottom, fill)
        }
    }

    /** For U+2596 to U+259F, which quarters are filled: 8 upper left, 4 upper right, 2 lower left, 1 lower right. */
    private val QUARTERS = intArrayOf(2, 1, 8, 8 or 2 or 1, 8 or 1, 8 or 4 or 2, 8 or 4 or 1, 4, 4 or 2, 4 or 2 or 1)

    /** Braille patterns as a grid of two by four dots, used for spinners and small charts. */
    private fun braille(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float) {
        val bits = cp - 0x2800
        if (bits == 0) return
        val dot = maxOf(1f, Math.round(minOf(w / 2f, h / 4f) * 0.5f).toFloat())
        for (i in 0 until 8) {
            if (bits and (1 shl i) == 0) continue
            val col = if (i == 3 || i == 4 || i == 5 || i == 7) 1 else 0
            val row = when (i) {
                0, 3 -> 0
                1, 4 -> 1
                2, 5 -> 2
                else -> 3
            }
            val left = x + Math.round(w * (0.25f + 0.5f * col) - dot / 2f)
            val top = y + Math.round(h * (0.125f + 0.25f * row) - dot / 2f)
            canvas.drawRect(left, top, left + dot, top + dot, fill)
        }
    }

    /** The arrows that end a prompt's or status line's segments, filling the cell's whole height. */
    private fun powerline(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, thin: Float) {
        val pointsRight = cp == 0xE0B0 || cp == 0xE0B1
        val base = if (pointsRight) x else x + w
        val tip = if (pointsRight) x + w else x
        path.rewind()
        path.moveTo(base, y)
        path.lineTo(tip, y + h / 2)
        path.lineTo(base, y + h)
        if (cp == 0xE0B0 || cp == 0xE0B2) {
            path.close()
            smooth.style = Paint.Style.FILL
        } else {
            stroke(thin)
        }
        canvas.drawPath(path, smooth)
    }
}
