package dev.compy.rex

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Keeps one pane's agent conversation up to date by reading its transcript through the host's
 * compy_transcript action: the newest lines first, then whatever is added, and older lines on
 * request. It reads only while [run] runs, which the chat view does while it is on screen.
 */
class TranscriptFeed(
    private val connection: ServerConnection,
    private val sessionId: String,
    private val blockId: String,
) {
    sealed interface Status {
        data object Loading : Status
        data object Live : Status
        /** The host's Rex config doesn't load compy.lua. */
        data object NotInstalled : Status
        /** Nothing Compy knows how to read is running in the pane. */
        data object NoAgent : Status
        data class Unsupported(val agent: String) : Status
        data class Failed(val message: String) : Status
    }

    data class State(
        val status: Status = Status.Loading,
        val agent: String? = null,
        val entries: List<ChatEntry> = emptyList(),
        val hasOlder: Boolean = false,
        val loadingOlder: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()
    private var session: String? = null
    private var parser: TranscriptParser? = null
    private var start = 0L
    private var stop = 0L
    private var raw: List<ChatEntry> = emptyList()

    /** Reads until cancelled: every [POLL_MS], or sooner after [nudge]. */
    suspend fun run() {
        while (true) {
            poll()
            val until = System.currentTimeMillis() + POLL_MS
            while (System.currentTimeMillis() < until && !nudged) delay(100)
            nudged = false
        }
    }

    @Volatile private var nudged = false

    /** Reads again soon, such as right after sending a message. */
    fun nudge() {
        nudged = true
    }

    private suspend fun poll() = lock.withLock {
        // Twice at most: a read that finds a new session starts over from the newest lines.
        repeat(2) {
            val result = fetch(after = if (session != null) stop else null) ?: return@withLock
            val newSession = result["session"].str()
            val chunkStart = result["start"].long() ?: 0L
            // A new session (after /clear), or a transcript that shrank.
            if (session != null && (newSession != session || chunkStart != stop)) {
                reset()
                return@repeat
            }
            val fresh = session == null
            session = newSession
            if (fresh) start = chunkStart
            stop = result["stop"].long() ?: stop
            val entries = parse(result, chunkStart)
            raw = (if (fresh) entries else raw + entries).takeLast(MAX_ENTRIES)
            publish(Status.Live)
            return@withLock
        }
    }

    /** Reads the lines before the oldest ones loaded. */
    suspend fun loadOlder() {
        if (start <= 0 || _state.value.loadingOlder) return
        _state.update { it.copy(loadingOlder = true) }
        try {
            lock.withLock {
                val before = start
                val result = fetch(before = before) ?: return@withLock
                if (result["session"].str() != session) return@withLock
                start = result["start"].long() ?: 0L
                raw = parse(result, start) + raw
                publish(Status.Live)
            }
        } finally {
            _state.update { it.copy(loadingOlder = false) }
        }
    }

    private fun reset() {
        session = null
        start = 0
        stop = 0
        raw = emptyList()
    }

    private fun parse(result: JsonObject, chunkStart: Long): List<ChatEntry> {
        val p = parser ?: return emptyList()
        return result["lines"].arr().orEmpty().flatMapIndexed { i, line ->
            line.str()?.let { p.parse(it, "$chunkStart+$i") }.orEmpty()
        }
    }

    private fun publish(status: Status) {
        _state.update { it.copy(status = status, entries = joinResults(raw), hasOlder = start > 0) }
    }

    /** One read. Returns null, after setting the status, when there is nothing to show. */
    private suspend fun fetch(after: Long? = null, before: Long? = null): JsonObject? {
        val result = try {
            connection.readTranscript(sessionId, blockId, after, before)
        } catch (e: TimeoutCancellationException) {
            return failed("The host took too long to answer")
        } catch (e: RexError) {
            return if (e.code == "not_found") notInstalled() else failed(e.message ?: "Couldn't read the conversation")
        }
        if (result == null) return failed("The host sent no conversation")
        if ((result["available"] as? JsonPrimitive)?.booleanOrNull != true) {
            reset()
            _state.value = State(status = Status.NoAgent)
            return null
        }
        val agent = result["agent"].str().orEmpty()
        if (parser == null || _state.value.agent != agent) parser = transcriptParser(agent)
        if (parser == null) {
            _state.value = State(status = Status.Unsupported(agent), agent = agent)
            return null
        }
        _state.update { it.copy(agent = agent) }
        return result
    }

    private fun notInstalled(): JsonObject? {
        reset()
        _state.value = State(status = Status.NotInstalled)
        return null
    }

    private fun failed(message: String): JsonObject? {
        // Keep what was already read on screen; only say why it stopped updating.
        _state.update { it.copy(status = Status.Failed(message)) }
        return null
    }

    private companion object {
        const val POLL_MS = 1_500L
        const val MAX_ENTRIES = 4_000
    }
}
