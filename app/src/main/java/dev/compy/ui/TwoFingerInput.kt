package dev.compy.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.compy.rex.Side
import dev.compy.term.TerminalView
import dev.compy.term.TwoFinger

/**
 * Watches for two fingers on the panes: a swipe, which goes to the pane on a side, and a tap,
 * which zooms a pane or shows them all again. It looks at touches on their way down to the
 * panes, so it works the same over a terminal and over an agent's chat, and takes a touch away
 * from them only once it knows the gesture is its own. A pinch is left to the terminal.
 *
 * [onSwipe] is given the side to go to. [onTap] is given where the fingers were, in fractions of
 * the area's width and height.
 */
internal fun Modifier.twoFingerGestures(enabled: Boolean, onSwipe: (Side) -> Unit, onTap: (Offset) -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        val slop = TerminalView.TWO_FINGER_SLOP_DP.dp.toPx()
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var start: Offset? = null
            var startSpan = 0f
            var startedAt = 0L
            var kind = TwoFinger.Kind.Undecided
            // More than two fingers, or a pinch: not this gesture's to take.
            var left = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.filter { it.pressed }
                if (down.size > 2) left = true
                if (down.size == 2 && !left && kind == TwoFinger.Kind.Undecided) {
                    val middle = (down[0].position + down[1].position) / 2f
                    val span = (down[0].position - down[1].position).getDistance()
                    val from = start
                    if (from == null) {
                        start = middle
                        startSpan = span
                        startedAt = down[1].uptimeMillis
                    } else {
                        val moved = middle - from
                        kind = TwoFinger.classify(moved.x, moved.y, span - startSpan, slop)
                        if (kind == TwoFinger.Kind.Swipe) onSwipe(TwoFinger.side(moved.x, moved.y))
                    }
                }
                val from = start
                when {
                    // The rest of a swipe is this gesture's too, or the pane would scroll under it.
                    kind == TwoFinger.Kind.Swipe -> event.changes.forEach { it.consume() }
                    // A finger lifting from what may be a tap: kept from the pane, which would
                    // take one finger's tap for its own.
                    from != null && !left && kind == TwoFinger.Kind.Undecided -> {
                        val lifting = event.changes.filter { it.changedToUp() }
                        lifting.forEach { it.consume() }
                        if (down.isEmpty() && lifting.isNotEmpty() && lifting[0].uptimeMillis - startedAt < TAP_MS) {
                            onTap(Offset(from.x / size.width.coerceAtLeast(1), from.y / size.height.coerceAtLeast(1)))
                        }
                    }
                }
                if (down.isEmpty()) break
            }
        }
    }

/** Two fingers down and up again within this long are a tap. */
private const val TAP_MS = 350L
