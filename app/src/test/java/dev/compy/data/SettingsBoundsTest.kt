package dev.compy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsBoundsTest {
    @Test
    fun settingsWithinTheirLimitsAreLeftAsTheyAre() {
        val settings = Settings(chatPanes = setOf("a", "b"), composerHistory = listOf("one"))
        assertSame(settings, settings.bounded())
    }

    @Test
    fun whatIsRememberedPerPaneKeepsOnlyTheNewest() {
        val panes = (1..500).map { "block-$it" }
        val settings = Settings(
            chatPanes = panes.toSet(),
            paneFontSizes = panes.associateWith { 12f },
            sizeModes = panes.associateWith { SizeMode.Keep },
            collapsedSessions = panes.toSet(),
            composerHistory = (1..80).map { "message $it" },
            snippets = (1..80).map { "snippet $it" },
        ).bounded()
        assertEquals(MAX_REMEMBERED, settings.chatPanes.size)
        assertEquals(setOf("block-500", "block-301"), setOf(settings.chatPanes.last(), settings.chatPanes.first()))
        assertEquals(MAX_REMEMBERED, settings.paneFontSizes.size)
        assertEquals("block-500", settings.paneFontSizes.keys.last())
        assertEquals(MAX_REMEMBERED, settings.sizeModes.size)
        assertEquals(MAX_REMEMBERED, settings.collapsedSessions.size)
        // The composer's lists hold the newest first, so the start is what stays.
        assertEquals(30, settings.composerHistory.size)
        assertEquals("message 1", settings.composerHistory.first())
        assertEquals(50, settings.snippets.size)
    }
}
