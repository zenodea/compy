package dev.compy.rex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
@Serializable
sealed interface ChatEntry {
    val key: String

    @Serializable @SerialName("user")
    data class User(override val key: String, val text: String, val images: List<ChatImage?> = emptyList()) : ChatEntry

    @Serializable @SerialName("assistant")
    data class Assistant(override val key: String, val text: String) : ChatEntry

    @Serializable @SerialName("thinking")
    data class Thinking(override val key: String, val text: String) : ChatEntry

    /** A tool call, with its result once the transcript has it. */
    @Serializable @SerialName("tool")
    data class Tool(
        override val key: String,
        val callId: String,
        val name: String,
        val summary: String,
        val input: String?,
        val result: ToolResult? = null,
    ) : ChatEntry

    /** Something about the session itself, such as "Conversation compacted". */
    @Serializable @SerialName("notice")
    data class Notice(override val key: String, val text: String) : ChatEntry
}

@Serializable
data class ToolResult(val ok: Boolean, val output: String, val images: List<ChatImage?> = emptyList())

/**
 * An image in a transcript, which compy.lua leaves out of the line it sends and names by where it
 * is: the line's offset and the image's place in that line. Null where an older compy.lua sent the
 * line without saying where its image was.
 */
@Serializable
data class ChatImage(val offset: Long, val index: Int, val bytes: Int)

