package dev.compy.system

import dev.compy.rex.ProgramStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentSummaryTest {
    private fun agent(name: String, state: String, since: Long = 1, percent: Int? = null, kind: String? = null, message: String? = null) =
        AgentSummary.Agent(name, ProgramStatus(state, kind, null, message, since = since), percent, "srv", "ses", "blk-$name")

    @Test
    fun rowsNameTheMostPressingAgentsFirst() {
        val rows = AgentSummary.rows(
            listOf(
                agent("docs", "done"),
                agent("api", "working", message = "Running tests"),
                agent("infra", "error"),
                agent("web", "blocked", kind = "permission"),
                agent("idle", "idle"),
            ),
            limit = 5,
        )
        assertEquals(listOf("web needs your permission", "infra failed", "api: Running tests"), rows.map { it.text })
        assertEquals("blk-web", rows.first().block)
    }

    @Test
    fun rowsStopAtTheRoomThereIs() {
        val many = (1..9).map { agent("a$it", "working", since = it.toLong()) }
        assertEquals(listOf("a9 is working", "a8 is working"), AgentSummary.rows(many, 2).map { it.text })
    }

    @Test
    fun theNoticeIsQuietWithNothingToSay() {
        val notice = AgentSummary.notice(listOf(agent("docs", "done")))
        assertEquals("Watching your Rex servers", notice.title)
        assertNull(notice.percent)
    }

    @Test
    fun theNoticeSaysWhoNeedsYouBeforeWhoIsWorking() {
        val notice = AgentSummary.notice(listOf(agent("api", "working", percent = 40), agent("web", "blocked", kind = "question")))
        assertEquals("1 agent needs you", notice.title)
        assertEquals("web has a question", notice.text)
        assertNull(notice.percent)
    }

    @Test
    fun theNoticeShowsABarForWorkThatReportsIt() {
        val one = AgentSummary.notice(listOf(agent("api", "working", percent = 40, message = "Running tests")))
        assertEquals(AgentSummary.Notice("1 agent working", "api: Running tests", 40), one)
        // Of several, the bar is the latest one's that says how far along it is.
        val several = AgentSummary.notice(
            listOf(agent("api", "working", since = 5, percent = 40), agent("web", "working", since = 9), agent("docs", "working", since = 7, percent = 120)),
        )
        assertEquals(AgentSummary.Notice("3 agents working", "docs is working", 100), several)
        // Work with no known end has no bar.
        assertNull(AgentSummary.notice(listOf(agent("api", "working", percent = -1))).percent)
    }
}
