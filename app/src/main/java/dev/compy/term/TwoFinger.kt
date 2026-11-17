package dev.compy.term

import dev.compy.rex.Side
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Tells what two fingers on a pane are doing. A pinch sizes the pane's text and a swipe goes to
 * the pane beside this one, and both start the same way, so each is named only once the fingers
 * have moved far enough to tell. The terminal and the screen around it both ask here, so they
 * never disagree about one gesture.
 */
object TwoFinger {
    enum class Kind { Undecided, Pinch, Swipe }

    /**
     * [dx] and [dy] are how far the point between the fingers has moved, and [stretch] how much
     * further apart they are, all since the second finger came down. Under [slop] either way,
     * it could still be a tap. Past it, fingers that part or close by more than they travel are
     * pinching, and fingers that travel together are swiping.
     */
    fun classify(dx: Float, dy: Float, stretch: Float, slop: Float): Kind {
        val travel = hypot(dx, dy)
        val spread = abs(stretch)
        return when {
            travel < slop && spread < slop -> Kind.Undecided
            spread > travel -> Kind.Pinch
            else -> Kind.Swipe
        }
    }

    /**
     * The side a swipe goes to. Panes follow the fingers as pages do: swiping left brings in the
     * pane on the right, and swiping up the one below.
     */
    fun side(dx: Float, dy: Float): Side = when {
        abs(dx) >= abs(dy) -> if (dx < 0) Side.Right else Side.Left
        else -> if (dy < 0) Side.Down else Side.Up
    }
}
