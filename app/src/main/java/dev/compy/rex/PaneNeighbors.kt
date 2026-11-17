package dev.compy.rex

/** Every pane of a split tree with the part of the window it fills, in the tree's own order. */
fun paneRects(node: LayoutNode?, rect: FractionRect = FractionRect(0f, 0f, 1f, 1f)): List<Pair<String, FractionRect>> = when (node) {
    null -> emptyList()
    is LayoutNode.Leaf -> listOf(node.blockId to rect)
    is LayoutNode.Split -> if (node.horizontal) {
        val w = rect.w * node.ratio
        paneRects(node.before, FractionRect(rect.x, rect.y, w, rect.h)) +
            paneRects(node.after, FractionRect(rect.x + w, rect.y, rect.w - w, rect.h))
    } else {
        val h = rect.h * node.ratio
        paneRects(node.before, FractionRect(rect.x, rect.y, rect.w, h)) +
            paneRects(node.after, FractionRect(rect.x, rect.y + h, rect.w, rect.h - h))
    }
}

/**
 * The pane beside another in a window's layout: the nearest one on that side that shares part of
 * its edge, and of two equally near, the one sharing more of it. Null when the pane is at the
 * window's edge, or isn't one of the layout's panes, as a floating pane isn't.
 */
fun WindowView.neighbor(blockId: String, side: Side): String? {
    val panes = paneRects(tiledLayer?.layout)
    val from = panes.firstOrNull { it.first == blockId }?.second ?: return null
    var best: String? = null
    var bestGap = Float.MAX_VALUE
    var bestShared = 0f
    for ((id, r) in panes) {
        if (id == blockId) continue
        val gap = when (side) {
            Side.Left -> from.x - (r.x + r.w)
            Side.Right -> r.x - (from.x + from.w)
            Side.Up -> from.y - (r.y + r.h)
            Side.Down -> r.y - (from.y + from.h)
        }
        val shared = if (side.horizontal) {
            minOf(from.y + from.h, r.y + r.h) - maxOf(from.y, r.y)
        } else {
            minOf(from.x + from.w, r.x + r.w) - maxOf(from.x, r.x)
        }
        if (gap < -EDGE || shared <= EDGE) continue
        if (gap < bestGap - EDGE || (gap < bestGap + EDGE && shared > bestShared)) {
            best = id
            bestGap = gap
            bestShared = shared
        }
    }
    return best
}

/**
 * The pane of a window's layout at a point, given in fractions of the window's width and height.
 * Null where no pane of the layout is, as with only floating panes.
 */
fun WindowView.paneAt(fx: Float, fy: Float): String? =
    paneRects(tiledLayer?.layout).firstOrNull { (_, r) -> fx >= r.x && fx < r.x + r.w && fy >= r.y && fy < r.y + r.h }?.first

/** The pane under a point of a window: a floating pane where one lies there, the front one first, or else the layout's. */
fun WindowView.paneUnder(fx: Float, fy: Float): String? =
    floatingLayers.lastOrNull { !it.minimized && fx >= it.bounds.x && fx < it.bounds.x + it.bounds.w && fy >= it.bounds.y && fy < it.bounds.y + it.bounds.h }
        ?.blocks?.firstOrNull()?.id
        ?: paneAt(fx, fy)

/** The window before which a window goes to move one place along its session's tabs, or null for the end. */
fun SessionView.tabMoveTarget(windowId: String, forward: Boolean): TabMove? {
    val at = windows.indexOfFirst { it.id == windowId }
    if (at < 0) return null
    return if (forward) {
        if (at == windows.lastIndex) null else TabMove(windows.getOrNull(at + 2)?.id)
    } else {
        if (at == 0) null else TabMove(windows[at - 1].id)
    }
}

/** Where a tab moves to: just before [beforeWindowId], or to the end when that is null. */
data class TabMove(val beforeWindowId: String?)

/** Rounding in the fractions a layout is given in. */
private const val EDGE = 0.001f
