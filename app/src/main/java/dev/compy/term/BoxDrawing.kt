package dev.compy.term

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

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
        // Dashed lines are drawn solid, which reads better at phone sizes.
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

    fun handles(cp: Int): Boolean =
        (cp in 0x2500..0x257F && (cp in table || cp in 0x256D..0x2570 || cp in 0x2571..0x2573)) || cp in 0x2580..0x2595

    /** Draws the character in the given cell using [paint]'s colour. */
    fun draw(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, paint: Paint, rect: RectF, path: Path) {
        paint.style = Paint.Style.FILL
        val thin = maxOf(1f, Math.round(h / 16f).toFloat())
        when (cp) {
            in 0x256D..0x2570 -> rounded(canvas, cp, x, y, w, h, thin, paint, path)
            in 0x2571..0x2573 -> diagonal(canvas, cp, x, y, w, h, thin, paint)
            in 0x2580..0x2595 -> block(canvas, cp, x, y, w, h, paint, rect)
            else -> table[cp]?.let { lines(canvas, it, x, y, w, h, thin, paint, rect) }
        }
    }

    private fun lines(canvas: Canvas, s: Segs, x: Float, y: Float, w: Float, h: Float, thin: Float, p: Paint, r: RectF) {
        val cx = x + Math.round(w / 2f)
        val cy = y + Math.round(h / 2f)
        fun horizontal(x0: Float, x1: Float, weight: Int) {
            if (weight == NONE) return
            val t = if (weight == HEAVY) thin * 2 else thin
            if (weight == DOUBLE) {
                r.set(x0, cy - thin * 1.5f, x1, cy - thin * 0.5f); canvas.drawRect(r, p)
                r.set(x0, cy + thin * 0.5f, x1, cy + thin * 1.5f); canvas.drawRect(r, p)
            } else {
                r.set(x0, cy - t / 2, x1, cy + t / 2); canvas.drawRect(r, p)
            }
        }
        fun vertical(y0: Float, y1: Float, weight: Int) {
            if (weight == NONE) return
            val t = if (weight == HEAVY) thin * 2 else thin
            if (weight == DOUBLE) {
                r.set(cx - thin * 1.5f, y0, cx - thin * 0.5f, y1); canvas.drawRect(r, p)
                r.set(cx + thin * 0.5f, y0, cx + thin * 1.5f, y1); canvas.drawRect(r, p)
            } else {
                r.set(cx - t / 2, y0, cx + t / 2, y1); canvas.drawRect(r, p)
            }
        }
        val reach = thin / 2
        horizontal(x, cx + if (s.l != NONE && (s.u != NONE || s.d != NONE)) reach else 0f, s.l)
        horizontal(cx - if (s.r != NONE && (s.u != NONE || s.d != NONE)) reach else 0f, x + w, s.r)
        vertical(y, cy + if (s.u != NONE && (s.l != NONE || s.r != NONE)) reach else 0f, s.u)
        vertical(cy - if (s.d != NONE && (s.l != NONE || s.r != NONE)) reach else 0f, y + h, s.d)
    }

    private fun rounded(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, thin: Float, p: Paint, path: Path) {
        val cx = x + Math.round(w / 2f)
        val cy = y + Math.round(h / 2f)
        val radius = minOf(w, h) / 2
        path.rewind()
        when (cp) {
            0x256D -> { path.moveTo(x + w, cy); path.quadTo(cx, cy, cx, cy + radius); path.lineTo(cx, y + h) }
            0x256E -> { path.moveTo(x, cy); path.quadTo(cx, cy, cx, cy + radius); path.lineTo(cx, y + h) }
            0x256F -> { path.moveTo(x, cy); path.quadTo(cx, cy, cx, cy - radius); path.lineTo(cx, y) }
            0x2570 -> { path.moveTo(x + w, cy); path.quadTo(cx, cy, cx, cy - radius); path.lineTo(cx, y) }
        }
        val style = p.style
        val width = p.strokeWidth
        p.style = Paint.Style.STROKE
        p.strokeWidth = thin
        canvas.drawPath(path, p)
        p.style = style
        p.strokeWidth = width
    }

    private fun diagonal(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, thin: Float, p: Paint) {
        val width = p.strokeWidth
        p.strokeWidth = thin
        if (cp == 0x2571 || cp == 0x2573) canvas.drawLine(x + w, y, x, y + h, p)
        if (cp == 0x2572 || cp == 0x2573) canvas.drawLine(x, y, x + w, y + h, p)
        p.strokeWidth = width
    }

    private fun block(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, p: Paint, r: RectF) {
        val alpha = p.alpha
        fun fill(x0: Float, y0: Float, x1: Float, y1: Float) {
            r.set(x + w * x0, y + h * y0, x + w * x1, y + h * y1)
            canvas.drawRect(r, p)
        }
        when (cp) {
            0x2580 -> fill(0f, 0f, 1f, 0.5f)
            in 0x2581..0x2588 -> fill(0f, 1f - (cp - 0x2580) / 8f, 1f, 1f)
            in 0x2589..0x258F -> fill(0f, 0f, (0x2590 - cp) / 8f, 1f)
            0x2590 -> fill(0.5f, 0f, 1f, 1f)
            0x2591 -> { p.alpha = alpha / 4; fill(0f, 0f, 1f, 1f); p.alpha = alpha }
            0x2592 -> { p.alpha = alpha / 2; fill(0f, 0f, 1f, 1f); p.alpha = alpha }
            0x2593 -> { p.alpha = alpha * 3 / 4; fill(0f, 0f, 1f, 1f); p.alpha = alpha }
            0x2594 -> fill(0f, 0f, 1f, 0.125f)
            0x2595 -> fill(0.875f, 0f, 1f, 1f)
        }
    }
}
