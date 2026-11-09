package dev.compy.rex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One item of an agent's conversation, read from the transcript its harness writes rather than
 * from its screen. [key] is unique within a session and stays the same as more is read.
 */
sealed interface ChatEntry {
    val key: String

    data class User(override val key: String, val text: String, val images: List<ChatImage?> = emptyList()) : ChatEntry
    data class Assistant(override val key: String, val text: String) : ChatEntry
    data class Thinking(override val key: String, val text: String) : ChatEntry

    /** A tool call, with its result once the transcript has it. */
    data class Tool(
        override val key: String,
        val callId: String,
        val name: String,
        val summary: String,
        val input: String?,
        val result: ToolResult? = null,
    ) : ChatEntry

    /** Something about the session itself, such as "Conversation compacted". */
    data class Notice(override val key: String, val text: String) : ChatEntry
}

data class ToolResult(val ok: Boolean, val output: String, val images: List<ChatImage?> = emptyList())

/**
 * An image in a transcript, which compy.lua leaves out of the line it sends and names by where it
 * is: the line's offset and the image's place in that line. Null where an older compy.lua sent the
 * line without saying where its image was.
 */
data class ChatImage(val offset: Long, val index: Int, val bytes: Int)

private fun imageOf(block: JsonObject): ChatImage? {
    val ref = block["source"].obj()?.get("compy_blob").obj() ?: return null
    return ChatImage(ref["offset"].long() ?: return null, ref["index"].int() ?: return null, ref["bytes"].int() ?: 0)
}

/** The images in a content field that is a list of blocks. */
internal fun contentImages(content: JsonElement?): List<ChatImage?> =
    content.arr().orEmpty().mapNotNull { it.obj() }.filter { it["type"].str() == "image" }.map(::imageOf)

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private const val TEXT_LIMIT = 24_000
private const val INPUT_LIMIT = 2_000
private const val OUTPUT_HEAD = 3_000
private const val OUTPUT_TAIL = 1_000

internal fun clipText(text: String, limit: Int = TEXT_LIMIT): String =
    if (text.length <= limit) text else "${text.take(limit)}\n… (${text.length - limit} more characters)"

/** Tool output: the start and the end, which is where errors and summaries are. */
internal fun clipOutput(text: String): String {
    if (text.length <= OUTPUT_HEAD + OUTPUT_TAIL) return text
    val hidden = text.length - OUTPUT_HEAD - OUTPUT_TAIL
    return "${text.take(OUTPUT_HEAD)}\n… ($hidden characters) …\n${text.takeLast(OUTPUT_TAIL)}"
}

private val SUMMARY_FIELDS = listOf(
    "command", "cmd", "file_path", "path", "filePath", "pattern", "url", "query", "description", "prompt",
    "task", "title", "skill", "message", "name", "id",
)

private fun firstLine(text: String): String {
    val line = text.trim().lineSequence().firstOrNull().orEmpty()
    return if (line.length > 160) line.take(157) + "…" else line
}

/** One line saying what a tool call does, from its input. */
internal fun toolSummary(input: JsonElement?): String {
    if (input is JsonPrimitive) return input.str()?.let(::firstLine).orEmpty()
    val o = input.obj() ?: return ""
    o["questions"].arr()?.firstNotNullOfOrNull { it.obj()?.get("question").str() }?.let { return firstLine(it) }
    for (field in SUMMARY_FIELDS) {
        val value = o[field]
        value.str()?.takeIf { it.isNotBlank() }?.let { return firstLine(it) }
        value.arr()?.mapNotNull { it.str() }?.takeIf { it.isNotEmpty() && it.size == value.arr()?.size }
            ?.let { return firstLine(it.joinToString(" ")) }
    }
    return ""
}

private val pretty = Json { prettyPrint = true }

/** The full input, when the one-line summary doesn't already say it all. */
internal fun toolInput(input: JsonElement?): String? {
    if (input == null) return null
    val text = input.str() ?: pretty.encodeToString(JsonElement.serializer(), input)
    if (text.isBlank() || text == "{}" || text.trim() == toolSummary(input)) return null
    return clipText(text, INPUT_LIMIT)
}

/** Text out of a content field that is a string or a list of `{type:"text", text}` blocks. */
internal fun contentText(content: JsonElement?): String {
    content.str()?.let { return it }
    return content.arr().orEmpty().mapNotNull { block ->
        val b = block.obj() ?: return@mapNotNull null
        b["text"].str()
    }.filter { it.isNotEmpty() }.joinToString("\n")
}

/** Reads one transcript line into entries. Parsers may keep state across lines. */
interface TranscriptParser {
    fun parse(line: String, key: String): List<ChatEntry>
}

/** Housekeeping Claude Code writes into user messages, not something the person typed. */
private val HIDDEN_USER_TEXT =
    Regex("""^\s*<(local-command-stdout|local-command-stderr|local-command-caveat|task-notification|system-reminder)>""")
private val COMMAND_NAME = Regex("""<command-name>([^<]*)</command-name>""")
private val COMMAND_ARGS = Regex("""<command-args>([\s\S]*?)</command-args>""")
private val MENU_COMMANDS = setOf("/model", "/effort")

