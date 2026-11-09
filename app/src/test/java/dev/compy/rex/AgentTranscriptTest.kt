package dev.compy.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptTest {
    private fun parse(vararg lines: String): List<ChatEntry> {
        val p = ClaudeTranscriptParser()
        return joinResults(lines.flatMapIndexed { i, line -> p.parse(line, "0+$i") })
    }

    @Test fun readsTypedMessagesAndReplies() {
        val entries = parse(
            """{"type":"user","message":{"role":"user","content":"fix the login page"}}""",
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"Look at auth"},{"type":"text","text":"On it."}]}}""",
        )
        assertEquals(
            listOf(
                ChatEntry.User("0+0", "fix the login page"),
                ChatEntry.Thinking("0+1:0", "Look at auth"),
                ChatEntry.Assistant("0+1:1", "On it."),
            ),
            entries,
        )
    }

    @Test fun putsResultsOnTheirCalls() {
        val entries = parse(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls -la","description":"List files"}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"a\nb"}]}}""",
        )
        val tool = entries.single() as ChatEntry.Tool
        assertEquals("Bash", tool.name)
        assertEquals("ls -la", tool.summary)
        assertEquals(ToolResult(ok = true, output = "a\nb"), tool.result)
    }

    @Test fun dropsResultsWhoseCallWasNotRead() {
        assertTrue(parse("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"old","content":"x"}]}}""").isEmpty())
    }

    @Test fun hidesHousekeepingAndSubagents() {
        val entries = parse(
            """{"type":"user","message":{"content":"<system-reminder>internal</system-reminder>"}}""",
            """{"type":"user","isMeta":true,"message":{"content":"meta"}}""",
            """{"type":"assistant","isSidechain":true,"message":{"content":[{"type":"text","text":"subagent"}]}}""",
            """{"type":"user","message":{"content":"<command-name>/model</command-name><command-args></command-args>"}}""",
            """not json""",
        )
        assertTrue(entries.isEmpty())
    }

    @Test fun showsSlashCommandsAsTyped() {
        val entries = parse("""{"type":"user","message":{"content":"<command-name>/review</command-name><command-args>auth.ts</command-args>"}}""")
        assertEquals(listOf(ChatEntry.User("0+0", "/review auth.ts")), entries)
    }

    @Test fun notesCompactionAndOmittedLines() {
        val entries = parse(
            """{"type":"system","subtype":"compact_boundary"}""",
            """{"type":"compy-omitted","bytes":9000000}""",
        )
        assertEquals(listOf("Conversation compacted", "A message too large to show was left out"), entries.map { (it as ChatEntry.Notice).text })
    }

    @Test fun readsMessagesTakenInMidTurn() {
        val entries = parse("""{"type":"attachment","attachment":{"type":"queued_command","origin":{"kind":"human"},"prompt":"also check signup"}}""")
        assertEquals(listOf(ChatEntry.User("0+0", "also check signup")), entries)
    }

    @Test fun keepsWhereImagesAre() {
        val entries = parse(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"r1","name":"Read","input":{"file_path":"/tmp/a.png"}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"r1","content":[{"type":"image","source":{"type":"base64","data":"","compy_blob":{"offset":4096,"index":1,"bytes":900},"media_type":"image/png"}}]}]}}""",
            """{"type":"user","message":{"content":[{"type":"text","text":"like this"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":""}}]}}""",
        )
        val tool = entries[0] as ChatEntry.Tool
        assertEquals(listOf(ChatImage(4096, 1, 900)), tool.result?.images)
        assertEquals("", tool.result?.output)
        // Sent by an older compy.lua, which didn't say where the image was.
        assertEquals(ChatEntry.User("0+2", "like this", listOf(null)), entries[1])
    }
}