private fun imageOf(block: JsonObject): ChatImage? {
    // Claude Code keeps an image's data under `source`; Pi keeps it on the block itself.
    val ref = (block["source"].obj()?.get("compy_blob") ?: block["compy_blob"]).obj() ?: return null
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
@Serializable @SerialName("result")
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

private const val OMITTED = "A message too large to show was left out"

/**
 * Tells the second record of a message from the first. Codex writes what the agent says twice,
 * once as a finished item and once as the model's own record, one right after the other. The
 * second is known by carrying the first one's id, or by saying the same thing through the other
 * of the two records. The same words said again later are a new message, and are shown.
 */
private class Twice(private val limit: Int = 64) {
    private val ids = ArrayDeque<String>()
    private var lastText: String? = null
    private var lastSource: String? = null

    /** True when this record is the second of a pair, and so not to be shown again. */
    fun second(id: String?, text: String, source: String): Boolean {
        val byId = id != null && id in ids
        val byText = text == lastText && source != lastSource
        if (byId || byText) {
            // The pair is complete. The same words from here on are someone saying them again.
            lastText = null
            lastSource = null
            return true
        }
        if (id != null) {
            ids.addLast(id)
            if (ids.size > limit) ids.removeFirst()
        }
        lastText = text
        lastSource = source
        return false
    }
}

/**
 * Codex's `~/.codex/sessions/<date>/rollout-<time>-<id>.jsonl`. Each line has a `type` and a
 * `payload`. What the person and the agent said, and the commands that ran, come as finished
 * items (`event_msg` with `item_completed`); the model's own messages and tool calls come as
 * `response_item` records, which older versions wrote alone. Both are read, and a message that
 * arrives both ways is shown once.
 */
class CodexTranscriptParser : TranscriptParser {
    private val said = Twice()
    private var lastNotice: String? = null

    override fun parse(line: String, key: String): List<ChatEntry> {
        val record = runCatching { lenient.parseToJsonElement(line) }.getOrNull().obj() ?: return emptyList()
        val entries = when (record["type"].str()) {
            // A compaction is one very long record; the item that goes with it says it happened.
            "compy-omitted" -> if (record["was"].str() == "compacted") emptyList() else listOf(ChatEntry.Notice(key, OMITTED))
            "event_msg" -> event(record["payload"].obj() ?: return emptyList(), key)
            "response_item" -> response(record["payload"].obj() ?: return emptyList(), key)
            else -> emptyList()
        }
        // Compacting is recorded in more than one way, one after the other.
        return entries.filter { e ->
            if (e !is ChatEntry.Notice) {
                lastNotice = null
                true
            } else {
                val repeated = e.text == lastNotice
                lastNotice = e.text
                !repeated
            }
        }
    }

    private fun assistant(text: String?, id: String?, source: String, key: String): List<ChatEntry> {
        val shown = text?.trim().orEmpty()
        if (shown.isEmpty() || said.second(id, shown, source)) return emptyList()
        return listOf(ChatEntry.Assistant(key, clipText(shown)))
    }

    private fun user(text: String?, key: String): List<ChatEntry> {
        val shown = text?.trim().orEmpty()
        if (shown.isEmpty()) return emptyList()
        return listOf(ChatEntry.User(key, clipText(shown)))
    }

    private fun event(payload: JsonObject, key: String): List<ChatEntry> = when (payload["type"].str()) {
        "item_completed" -> item(payload["item"].obj() ?: return emptyList(), key)
        // How versions before items wrote the same things.
        "user_message" -> user(payload["message"].str(), key)
        "agent_message" -> assistant(payload["message"].str(), null, "item", key)
        "agent_reasoning" -> payload["text"].str()?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(ChatEntry.Thinking(key, clipText(it))) }.orEmpty()
        "turn_aborted" -> listOf(ChatEntry.Notice(key, "Interrupted"))
        else -> emptyList()
    }

    private fun item(item: JsonObject, key: String): List<ChatEntry> {
        val id = item["id"].str() ?: key
        return when (item["type"].str()) {
            "UserMessage" -> user(contentText(item["content"]), key)
            "AgentMessage" -> assistant(contentText(item["content"]), item["id"].str(), "item", key)
            "Reasoning" -> contentText(item["summary_text"]).trim().takeIf { it.isNotEmpty() }
                ?.let { listOf(ChatEntry.Thinking(key, clipText(it))) }.orEmpty()
            "CommandExecution" -> {
                val words = item["command"].arr().orEmpty().mapNotNull { it.str() }
                // A command run through a shell is the last word; the words before only start the shell.
                val command = if (words.size >= 3 && words[1].startsWith("-") && words[1].endsWith("c")) words.last() else words.joinToString(" ")
                val status = item["exit_code"].int()
                listOf(
                    ChatEntry.Tool(
                        key, id, "Shell", firstLineOf(command), command.takeIf { it.trim() != firstLineOf(command) }?.let { clipText(it, INPUT_LIMIT) },
                        // A command still running has no exit code yet.
                        item["aggregated_output"].str()?.let { ToolResult(ok = status == null || status == 0, output = clipOutput(it)) },
                    ),
                )
            }
            "FileChange" -> {
                val files = item["changes"].obj()?.keys.orEmpty().map { it.substringAfterLast('/') }
                val failed = item["status"].str() == "failed"
                listOf(
                    ChatEntry.Tool(
                        key, id, "Edit", files.joinToString(", "), null,
                        ToolResult(ok = !failed, output = clipOutput(listOfNotNull(item["stdout"].str(), item["stderr"].str()).filter { it.isNotBlank() }.joinToString("\n"))),
                    ),
                )
            }
            "ImageView" -> listOf(ChatEntry.Tool(key, id, "View image", item["path"].str().orEmpty().substringAfterLast('/'), null, ToolResult(true, "")))
            "Extension" -> if (item["kind"].str() == "web.search") {
                listOf(ChatEntry.Tool(key, id, "Web search", firstLineOf(item["query"].str().orEmpty()), null, ToolResult(true, "")))
            } else {
                emptyList()
            }
            "ContextCompaction" -> listOf(ChatEntry.Notice(key, "Conversation compacted"))
            else -> emptyList()
        }
    }

    private fun response(payload: JsonObject, key: String): List<ChatEntry> = when (payload["type"].str()) {
        // Only the agent's own words. What goes in as the user's turn is mostly context added for the model.
        "message" -> if (payload["role"].str() == "assistant") assistant(contentText(payload["content"]), payload["id"].str(), "response", key) else emptyList()
        "reasoning" -> contentText(payload["summary"]).trim().takeIf { it.isNotEmpty() }
            ?.let { listOf(ChatEntry.Thinking(key, clipText(it))) }.orEmpty()
        "function_call" -> {
            val arguments = payload["arguments"].str()?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } ?: payload["arguments"]
            listOf(ChatEntry.Tool(key, payload["call_id"].str().orEmpty(), payload["name"].str() ?: "tool", toolSummary(arguments), toolInput(arguments)))
        }
        // "exec" is the wrapper every command goes through; the commands themselves come as items.
        "custom_tool_call" -> if (payload["name"].str() == "exec") {
            emptyList()
        } else {
            listOf(ChatEntry.Tool(key, payload["call_id"].str().orEmpty(), payload["name"].str() ?: "tool", toolSummary(payload["input"]), toolInput(payload["input"])))
        }
        "function_call_output", "custom_tool_call_output" -> listOf(ResultEntry(key, payload["call_id"].str().orEmpty(), output(payload["output"])))
        else -> emptyList()
    }

    /** A call's output, which is text, a list of text blocks, or JSON holding the text and an exit code. */
    private fun output(value: JsonElement?): ToolResult {
        val text = value.str() ?: return ToolResult(true, clipOutput(contentText(value)))
        val wrapped = text.takeIf { it.startsWith("{") }?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull().obj() }
        val inner = wrapped?.get("output").str() ?: return ToolResult(true, clipOutput(text))
        val status = wrapped?.get("metadata").obj()?.get("exit_code").int()
        return ToolResult(ok = status == null || status == 0, output = clipOutput(inner))
    }
}

private fun firstLineOf(text: String) = firstLine(text)

/**
 * Pi's `~/.pi/agent/sessions/--<folder>--/<time>_<id>.jsonl`. Messages are lines of type
 * `message` with a role: the person, the assistant (text, thinking and tool calls as content
 * blocks), or a tool's result. Other lines record the session, model changes and the like.
 */