private fun claudeUserText(text: String): String? {
    if (HIDDEN_USER_TEXT.containsMatchIn(text)) return null
    COMMAND_NAME.find(text)?.let { m ->
        val name = m.groupValues[1].trim()
        val args = COMMAND_ARGS.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (args.isEmpty() && name in MENU_COMMANDS) return null
        return listOf(name, args).filter { it.isNotEmpty() }.joinToString(" ")
    }
    return text.trim().ifEmpty { null }
}

/**
 * Claude Code's `~/.claude/projects/<dir>/<session>.jsonl`: one record per line. Assistant
 * messages come one content block per record, and tool results come back as user records.
 */
class ClaudeTranscriptParser : TranscriptParser {
    override fun parse(line: String, key: String): List<ChatEntry> {
        val record = runCatching { lenient.parseToJsonElement(line) }.getOrNull().obj() ?: return emptyList()
        val type = record["type"].str()
        if (type == "compy-omitted") return listOf(ChatEntry.Notice(key, "A message too large to show was left out"))
        if ((record["isSidechain"] as? JsonPrimitive)?.booleanOrNull == true) return emptyList()
        if (type == "system") {
            return if (record["subtype"].str() == "compact_boundary") listOf(ChatEntry.Notice(key, "Conversation compacted")) else emptyList()
        }
        // A message sent while it worked that it took in mid-turn: recorded only as an attachment.
        if (type == "attachment") {
            val attached = record["attachment"].obj() ?: return emptyList()
            if (attached["type"].str() != "queued_command" || attached["origin"].obj()?.get("kind").str() != "human") return emptyList()
            val prompt = attached["prompt"]
            val raw = if (prompt is JsonArray) contentText(prompt) else prompt.str().orEmpty()
            val images = contentImages(prompt)
            val text = claudeUserText(raw)
            if (text == null && images.isEmpty()) return emptyList()
            return listOf(ChatEntry.User(key, clipText(text.orEmpty()), images))
        }
        val message = record["message"].obj() ?: return emptyList()
        return when (type) {
            "user" -> user(record, message, key)
            "assistant" -> assistant(message, key)
            else -> emptyList()
        }
    }

    private fun user(record: JsonObject, message: JsonObject, key: String): List<ChatEntry> {
        val meta = (record["isMeta"] as? JsonPrimitive)?.booleanOrNull == true ||
            (record["isCompactSummary"] as? JsonPrimitive)?.booleanOrNull == true
        if (meta) return emptyList()
        val content = message["content"]
        content.str()?.let { text -> return claudeUserText(text)?.let { listOf(ChatEntry.User(key, clipText(it))) }.orEmpty() }
        val out = mutableListOf<ChatEntry>()
        val typed = mutableListOf<String>()
        val images = mutableListOf<ChatImage?>()
        content.arr().orEmpty().forEachIndexed { i, block ->
            val b = block.obj() ?: return@forEachIndexed
            when (b["type"].str()) {
                "tool_result" -> out += ResultEntry(
                    "$key:$i",
                    b["tool_use_id"].str().orEmpty(),
                    ToolResult(
                        ok = (b["is_error"] as? JsonPrimitive)?.booleanOrNull != true,
                        output = clipOutput(contentText(b["content"])),
                        images = contentImages(b["content"]),
                    ),
                )
                "text" -> b["text"].str()?.let(::claudeUserText)?.let { typed += it }
                "image" -> images += imageOf(b)
            }
        }
        if (typed.isNotEmpty() || images.isNotEmpty()) out.add(0, ChatEntry.User(key, clipText(typed.joinToString("\n")), images))
        return out
    }

    private fun assistant(message: JsonObject, key: String): List<ChatEntry> =
        message["content"].arr().orEmpty().mapIndexedNotNull { i, block ->
            val b = block.obj() ?: return@mapIndexedNotNull null
            val k = "$key:$i"
            when (b["type"].str()) {
                "text" -> b["text"].str()?.trim()?.takeIf { it.isNotEmpty() }?.let { ChatEntry.Assistant(k, clipText(it)) }
                "thinking" -> b["thinking"].str()?.trim()?.takeIf { it.isNotEmpty() }?.let { ChatEntry.Thinking(k, clipText(it)) }
                "tool_use" -> ChatEntry.Tool(
                    key = k,
                    callId = b["id"].str().orEmpty(),
                    name = b["name"].str() ?: "tool",
                    summary = toolSummary(b["input"]),
                    input = toolInput(b["input"]),
                )
                else -> null
            }
        }
}

/**
 * A tool's result before [joinResults] puts it on its call. Results whose call wasn't loaded
 * (it came before the oldest line read) are dropped there, so none reach the screen.
 */
data class ResultEntry(override val key: String, val callId: String, val result: ToolResult) : ChatEntry

/** Puts results on their calls, keeping the order the agent wrote the calls in. */
fun joinResults(entries: List<ChatEntry>): List<ChatEntry> {
    val results = entries.filterIsInstance<ResultEntry>().associateBy { it.callId }
    return entries.mapNotNull { e ->
        when (e) {
            is ChatEntry.Tool -> results[e.callId]?.let { e.copy(result = it.result) } ?: e
            is ResultEntry -> null
            else -> e
        }
    }
}

/** The parser for an agent the transcript action reported, or null for one Compy can't read yet. */
fun transcriptParser(agent: String): TranscriptParser? = when (agent) {
    "claude" -> ClaudeTranscriptParser()
    else -> null
}
