package dev.compy.term

import dev.compy.rex.Side
import org.junit.Assert.assertEquals
import org.junit.Test

class TwoFingerTest {
    private val slop = 40f

    @Test fun fingersThatBarelyMoveMayStillBeATap() {
        assertEquals(TwoFinger.Kind.Undecided, TwoFinger.classify(10f, -12f, 8f, slop))
    }

    @Test fun fingersThatTravelTogetherSwipe() {
        assertEquals(TwoFinger.Kind.Swipe, TwoFinger.classify(-120f, 6f, 10f, slop))
        // One finger lagging behind the other still swipes.
        assertEquals(TwoFinger.Kind.Swipe, TwoFinger.classify(0f, 90f, -50f, slop))
    }

    @Test fun fingersThatPartOrCloseArePinching() {
        assertEquals(TwoFinger.Kind.Pinch, TwoFinger.classify(4f, 3f, 80f, slop))
        assertEquals(TwoFinger.Kind.Pinch, TwoFinger.classify(0f, 0f, -60f, slop))
        // One finger still and the other moving: the point between them moves half as far as they part.
        assertEquals(TwoFinger.Kind.Pinch, TwoFinger.classify(30f, 0f, 60f, slop))
    }

    @Test fun panesFollowTheFingersAsPagesDo() {
        assertEquals(Side.Right, TwoFinger.side(-100f, 10f))
        assertEquals(Side.Left, TwoFinger.side(100f, -10f))
        assertEquals(Side.Down, TwoFinger.side(10f, -100f))
        assertEquals(Side.Up, TwoFinger.side(-10f, 100f))
    }
}
