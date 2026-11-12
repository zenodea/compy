package dev.compy.ui

import dev.compy.rex.ProgramStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BoardRulesTest {
    private fun status(state: String) = ProgramStatus(state, null, "claude", null)

    @Test
    fun sortsProgramsIntoColumns() {
        assertEquals(BoardColumn.Running, BoardRules.column(status("working"), unseen = false))
        assertEquals(BoardColumn.Attention, BoardRules.column(status("blocked"), unseen = false))
        assertEquals(BoardColumn.Attention, BoardRules.column(status("error"), unseen = false))
        assertEquals(BoardColumn.Done, BoardRules.column(status("done"), unseen = false))
        assertEquals(BoardColumn.Idle, BoardRules.column(status("idle"), unseen = false))
        assertEquals(BoardColumn.Idle, BoardRules.column(null, unseen = false))
    }

    @Test
    fun aPaneThatFinishedOutOfSightIsDone() {
        assertEquals(BoardColumn.Done, BoardRules.column(null, unseen = true))
        // Still working wins over an old mark.
        assertEquals(BoardColumn.Running, BoardRules.column(status("working"), unseen = true))
    }

    @Test
    fun knowsAgentsByNameOrByTheirReports() {
        assertTrue(BoardRules.isAgent(null, "claude"))
        assertTrue(BoardRules.isAgent(null, "node/claude-code.js"))
        assertTrue(BoardRules.isAgent(null, "-codex"))
        assertTrue(BoardRules.isAgent(status("working"), "make"))
        assertFalse(BoardRules.isAgent(null, "zsh"))
        assertFalse(BoardRules.isAgent(null, null))
    }

    @Test
    fun opensOnTheMostPressingColumn() {
        assertEquals(BoardColumn.Attention, BoardRules.firstColumn(mapOf(BoardColumn.Attention to 1, BoardColumn.Running to 4)))
        assertEquals(BoardColumn.Running, BoardRules.firstColumn(mapOf(BoardColumn.Running to 2, BoardColumn.Idle to 3)))
        assertEquals(BoardColumn.Idle, BoardRules.firstColumn(mapOf(BoardColumn.Idle to 3)))
        assertEquals(BoardColumn.Attention, BoardRules.firstColumn(emptyMap()))
    }

    @Test
    fun saysHowLongAgo() {
        val now = Instant.parse("2026-11-12T10:00:00Z")
        assertEquals("now", BoardRules.ago("2026-11-12T09:59:30Z", now))
        assertEquals("5m", BoardRules.ago("2026-11-12T09:55:00Z", now))
        assertEquals("2h", BoardRules.ago("2026-11-12T07:10:00Z", now))
        assertEquals("3d", BoardRules.ago("2026-11-09T09:00:00Z", now))
        assertEquals("now", BoardRules.ago("2026-11-12T10:00:05Z", now))
        assertEquals("", BoardRules.ago(null, now))
        assertEquals("", BoardRules.ago("yesterday", now))
    }
}
