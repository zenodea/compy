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

class CodexTranscriptTest {
    private fun read(vararg lines: String): List<ChatEntry> {
        val parser = CodexTranscriptParser()
        return joinResults(lines.flatMapIndexed { i, line -> parser.parse(line, "k$i") })
    }

    private fun item(body: String) = """{"timestamp":"t","ordinal":1,"type":"event_msg","payload":{"type":"item_completed","item":$body}}"""
    private fun response(body: String) = """{"timestamp":"t","ordinal":1,"type":"response_item","payload":$body}"""

    @Test
    fun readsWhatWasSaidOnce() {
        val entries = read(
            // Context added for the model goes in as the user's turn, and isn't something the person typed.
            response("""{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions"}]}"""),
            item("""{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"fix the build"}]}"""),
            item("""{"type":"AgentMessage","id":"m1","content":[{"type":"Text","text":"Done."}],"phase":"final_answer"}"""),
            response("""{"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"Done."}]}"""),
        )
        assertEquals(listOf(ChatEntry.User("k1", "fix the build"), ChatEntry.Assistant("k2", "Done.")), entries)
    }

    @Test
    fun showsACommandWithItsOutput() {
        val entries = read(
            // The wrapper every command goes through is left out; the command itself is an item.
            response("""{"type":"custom_tool_call","name":"exec","call_id":"c1","input":"const r = await run()"}"""),
            response("""{"type":"custom_tool_call_output","call_id":"c1","output":[{"type":"input_text","text":"Script completed"}]}"""),
            item("""{"type":"CommandExecution","id":"e1","command":["/bin/zsh","-lc","ls -la"],"aggregated_output":"total 0","exit_code":0}"""),
            item("""{"type":"CommandExecution","id":"e2","command":["/bin/zsh","-lc","false"],"aggregated_output":"","exit_code":1}"""),
        )
        assertEquals(2, entries.size)
        val first = entries[0] as ChatEntry.Tool
        assertEquals("Shell", first.name)
        assertEquals("ls -la", first.summary)
        assertEquals(ToolResult(true, "total 0"), first.result)
        assertEquals(false, (entries[1] as ChatEntry.Tool).result?.ok)
    }

    @Test
    fun joinsAFunctionCallToItsOutput() {
        val entries = read(
            response("""{"type":"function_call","name":"shell","call_id":"c1","arguments":"{\"command\":[\"bash\",\"-lc\",\"make test\"]}"}"""),
            response("""{"type":"function_call_output","call_id":"c1","output":"{\"output\":\"2 failed\",\"metadata\":{\"exit_code\":2}}"}"""),
        )
        val tool = entries.single() as ChatEntry.Tool
        assertEquals("bash -lc make test", tool.summary)
        assertEquals(ToolResult(false, "2 failed"), tool.result)
    }

    @Test
    fun readsOlderVersionsThatWroteEventsAlone() {
        val entries = read(
            """{"type":"event_msg","payload":{"type":"user_message","message":"hello"}}""",
            """{"type":"event_msg","payload":{"type":"agent_reasoning","text":"Thinking it over"}}""",
            """{"type":"event_msg","payload":{"type":"agent_message","message":"Hi!"}}""",
            response("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hi!"}]}"""),
        )
        assertEquals(listOf(ChatEntry.User("k0", "hello"), ChatEntry.Thinking("k1", "Thinking it over"), ChatEntry.Assistant("k2", "Hi!")), entries)
    }

    @Test
    fun saysOnceThatItCompactedAndSkipsTheLongRecord() {
        val entries = read(
            item("""{"type":"ContextCompaction","id":"x"}"""),
            """{"type":"compy-omitted","bytes":2000000,"was":"compacted"}""",
            item("""{"type":"ContextCompaction","id":"y"}"""),
            item("""{"type":"FileChange","id":"f","changes":{"/a/b/main.rs":{"type":"add"}},"status":"completed","stdout":"Success"}"""),
        )
        assertEquals("Conversation compacted", (entries[0] as ChatEntry.Notice).text)
        assertEquals(2, entries.size)
        assertEquals("main.rs", (entries[1] as ChatEntry.Tool).summary)
    }
}

class PiTranscriptTest {
    private fun read(vararg lines: String): List<ChatEntry> {
        val parser = PiTranscriptParser()
        return joinResults(lines.flatMapIndexed { i, line -> parser.parse(line, "k$i") })
    }

    @Test
    fun readsAConversationWithATool() {
        val entries = read(
            """{"type":"session","version":3,"id":"s","timestamp":"t","cwd":"/tmp"}""",
            """{"type":"message","id":"1","parentId":null,"message":{"role":"system","content":"","sections":{}}}""",
            """{"type":"message","id":"2","parentId":"1","message":{"role":"user","content":[{"type":"text","text":"list the files"}]}}""",
            """{"type":"message","id":"3","parentId":"2","message":{"role":"assistant","content":[{"type":"thinking","thinking":"","thinkingSignature":"x"},{"type":"text","text":"Looking."},{"type":"toolCall","id":"call_1|fc_1","name":"bash","arguments":{"command":"ls"}}]}}""",
            """{"type":"message","id":"4","parentId":"3","message":{"role":"toolResult","toolCallId":"call_1|fc_1","toolName":"bash","content":[{"type":"text","text":"a.txt"}],"isError":false}}""",
            """{"type":"model_change","id":"5","parentId":"4","provider":"p","modelId":"m"}""",
        )
        assertEquals(3, entries.size)
        assertEquals(ChatEntry.User("k2", "list the files"), entries[0])
        assertEquals(ChatEntry.Assistant("k3:1", "Looking."), entries[1])
        val tool = entries[2] as ChatEntry.Tool
        assertEquals("bash", tool.name)
        assertEquals("ls", tool.summary)
        assertEquals(ToolResult(true, "a.txt"), tool.result)
    }

    @Test
    fun marksAFailedToolAndFindsItsPicture() {
        val entries = read(
            """{"type":"message","message":{"role":"assistant","content":[{"type":"toolCall","id":"c","name":"read","arguments":{"path":"shot.png"}}]}}""",
            """{"type":"message","message":{"role":"toolResult","toolCallId":"c","content":[{"type":"text","text":"Read image"},{"type":"image","data":"","compy_blob":{"offset":120,"index":1,"bytes":4000},"mimeType":"image/png"}],"isError":true}}""",
        )
        val result = (entries.single() as ChatEntry.Tool).result!!
        assertEquals(false, result.ok)
        assertEquals("Read image", result.output)
        assertEquals(listOf(ChatImage(120, 1, 4000)), result.images)
    }

    @Test
    fun showsCompactionAndWhatExtensionsDisplay() {
        val entries = read(
            """{"type":"compaction","id":"1","summary":"…"}""",
            """{"type":"custom_message","customType":"note","content":"Subagent finished\nwith details","display":true}""",
            """{"type":"custom_message","customType":"note","content":"hidden","display":false}""",
            """{"type":"custom","customType":"state","data":{}}""",
        )
        assertEquals(listOf("Conversation compacted", "Subagent finished"), entries.map { (it as ChatEntry.Notice).text })
    }
}
