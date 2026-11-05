package dev.compy.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLinksTest {
    @Test fun findsALinkAndItsCells() {
        val link = TerminalLinks.find(listOf("see https://example.com/a now ")).single()
        assertEquals("https://example.com/a", link.url)
        assertTrue(link.contains(4, 0))
        assertTrue(link.contains(24, 0))
        assertFalse(link.contains(3, 0))
        assertFalse(link.contains(25, 0))
    }

    @Test fun dropsSentencePunctuation() {
        val link = TerminalLinks.find(listOf("go to https://example.com. ")).single()
        assertEquals("https://example.com", link.url)
        assertFalse(link.contains(25, 0))
    }

    @Test fun keepsBalancedBrackets() {
        assertEquals("https://example.com/a_(b)", TerminalLinks.find(listOf("https://example.com/a_(b) ")).single().url)
    }

    @Test fun joinsALinkWrappedAcrossRows() {
        val link = TerminalLinks.find(listOf("https://example", ".com/long/path ", "after          ")).single()
        assertEquals("https://example.com/long/path", link.url)
        assertTrue(link.contains(3, 1))
        assertFalse(link.contains(0, 2))
    }

    @Test fun ignoresOtherText() {
        assertEquals(emptyList<TerminalLinks.Link>(), TerminalLinks.find(listOf("no links here", "ftp://nope")))
    }
}
