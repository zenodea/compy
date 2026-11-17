package dev.compy.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneNeighborsTest {
    private val whole = FractionRect(0f, 0f, 1f, 1f)
    private fun leaf(id: String) = LayoutNode.Leaf(whole, id)
    private fun split(horizontal: Boolean, ratio: Float, before: LayoutNode, after: LayoutNode) =
        LayoutNode.Split(whole, "s", horizontal, ratio, before, after)

    private fun window(layout: LayoutNode): WindowView {
        val blocks = paneRects(layout).map { BlockInfo(it.first, "", "", "", null) }
        return WindowView("w", "", null, null, listOf(LayerView("l", floating = false, minimized = false, whole, layout, blocks)))
    }

    // a | b
    //   | c
    private val three = window(split(true, 0.5f, leaf("a"), split(false, 0.5f, leaf("b"), leaf("c"))))

    @Test fun findsThePaneOnEachSide() {
        assertEquals("a", three.neighbor("b", Side.Left))
        assertEquals("c", three.neighbor("b", Side.Down))
        assertEquals("b", three.neighbor("c", Side.Up))
        assertEquals("a", three.neighbor("c", Side.Left))
    }

    @Test fun nothingPastTheWindowsEdge() {
        assertNull(three.neighbor("a", Side.Left))
        assertNull(three.neighbor("a", Side.Up))
        assertNull(three.neighbor("b", Side.Right))
        assertNull(three.neighbor("nowhere", Side.Right))
    }

    @Test fun takesThePaneSharingMoreOfTheEdge() {
        // a is beside both b and c; b takes the top 70% of the right half.
        val uneven = window(split(true, 0.5f, leaf("a"), split(false, 0.7f, leaf("b"), leaf("c"))))
        assertEquals("b", uneven.neighbor("a", Side.Right))
    }

    @Test fun takesTheNearestPaneNotOneBeyondIt() {
        val row = window(split(true, 0.33f, leaf("a"), split(true, 0.5f, leaf("b"), leaf("c"))))
        assertEquals("b", row.neighbor("a", Side.Right))
        assertEquals("b", row.neighbor("c", Side.Left))
    }

    @Test fun findsThePaneAtAPoint() {
        assertEquals("a", three.paneAt(0.2f, 0.9f))
        assertEquals("b", three.paneAt(0.75f, 0.25f))
        assertEquals("c", three.paneAt(0.75f, 0.75f))
        assertNull(three.paneAt(1.2f, 0.5f))
    }

    @Test fun aFloatingPaneIsOverTheLayout() {
        val float = LayerView("f", floating = true, minimized = false, FractionRect(0.1f, 0.1f, 0.5f, 0.5f), leaf("x"), listOf(BlockInfo("x", "", "", "", null)))
        val hidden = float.copy(id = "g", minimized = true, blocks = listOf(BlockInfo("y", "", "", "", null)))
        val window = three.copy(layers = three.layers + float + hidden)
        assertEquals("x", window.paneUnder(0.3f, 0.3f))
        // Past the floating pane, and under a minimized one, the layout's pane is what is there.
        assertEquals("c", window.paneUnder(0.75f, 0.75f))
        assertEquals(listOf("a", "b", "c", "x"), dev.compy.ui.mapPanes(window).map { it.blockId })
        assertEquals(listOf(false, false, false, true), dev.compy.ui.mapPanes(window).map { it.floating })
    }

    private val tabs = SessionView(1, "s", "", "w2", listOf("w1", "w2", "w3").map { WindowView(it, "", null, null, emptyList()) })

    @Test fun movesATabOnePlace() {
        assertEquals(TabMove("w1"), tabs.tabMoveTarget("w2", forward = false))
        // Forward past w3 is the end, which has no window to go before.
        assertEquals(TabMove(null), tabs.tabMoveTarget("w2", forward = true))
        assertEquals(TabMove("w3"), tabs.tabMoveTarget("w1", forward = true))
    }

    @Test fun doesNotMoveATabPastEitherEnd() {
        assertNull(tabs.tabMoveTarget("w1", forward = false))
        assertNull(tabs.tabMoveTarget("w3", forward = true))
        assertNull(tabs.tabMoveTarget("gone", forward = true))
    }
}
