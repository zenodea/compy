package dev.compy.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerTest {
    @Test
    fun remembersTheNewestFirstAndOnce() {
        assertEquals(listOf("b", "a"), Composer.remembered(listOf("a"), "b", 30))
        assertEquals(listOf("a", "b"), Composer.remembered(listOf("b", "a"), "a", 30))
        assertEquals(listOf("c", "b"), Composer.remembered(listOf("b", "a"), "c", 2))
        assertEquals(listOf("a"), Composer.remembered(listOf("a"), "  ", 30))
    }

    @Test
    fun showsOneLineOfAMessage() {
        assertEquals("fix the tests", Composer.preview("fix the tests"))
        assertEquals("fix the tests …", Composer.preview("fix the tests\nthen commit"))
        assertEquals(60, Composer.preview("x".repeat(200)).length)
    }

    @Test
    fun addsSpokenWordsAfterWhatIsThere() {
        assertEquals("run it", Composer.appended("", "run it"))
        assertEquals("first run it", Composer.appended("first", "run it"))
        assertEquals("first run it", Composer.appended("first ", "run it"))
    }
}