class PiTranscriptParser : TranscriptParser {
    override fun parse(line: String, key: String): List<ChatEntry> {
        val record = runCatching { lenient.parseToJsonElement(line) }.getOrNull().obj() ?: return emptyList()
        return when (record["type"].str()) {
            "compy-omitted" -> listOf(ChatEntry.Notice(key, OMITTED))
            "compaction" -> listOf(ChatEntry.Notice(key, "Conversation compacted"))
            // What an extension put into the conversation for the person to see.
            "custom_message" -> if ((record["display"] as? JsonPrimitive)?.booleanOrNull == true) {
                contentText(record["content"]).trim().takeIf { it.isNotEmpty() }?.let { listOf(ChatEntry.Notice(key, firstLine(it))) }.orEmpty()
            } else {
                emptyList()
            }
            "message" -> message(record["message"].obj() ?: return emptyList(), key)
            else -> emptyList()
        }
    }

    private fun message(message: JsonObject, key: String): List<ChatEntry> = when (message["role"].str()) {
        "user" -> {
            val text = contentText(message["content"].str()?.let(::JsonPrimitive) ?: textBlocks(message["content"])).trim()
            val images = contentImages(message["content"])
            if (text.isEmpty() && images.isEmpty()) emptyList() else listOf(ChatEntry.User(key, clipText(text), images))
        }
        "assistant" -> message["content"].arr().orEmpty().mapIndexedNotNull { i, block ->
            val b = block.obj() ?: return@mapIndexedNotNull null
            val k = "$key:$i"
            when (b["type"].str()) {
                "text" -> b["text"].str()?.trim()?.takeIf { it.isNotEmpty() }?.let { ChatEntry.Assistant(k, clipText(it)) }
                "thinking" -> b["thinking"].str()?.trim()?.takeIf { it.isNotEmpty() }?.let { ChatEntry.Thinking(k, clipText(it)) }
                "toolCall" -> ChatEntry.Tool(k, b["id"].str().orEmpty(), b["name"].str() ?: "tool", toolSummary(b["arguments"]), toolInput(b["arguments"]))
                else -> null
            }
        }
        "toolResult" -> listOf(
            ResultEntry(
                key,
                message["toolCallId"].str().orEmpty(),
                ToolResult(
                    ok = (message["isError"] as? JsonPrimitive)?.booleanOrNull != true,
                    output = clipOutput(contentText(textBlocks(message["content"]))),
                    images = contentImages(message["content"]),
                ),
            ),
        )
        else -> emptyList()
    }

    /** Only the text blocks, since an image block has no text of its own. */
    private fun textBlocks(content: JsonElement?): JsonElement =
        JsonArray(content.arr().orEmpty().filter { it.obj()?.get("type").str() == "text" })
}

private val chatJson = Json { ignoreUnknownKeys = true }

/** A conversation as bytes to keep on the phone, and [decodeChat] to read it back. */
fun encodeChat(entries: List<ChatEntry>): ByteArray =
    chatJson.encodeToString(ListSerializer(ChatEntry.serializer()), entries).toByteArray()

fun decodeChat(bytes: ByteArray): List<ChatEntry>? =
    runCatching { chatJson.decodeFromString(ListSerializer(ChatEntry.serializer()), bytes.toString(Charsets.UTF_8)) }.getOrNull()

/** How much text an entry holds, for keeping a saved conversation within a size. */
private fun ChatEntry.weight(): Int = 64 + when (this) {
    is ChatEntry.User -> text.length
    is ChatEntry.Assistant -> text.length
    is ChatEntry.Thinking -> text.length
    is ChatEntry.Tool -> name.length + summary.length + (input?.length ?: 0) + (result?.output?.length ?: 0)
    is ChatEntry.Notice -> text.length
    is ResultEntry -> result.output.length
}

/** The newest entries that fit in [limit] characters, so a very long conversation keeps its end. */
fun newestWithin(entries: List<ChatEntry>, limit: Int): List<ChatEntry> {
    var total = 0
    var from = entries.size
    while (from > 0) {
        val next = total + entries[from - 1].weight()
        if (next > limit && from < entries.size) break
        total = next
        from--
    }
    return entries.subList(from, entries.size)
}

/** The words of an entry that a search looks through. */
fun ChatEntry.searchText(): String = when (this) {
    is ChatEntry.User -> text
    is ChatEntry.Assistant -> text
    is ChatEntry.Thinking -> text
    is ChatEntry.Tool -> listOfNotNull(name, summary, input, result?.output).joinToString("\n")
    is ChatEntry.Notice -> text
    is ResultEntry -> ""
}

/** Which of [entries] hold [query], by position, ignoring case. None for an empty query. */
fun chatMatches(entries: List<ChatEntry>, query: String): List<Int> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return emptyList()
    return entries.indices.filter { entries[it].searchText().contains(wanted, ignoreCase = true) }
}

/** The agents whose conversations Compy can show as a chat. */
val CHAT_AGENT_NAMES = setOf("claude", "codex", "pi")

/** The parser for an agent the transcript action reported, or null for one Compy can't read yet. */
fun transcriptParser(agent: String): TranscriptParser? = when (agent) {
    "claude" -> ClaudeTranscriptParser()
    "codex" -> CodexTranscriptParser()
    "pi" -> PiTranscriptParser()
    else -> null
}
