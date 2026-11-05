package dev.compy.ui

import dev.compy.rex.BlockInfo
import dev.compy.rex.FractionRect
import dev.compy.rex.layerBounds
import dev.compy.rex.parseDetachedBlocks
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingLayerTest {
    private val start = FractionRect(0.1f, 0.1f, 0.5f, 0.5f)

    @Test
    fun movingStaysInsideTheWindow() {
        assertEquals(FractionRect(0.5f, 0.5f, 0.5f, 0.5f), moved(start, 5f, 5f))
        assertEquals(FractionRect(0f, 0f, 0.5f, 0.5f), moved(start, -5f, -5f))
    }

    @Test
    fun resizingStopsAtTheWindowEdgeAndAUsableMinimum() {
        val big = resized(start, 5f, 5f)
        assertEquals(0.9f, big.w, 0.0001f)
        assertEquals(0.9f, big.h, 0.0001f)
        val small = resized(start, -5f, -5f)
        assertEquals(0.3f, small.w, 0.0001f)
        assertEquals(0.2f, small.h, 0.0001f)
    }

    @Test
    fun aLayerInTheCornerCanStillBeResized() {
        val corner = FractionRect(0.9f, 0.9f, 0.1f, 0.1f)
        val after = resized(corner, -5f, -5f)
        assertEquals(0.1f, after.w, 0.0001f)
        assertEquals(0.1f, after.h, 0.0001f)
    }

    @Test
    fun boundsSentToTheServerAlwaysFitTheWindow() {
        // Each of these adds up to just over 1 if the floats are simply widened to doubles.
        var x = 0f
        while (x < 0.7f) {
            val (bx, by, bw, bh) = layerBounds(resized(FractionRect(x, x, 0.3f, 0.3f), 5f, 5f))
            assertTrue("$x", bx + bw <= 1.0 && by + bh <= 1.0 && bw > 0.0 && bh > 0.0)
            x += 0.01f
        }
    }

    @Test
    fun aDraggedPaneSplitsTowardTheNearestEdge() {
        assertEquals(DropZone.Left, splitZone(0.1f, 0.5f))
        assertEquals(DropZone.Right, splitZone(0.9f, 0.5f))
        assertEquals(DropZone.Top, splitZone(0.5f, 0.1f))
        assertEquals(DropZone.Bottom, splitZone(0.5f, 0.9f))
    }

    @Test
    fun theHighlightCoversTheHalfThePaneWouldTake() {
        val pane = FractionRect(0.5f, 0f, 0.5f, 1f)
        assertEquals(FractionRect(0.5f, 0f, 0.25f, 1f), zoneRect(pane, DropZone.Left))
        assertEquals(FractionRect(0.75f, 0f, 0.25f, 1f), zoneRect(pane, DropZone.Right))
        assertEquals(FractionRect(0.5f, 0.5f, 0.5f, 0.5f), zoneRect(pane, DropZone.Bottom))
    }

    @Test
    fun onlyPlacedBlocksAreLeftOutOfTheDetachedList() {
        val json = Json.parseToJsonElement(
            """[
              {"block_id":"a","placement":"placed","label":"","creator_name":"t","flavor":"f"},
              {"block_id":"b","placement":"detached","label":"loose","creator_name":"t","flavor":"f"},
              {"block_id":"c","placement":"somewhere-new","creator_name":"t","flavor":"f"}
            ]""",
        )
        assertEquals(
            listOf(BlockInfo("b", "loose", "t", "f", null), BlockInfo("c", "", "t", "f", null)),
            parseDetachedBlocks(json),
        )
    }
}
