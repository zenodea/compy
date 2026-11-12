package dev.compy.ui

import dev.compy.rex.ProgramStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardRulesTest {
    private fun status(state: String, kind: String? = null, message: String? = null, task: String? = null, app: String? = "claude") =
        ProgramStatus(state, kind, app, message, task = task)

    @Test
    fun sortsProgramsIntoColumns() {
        assertEquals(BoardColumn.Running, BoardRules.column(status("working")))
        assertEquals(BoardColumn.Attention, BoardRules.column(status("blocked")))
        assertEquals(BoardColumn.Attention, BoardRules.column(status("error")))
        assertEquals(BoardColumn.Done, BoardRules.column(status("done")))
        // Anything else an agent reports, and an agent that reports nothing, is idle.
        assertEquals(BoardColumn.Idle, BoardRules.column(status("idle")))
        assertEquals(BoardColumn.Idle, BoardRules.column(null))
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
    fun namesACardByItsTaskThenItsTitleThenItsProgram() {
        assertEquals("Fix the parser", BoardRules.title(status("working", task = "Fix the parser"), "claude", "claude ~/compy"))
        assertEquals("claude ~/compy", BoardRules.title(status("working"), "claude", "claude ~/compy"))
        assertEquals("claude", BoardRules.title(status("working"), "node/cli.js", null))
        assertEquals("codex", BoardRules.title(null, "-codex", ""))
        // A shell's title is its prompt's, so a shell that reports a status goes by its name.
        assertEquals("zsh", BoardRules.title(status("working", app = null), "zsh", "~/compy"))
        assertEquals("shell", BoardRules.title(null, null, "anything"))
    }

    @Test
    fun saysWhatAnAgentSaysOrWhatItWaitsOn() {
        assertEquals("Running tests", BoardRules.message(status("working", message = "Running tests")))
        assertEquals("permission prompt", BoardRules.message(status("blocked", kind = "permission")))
        assertEquals("question", BoardRules.message(status("blocked", kind = "question", message = "")))
        assertNull(BoardRules.message(status("done")))
        assertNull(BoardRules.message(null))
    }

    @Test
    fun saysWhereAPaneIs() {
        assertEquals("api · ~/work/api", BoardRules.where("api", "~/work/api", bySession = false))
        assertEquals("api", BoardRules.where("api", null, bySession = false))
        assertEquals("~/work/api", BoardRules.where("api", "~/work/api", bySession = true))
        assertEquals("", BoardRules.where("api", null, bySession = true))
    }

    @Test
    fun showsProgressOnlyForWorkThatReportsIt() {
        assertEquals(40, BoardRules.progress(ProgramStatus("working", null, null, null, progress = 40), null))
        assertEquals(25, BoardRules.progress(status("working"), 25))
        assertNull(BoardRules.progress(status("working"), -1))
        assertNull(BoardRules.progress(status("working"), null))
        assertNull(BoardRules.progress(ProgramStatus("done", null, null, null, progress = 100), 100))
    }

    @Test
    fun opensOnTheMostPressingColumn() {
        assertEquals(BoardColumn.Attention, BoardRules.firstColumn(mapOf(BoardColumn.Attention to 1, BoardColumn.Running to 4)))
        assertEquals(BoardColumn.Running, BoardRules.firstColumn(mapOf(BoardColumn.Running to 2, BoardColumn.Idle to 3)))
        assertEquals(BoardColumn.Idle, BoardRules.firstColumn(mapOf(BoardColumn.Idle to 3)))
        assertEquals(BoardColumn.Attention, BoardRules.firstColumn(emptyMap()))
    }

    @Test
    fun saysHowLongAgoInAWord() {
        val minute = 60_000L
        val now = 10_000 * minute
        assertEquals("", BoardRules.ago(0, now))
        assertEquals("now", BoardRules.ago(now - 30_000, now))
        assertEquals("4 min", BoardRules.ago(now - 4 * minute - 5_000, now))
        assertEquals("2 h", BoardRules.ago(now - 150 * minute, now))
        assertEquals("3 d", BoardRules.ago(now - 75 * 60 * minute, now))
        assertEquals("now", BoardRules.ago(now + 5_000, now))
    }

    @Test
    fun putsTheLatestChangeFirstAndGroupsBySessionWhenAsked() {
        data class C(val name: String, val rank: Int, val since: Long)
        val cards = listOf(C("old-a", 1, 10), C("new-b", 2, 30), C("mid-a", 1, 20))
        assertEquals(listOf("new-b", "mid-a", "old-a"), BoardRules.sorted(cards, false, { it.rank }, { it.since }).map { it.name })
        assertEquals(listOf("mid-a", "old-a", "new-b"), BoardRules.sorted(cards, true, { it.rank }, { it.since }).map { it.name })
    }
}
