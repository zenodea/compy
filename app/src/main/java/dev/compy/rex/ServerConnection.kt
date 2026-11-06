package dev.compy.rex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Offline(val reason: String) : ConnectionState
}

/** Something a program wants the person to know, with where to find it. */
data class Alert(val serverId: String, val sessionId: String, val blockId: String, val text: String)

/** A saved server's address and name. */
data class ServerConfig(val id: String, val label: String, val url: String)

/**
 * Everything Compy knows about one Rex server, kept current from its event stream, plus the calls
 * that act on it.
 */
class ServerConnection(
    val config: ServerConfig,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val clientLabel: String,
    private val clientVersion: String,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _views = MutableStateFlow<Map<String, SessionView>>(emptyMap())
    val views: StateFlow<Map<String, SessionView>> = _views.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, ProgramStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ProgramStatus>> = _statuses.asStateFlow()

    /** Blocks that rang a bell, sent a notification or finished since they were last looked at. */
    private val _attention = MutableStateFlow<Set<String>>(emptySet())
    val attention: StateFlow<Set<String>> = _attention.asStateFlow()

    private val _titles = MutableStateFlow<Map<String, String>>(emptyMap())
    val titles: StateFlow<Map<String, String>> = _titles.asStateFlow()

    /** Set when the server's API revision isn't the one Compy was written against. */
    private val _versionWarning = MutableStateFlow<String?>(null)
    val versionWarning: StateFlow<String?> = _versionWarning.asStateFlow()

    private val _clientId = MutableStateFlow<String?>(null)
    val clientId: StateFlow<String?> = _clientId.asStateFlow()

    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    /** Things worth telling someone about while the app is open. */
    private val _alerts = MutableSharedFlow<Alert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<Alert> = _alerts.asSharedFlow()

    /** Text a program copied with OSC 52. */
    private val _clipboard = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val clipboard: SharedFlow<String> = _clipboard.asSharedFlow()

    /** Set by the app, so large copies aren't downloaded when they couldn't be used. */
    var acceptsClipboard: () -> Boolean = { true }

    /** Blocks that keep running outside every window, by session. */
    private val _detached = MutableStateFlow<Map<String, List<BlockInfo>>>(emptyMap())
    val detached: StateFlow<Map<String, List<BlockInfo>>> = _detached.asStateFlow()

    /** The colors this host's terminals use, as last reported by any of them. */
    private val _hostTheme = MutableStateFlow<TerminalColors?>(null)
    val hostTheme: StateFlow<TerminalColors?> = _hostTheme.asStateFlow()

    /** What each block is running: the program's own name or its foreground process. */
    private val _programs = MutableStateFlow<Map<String, String>>(emptyMap())
    val programs: StateFlow<Map<String, String>> = _programs.asStateFlow()

    /** Each block's current folder name. */
    private val _folders = MutableStateFlow<Map<String, String>>(emptyMap())
    val folders: StateFlow<Map<String, String>> = _folders.asStateFlow()

    /** Progress programs report separately from their status, in percent, or -1 when unknown. */
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    /** Sessions a Rex action asked this client to show. */
    private val _selections = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val selections: SharedFlow<String> = _selections.asSharedFlow()

    /** Blocks that just rang the terminal bell. */
    private val _bells = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val bells: SharedFlow<String> = _bells.asSharedFlow()

    @Volatile private var control: RexControl? = null
    private var reconnectToken: String? = null
    private var connectJob: Job? = null
    /** The control connection being opened or in use. Closes reported by any other are stale. */
    @Volatile private var attempt: RexControl? = null
    private val connectLock = Any()
    private var retries = 0
    @Volatile private var wanted = false

    private val attached = ConcurrentHashMap.newKeySet<String>()
    private val attachLock = Mutex()
    private val terminals = ConcurrentHashMap<String, TerminalSession>()
    private val viewFetches = ConcurrentHashMap<String, Job>()
    private val owned = ConcurrentHashMap<String, String>()

    fun connect() {
        wanted = true
        if (connectJob?.isActive == true || control != null) return
        startConnecting()
    }

    /** Called when the app returns or the network changes, to skip any retry delay. */
    fun retryNow() {
        wanted = true
        if (control != null) return
        retries = 0
        connectJob?.cancel()
        startConnecting()
    }

    /**
     * Leaves the server without ending the client, so a return within a minute resumes it and
     * everything it was attached to.
     */
    fun pause() {
        wanted = false
        connectJob?.cancel()
        val c = control
        control = null
        attempt = null
        terminals.values.forEach { it.pause() }
        c?.abandon()
        _state.value = ConnectionState.Offline("Paused")
    }

    /**
     * Stops streaming terminal output but stays connected for events, which is all alerts need.
     * Terminals resume from where they were when [resumeStreams] is called.
     */
    fun pauseStreams() {
        terminals.values.forEach { it.pause() }
    }

    fun resumeStreams() {
        terminals.values.forEach { it.reconnect() }
    }

    /** Sends a reply typed in a notification to a pane, followed by Enter. */
    suspend fun sendReply(sessionId: String, blockId: String, text: String) {
        val c = control ?: throw RexError("disconnected", "Not connected to ${config.label}", 0)
        ensureAttached(sessionId)
        c.call("block.event", ids(sessionId, blockId) {
            put("event", buildJsonObject {
                put("type", "paste")
                put("payload", buildJsonObject { put("text", text) })
            })
        })
        c.call("client.key", ids(sessionId, blockId) { put("key", RexKey.ENTER.toJson()) })
    }

    /** Ends the client on the server and forgets all local state. */
    fun shutdown() {
        wanted = false
        connectJob?.cancel()
        terminals.values.forEach { it.stop() }
        terminals.clear()
        attempt = null
        control?.close()
        control = null
    }

    private fun startConnecting(): Unit = synchronized(connectLock) {
        connectJob?.cancel()
        // The close callback needs the connection it belongs to, which doesn't exist yet.
        val self = arrayOfNulls<RexControl>(1)
        val c = RexControl(
            http, config.url, clientLabel, clientVersion,
            onEvent = ::handleEvent,
            onClosed = { reason, code -> self[0]?.let { handleClosed(it, reason, code) } },
        )
        self[0] = c
        attempt = c
        connectJob = scope.launch {
            _state.value = ConnectionState.Connecting
            try {
                val welcome = c.open(reconnectToken)
                synchronized(connectLock) {
                    // A newer attempt took over while this one waited.
                    if (attempt !== c) throw CancellationException("Replaced by a newer connection")
                    control = c
                }
                reconnectToken = welcome.reconnectToken
                _clientId.value = welcome.clientId
                _versionWarning.value = if (welcome.apiRevision != RexControl.API_REVISION) {
                    "This server uses Rex API revision ${welcome.apiRevision}. Compy was written for ${RexControl.API_REVISION}, so some things may not work."
                } else {
                    null
                }
                if (!welcome.resumed) attached.clear()
                bootstrap(c)
                retries = 0
                _state.value = ConnectionState.Connected
                val live = _sessions.value.map { it.id }.toSet()
                terminals.entries.toList().forEach { (block, terminal) ->
                    if (terminal.sessionId !in live) {
                        // The session ended while we were away, such as after a server restart.
                        terminals.remove(block)
                        terminal.stop()
                    } else {
                        // One pane failing shouldn't take the whole connection down with it.
                        runCatching { ensureAttached(terminal.sessionId) }
                        terminal.reconnect()
                    }
                }
            } catch (e: Exception) {
                c.abandon()
                if (e is CancellationException && e !is TimeoutCancellationException) {
                    // Replaced or stopped on purpose; whoever did that already decided what's next.
                    synchronized(connectLock) { if (control === c) control = null }
                    throw e
                }
                handleClosed(c, if (e is TimeoutCancellationException) SLOW_ANSWER else e.message ?: "Connection failed", 0)
            }
        }
    }

    /**
     * Schedules one reconnect after [from] closes. A failed attempt reports its close both from
     * the socket and from the attempt itself, and connections that were already replaced can close
     * later, so only the first report from the current connection counts. Before this check every
     * failure started two retry loops, and a few minutes offline left dozens of clients connected.
     */
    private fun handleClosed(from: RexControl, reason: String, code: Int): Unit = synchronized(connectLock) {
        if (from !== attempt) return
        attempt = null
        control = null
        if (!wanted) return
        _state.value = ConnectionState.Offline(reason)
        val wait = minOf(30_000L, 1_000L shl minOf(retries, 5))
        retries++
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(wait)
            if (wanted && control == null) startConnecting()
        }
    }

    private suspend fun bootstrap(c: RexControl) {
        val result = c.call(
            "session.bootstrap",
            buildJsonObject {
                put("event_scope", "all")
                put("attach_session_ids", buildJsonArray { attached.forEach { add(JsonPrimitive(it)) } })
            },
            timeoutMs = 30_000,
        ).obj()
        _serverVersion.value = result?.get("server_status").obj()?.get("rex_version").str()
        val sessions = parseSessions(result?.get("attach_all").obj()?.get("sessions"))
        _sessions.value = sessions
        // Events are lost while disconnected, so read every block's status again.
        statusRead.clear()
        val keep = sessions.map { it.id }.toSet()
        _views.update { it.filterKeys(keep::contains) }
        _detached.update { it.filterKeys(keep::contains) }
        for (session in sessions) refreshView(session.id)
    }

    private var learnJob: Job? = null

    /**
     * Theme colors only arrive on a terminal stream, and not every terminal reports them, so look
     * through a few until one does. Streams opened just for this are closed afterwards.
     */
    fun learnHostTheme() {
        if (_hostTheme.value != null || learnJob?.isActive == true) return
        learnJob = scope.launch {
            val blocks = _views.value.values.flatMap { view ->
                view.windows.flatMap { window ->
                    window.blocks.filter { it.creator.endsWith(".terminal") }.map { view.sessionId to it.id }
                }
            }
            for ((sessionId, blockId) in blocks.take(MAX_THEME_PROBES)) {
                if (_hostTheme.value != null) return@launch
                val temporary = !terminals.containsKey(blockId)
                terminal(sessionId, blockId)
                var waited = 0L
                while (_hostTheme.value == null && waited < THEME_PROBE_WAIT_MS) {
                    delay(100)
                    waited += 100
                }
                if (temporary) releaseTerminal(blockId)
            }
        }
    }

    /** Sets the colors remembered from last time, until the host reports its current ones. */
    fun seedHostTheme(colors: TerminalColors) {
        if (_hostTheme.value == null) _hostTheme.value = colors
    }

    private fun refreshView(sessionId: String, ifNewerThan: Long = -1) {
        val known = _views.value[sessionId]
        if (known != null && ifNewerThan in 0..known.revision) return
        val running = viewFetches[sessionId]
        if (running?.isActive == true) {
            // A fetch is in flight, but it may predate this change, so fetch again afterwards.
            viewFetches[sessionId] = scope.launch {
                running.join()
                fetchView(sessionId)
            }
            return
        }
        viewFetches[sessionId] = scope.launch { fetchView(sessionId) }
    }

    private suspend fun fetchView(sessionId: String) {
        val view = try {
            parseView(control?.call("session.view", buildJsonObject { put("session_id", sessionId) })) ?: return
        } catch (_: Exception) {
            return
        }
        _views.update { current ->
            val old = current[sessionId]
            if (old != null && old.revision > view.revision) current else current + (sessionId to view)
        }
        for (window in view.windows) for (block in window.blocks) readDetails(sessionId, block)
        readDetached(sessionId)
        // The server's list only names sessions, so keep labels current from the view too.
        _sessions.update { list ->
            list.map { if (it.id == sessionId && view.label.isNotEmpty()) it.copy(label = view.label) else it }
        }
    }

    private fun readDetails(sessionId: String, block: BlockInfo) {
        if (!block.creator.endsWith(".terminal") || block.id in statusRead) return
        statusRead += block.id
        scope.launch {
            readStatus(sessionId, block.id)
            readTitle(sessionId, block.id)
            readProcess(sessionId, block.id)
            readFolder(sessionId, block.id)
        }
    }

    private suspend fun readDetached(sessionId: String) {
        val result = runCatching {
            control?.call("session.list_blocks", buildJsonObject { put("session_id", sessionId) })
        }.getOrNull().obj() ?: return
        val blocks = parseDetachedBlocks(result["blocks"])
        _detached.update { if (blocks.isEmpty()) it - sessionId else it + (sessionId to blocks) }
        blocks.forEach { readDetails(sessionId, it) }
    }

    /** Reads the detached list again, since making a detached block doesn't change the layout. */
    fun refreshDetached(sessionId: String) {
        scope.launch { readDetached(sessionId) }
    }

    private val statusRead = ConcurrentHashMap.newKeySet<String>()

    private suspend fun readStatus(sessionId: String, blockId: String) {
        try {
            val result = control?.call(
                "com.superlogical.terminal.program_status",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("block_id", blockId)
                },
            ).obj()
            val records = result?.get("records").arr().orEmpty().mapNotNull { parseStatusRecord(it) }
            setStatus(blockId, mostUrgent(records))
        } catch (_: Exception) {
            statusRead -= blockId
        }
    }

    private suspend fun readFolder(sessionId: String, blockId: String) {
        val result = runCatching {
            control?.call(
                "com.superlogical.terminal.pwd",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("block_id", blockId)
                },
            ).obj()
        }.getOrNull()
        setFolder(blockId, result?.get("pwd").str())
    }

    private fun setFolder(blockId: String, pwd: String?) {
        val name = folderName(pwd) ?: return
        _folders.update { it + (blockId to name) }
    }

    private suspend fun readProcess(sessionId: String, blockId: String) {
        val result = runCatching {
            control?.call(
                "com.superlogical.terminal.process",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("block_id", blockId)
                },
            ).obj()
        }.getOrNull()
        setProgram(blockId, result?.get("foreground").obj())
    }

    /** An interpreter's script says more than its name, so node running claude counts as claude. */
    private fun setProgram(blockId: String, foreground: JsonObject?) {
        val name = foreground?.get("name").str() ?: return
        val script = foreground?.get("script").str()?.substringAfterLast('/')
        _programs.update { it + (blockId to listOfNotNull(name, script).joinToString("/")) }
    }

    private suspend fun readTitle(sessionId: String, blockId: String) {
        val result = runCatching {
            control?.call(
                "com.superlogical.terminal.title",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("block_id", blockId)
                },
            ).obj()
        }.getOrNull()
        val title = result?.get("title").str()?.takeIf { it.isNotBlank() } ?: return
        _titles.update { it + (blockId to title) }
    }

    private fun setStatus(blockId: String, status: ProgramStatus?) {
        _statuses.update { if (status == null) it - blockId else it + (blockId to status) }
    }

    private fun handleEvent(type: String, payload: JsonObject?) {
        when (type) {
            "session_created" -> {
                val session = payload?.get("session").obj() ?: return
                val id = session["session_id"].str() ?: return
                _sessions.update { list ->
                    if (list.any { it.id == id }) list else list + SessionSummary(id, session["label"].str().orEmpty())
                }
                refreshView(id)
            }
            "session_destroyed" -> {
                val id = payload?.get("session_id").str() ?: return
                _sessions.update { list -> list.filterNot { it.id == id } }
                _views.update { it - id }
                _detached.update { it - id }
                attached -= id
            }
            "session_moved" -> scope.launch {
                val result = runCatching { control?.call("session.list") }.getOrNull().obj()
                val sessions = parseSessions(result?.get("sessions"))
                if (sessions.isNotEmpty()) _sessions.value = sessions
            }
            "client_perform_requested" -> handlePerform(payload ?: return)
            // Compy never lists other servers with an action, so this shouldn't arrive, but the
            // action would wait for an answer if it did.
            "client_call_requested" -> reply(payload?.get("call_id").str() ?: return, "not_supported", "Compy can't call other servers")
            "session_event" -> {
                val sessionId = payload?.get("session_id").str() ?: return
                val event = payload?.get("event").obj() ?: return
                handleSessionEvent(sessionId, event["type"].str().orEmpty(), event["payload"].obj())
            }
        }
    }

    /**
     * Rex actions can ask the client that started them to do something, and then wait for the
     * answer, so every request gets one, even if it's only that Compy can't do it.
     */
    private fun handlePerform(payload: JsonObject) {
        val callId = payload["call_id"].str() ?: return
        val args = payload["args"].obj()
        when (payload["action"].str()) {
            "session.select" -> {
                val sessionId = args?.get("session_id").str() ?: payload["session_id"].str()
                val windowId = args?.get("window_id").str()
                if (sessionId != null) {
                    _selections.tryEmit(sessionId)
                    if (windowId != null) focusWindow(sessionId, windowId)
                }
                reply(callId, null, null)
            }
            else -> reply(callId, "not_supported", "Compy can't do that yet")
        }
    }

    private fun reply(callId: String, code: String?, message: String?) {
        control?.fire(
            "client.reply",
            buildJsonObject {
                put("call_id", callId)
                if (code == null) {
                    put("result", JsonNull)
                } else {
                    put("error", buildJsonObject {
                        put("code", code)
                        put("message", message.orEmpty())
                    })
                }
            },
        )
    }

    private fun handleSessionEvent(sessionId: String, type: String, payload: JsonObject?) {
        when (type) {
            "session_view_changed" -> refreshView(sessionId, payload?.get("revision").long() ?: -1)
            "session_label_changed" -> {
                val label = payload?.get("label").str() ?: return
                _sessions.update { list -> list.map { if (it.id == sessionId) it.copy(label = label) else it } }
            }
            "block_closed" -> {
                val id = payload?.get("block_id").str() ?: return
                _statuses.update { it - id }
                _attention.update { it - id }
                _programs.update { it - id }
                _folders.update { it - id }
                _progress.update { it - id }
                _detached.update { map ->
                    val left = map[sessionId]?.filterNot { it.id == id } ?: return@update map
                    if (left.isEmpty()) map - sessionId else map + (sessionId to left)
                }
                terminals.remove(id)?.stop()
            }
            "block_created" -> refreshDetached(sessionId)
            "block_event" -> handleBlockEvent(sessionId, payload ?: return)
        }
    }

    private fun handleBlockEvent(sessionId: String, payload: JsonObject) {
        val block = payload["block_id"].str() ?: return
        val fields = payload["payload"].obj()
        when (payload["name"].str()) {
            "program_status_changed" -> {
                val status = parseStatusRecord(fields?.get("record")) ?: return
                val previous = _statuses.value[block]
                setStatus(block, status)
                if (status.isDone || status.isBlocked || status.isError) markAttention(block)
                if (status.isBlocked && previous?.isBlocked != true) {
                    val who = status.app ?: "A program"
                    val what = when (status.kind) {
                        "permission" -> "needs your permission"
                        "question" -> "has a question"
                        "auth" -> "needs a password"
                        else -> "needs you"
                    }
                    _alerts.tryEmit(Alert(config.id, sessionId, block, "$who $what"))
                }
            }
            "program_status_removed" -> setStatus(block, null)
            "process_changed" -> setProgram(block, fields?.get("foreground").obj())
            "bell" -> {
                markAttention(block)
                _bells.tryEmit(block)
            }
            "pwd_changed" -> setFolder(block, fields?.get("pwd").str())
            "progress_report" -> {
                val percent = fields?.get("progress").int()
                when (fields?.get("state").str()) {
                    "set" -> if (percent != null) _progress.update { it + (block to percent) }
                    "indeterminate" -> _progress.update { it + (block to -1) }
                    // Paused or failed work still has a position worth showing.
                    "pause", "error" -> if (percent != null) _progress.update { it + (block to percent) }
                    else -> _progress.update { it - block }
                }
            }
            "desktop_notification" -> {
                markAttention(block)
                val text = fields?.get("body").str() ?: fields?.get("title").str()
                if (!text.isNullOrBlank()) _alerts.tryEmit(Alert(config.id, sessionId, block, text))
            }
            "clipboard_written" -> {
                val text = fields?.get("text").str()
                if (text != null) {
                    _clipboard.tryEmit(text)
                } else if ((fields?.get("bytes").long() ?: 0L) > 0L && acceptsClipboard()) {
                    // Too large for the event, so the server keeps it briefly to be read in parts.
                    scope.launch { readLargeCopy(sessionId, block, fields?.get("id").long(), fields?.get("bytes").long() ?: 0L) }
                }
            }
            "title_changed" -> {
                val title = fields?.get("title").str() ?: return
                _titles.update { it + (block to title) }
            }
        }
    }

    private suspend fun readLargeCopy(sessionId: String, blockId: String, id: Long?, bytes: Long) {
        if (bytes > MAX_CLIPBOARD_BYTES) {
            _alerts.tryEmit(Alert(config.id, sessionId, blockId, "That copy is too large for Android's clipboard (${bytes / 1024} KB)"))
            return
        }
        val text = runCatching { readCopy(sessionId, blockId, id) }.getOrNull()
        if (text != null) {
            _clipboard.tryEmit(text)
        } else {
            _alerts.tryEmit(Alert(config.id, sessionId, blockId, "Couldn't copy that text from the server"))
        }
    }

    private suspend fun readCopy(sessionId: String, blockId: String, id: Long?): String? {
        val text = StringBuilder()
        var copy = id
        var offset = 0L
        while (true) {
            val c = control ?: return null
            val part = c.call(
                "com.superlogical.terminal.clipboard_read",
                ids(sessionId, blockId) {
                    put("args", buildJsonObject {
                        if (copy != null) put("id", copy)
                        put("offset", offset)
                        put("max_bytes", CLIPBOARD_PART_BYTES)
                    })
                },
            ).obj() ?: return null
            val data = part["data"].str().orEmpty()
            text.append(data)
            copy = part["id"].long() ?: copy
            if (part["eof"].bool() == true) return text.toString()
            // A part with nothing in it would repeat forever.
            val size = data.toByteArray(Charsets.UTF_8).size
            if (size == 0) return null
            offset += size
        }
    }

    private fun markAttention(block: String) {
        _attention.update { it + block }
    }

    fun clearAttention(block: String) {
        if (block in _attention.value) _attention.update { it - block }
    }

    private suspend fun ensureAttached(sessionId: String) {
        attachLock.withLock {
            if (sessionId in attached) return
            val c = control ?: throw RexError("disconnected", "Not connected", 0)
            val result = c.call("session.attach", buildJsonObject { put("session_id", sessionId) }).obj()
            attached += sessionId
            parseView(result?.get("view"))?.let { view ->
                _views.update { current ->
                    val old = current[sessionId]
                    if (old != null && old.revision > view.revision) current else current + (sessionId to view)
                }
            }
        }
    }

    /** The live terminal for a block, connecting its stream on first use. */
    fun terminal(sessionId: String, blockId: String): TerminalSession =
        terminals.computeIfAbsent(blockId) {
            TerminalSession(
                sessionId, blockId, http, config.url, scope,
                requestTicket = {
                    ensureAttached(sessionId)
                    val result = control?.call(
                        "data.connect",
                        buildJsonObject {
                            put("session_id", sessionId)
                            put("block_id", blockId)
                            put("data_ref_id", "terminal")
                            put("subprotocol", TerminalSession.SUBPROTOCOL)
                        },
                    ).obj()
                    result?.get("ticket").str() ?: throw RexError("disconnected", "No ticket", 0)
                },
                readScreen = {
                    runCatching {
                        control?.call(
                            "com.superlogical.terminal.format",
                            buildJsonObject {
                                put("session_id", sessionId)
                                put("block_id", blockId)
                                put("args", buildJsonObject { put("format", "vt") })
                            },
                        ).obj()?.get("content").str()
                    }.getOrNull()
                },
                onHostTheme = { colors ->
                    _hostTheme.value = colors
                    terminals.values.forEach { it.hostColorsChanged() }
                },
                hostFallback = { _hostTheme.value },
            ).also { it.start() }
        }

    /** Stops streaming a block that's no longer on screen. */
    fun releaseTerminal(blockId: String) {
        terminals.remove(blockId)?.stop()
    }

    /** Pastes into a block that may not be on screen, attaching to its session first. */
    fun pasteInto(sessionId: String, blockId: String, text: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        ensureAttached(sessionId)
        c.call("block.event", ids(sessionId, blockId) {
            put("event", buildJsonObject {
                put("type", "paste")
                put("payload", buildJsonObject { put("text", text) })
            })
        })
    }

    private fun ids(sessionId: String, blockId: String, extra: JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("session_id", sessionId)
            put("block_id", blockId)
            extra()
        }

    private fun fire(method: String, payload: JsonObject) {
        control?.fire(method, payload)
    }

    /** Runs a call that changes something, reporting failure through [onError]. */
    fun act(onError: (String) -> Unit = {}, block: suspend (RexControl) -> Unit) {
        scope.launch {
            val c = control
            if (c == null) {
                onError("Not connected to ${config.label}")
                return@launch
            }
            try {
                block(c)
            } catch (e: TimeoutCancellationException) {
                onError(SLOW_ANSWER)
            } catch (e: Exception) {
                onError(e.message ?: "That didn't work")
            }
        }
    }

    // Input. These go out in order without waiting, so typing stays in order.

    fun sendKey(sessionId: String, blockId: String, key: RexKey) {
        fire("client.key", ids(sessionId, blockId) { put("key", key.toJson()) })
    }

    fun paste(sessionId: String, blockId: String, text: String) {
        fire("block.event", ids(sessionId, blockId) {
            put("event", buildJsonObject {
                put("type", "paste")
                put("payload", buildJsonObject { put("text", text) })
            })
        })
    }

    fun sendFocus(sessionId: String, blockId: String, focused: Boolean) {
        fire("block.event", ids(sessionId, blockId) {
            put("event", buildJsonObject {
                put("type", "focus")
                put("payload", buildJsonObject { put("action", if (focused) "in" else "out") })
            })
        })
    }

    fun sendMouse(sessionId: String, blockId: String, payload: JsonObject) {
        fire("block.event", ids(sessionId, blockId) {
            put("event", buildJsonObject {
                put("type", "mouse")
                put("payload", payload)
            })
        })
    }

    fun sendWheel(sessionId: String, blockId: String, payload: JsonObject) {
        fire("block.event", ids(sessionId, blockId) {
            put("event", buildJsonObject {
                put("type", "wheel")
                put("payload", payload)
            })
        })
    }

    // Size and ownership.

    fun requestSize(sessionId: String, blockId: String, cols: Int, rows: Int, cellW: Int, cellH: Int) {
        act { c ->
            ensureAttached(sessionId)
            c.call(
                "com.superlogical.terminal.resize",
                ids(sessionId, blockId) {
                    put("args", buildJsonObject {
                        put("columns", cols)
                        put("rows", rows)
                        put("cell_width_px", cellW.coerceIn(1, 65535))
                        put("cell_height_px", cellH.coerceIn(1, 65535))
                    })
                },
            )
        }
    }

    /** Fits a block to this screen: asks for the size, then takes ownership so it applies now. */
    fun fitBlock(sessionId: String, blockId: String, cols: Int, rows: Int, cellW: Int, cellH: Int) {
        owned[blockId] = sessionId
        act { c ->
            ensureAttached(sessionId)
            c.call(
                "com.superlogical.terminal.resize",
                ids(sessionId, blockId) {
                    put("args", buildJsonObject {
                        put("columns", cols)
                        put("rows", rows)
                        put("cell_width_px", cellW.coerceIn(1, 65535))
                        put("cell_height_px", cellH.coerceIn(1, 65535))
                    })
                },
            )
            c.call("session.own_block", blockIdList(sessionId, listOf(blockId)) { put("mode", "take") })
        }
    }

    fun takeOwnership(sessionId: String, blockIds: List<String>) {
        if (blockIds.isEmpty()) return
        blockIds.forEach { owned[it] = sessionId }
        act { c ->
            ensureAttached(sessionId)
            c.call("session.own_block", blockIdList(sessionId, blockIds) { put("mode", "take") })
        }
    }

    fun releaseOwnership(sessionId: String, blockIds: List<String>) {
        if (blockIds.isEmpty()) return
        blockIds.forEach { owned.remove(it) }
        act { c -> c.call("session.disown_block", blockIdList(sessionId, blockIds)) }
    }

    /** Gives back every block this client claimed, used when the app leaves the foreground. */
    suspend fun releaseAllOwnership() {
        val c = control ?: return
        owned.entries.groupBy({ it.value }, { it.key }).forEach { (sessionId, blocks) ->
            runCatching { c.call("session.disown_block", blockIdList(sessionId, blocks)) }
        }
        owned.clear()
    }

    private fun blockIdList(sessionId: String, blockIds: List<String>, extra: JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("session_id", sessionId)
            putJsonArray("block_ids") { blockIds.forEach { add(JsonPrimitive(it)) } }
            extra()
        }

    // Sessions, windows and layout.

    private val shellLayout = buildJsonObject {
        put("block", buildJsonObject { put("flavor", "com.superlogical.terminal.shell") })
    }

    suspend fun createSession(label: String?): String? {
        val c = control ?: throw RexError("disconnected", "Not connected to ${config.label}", 0)
        val result = c.call(
            "session.create",
            buildJsonObject {
                if (!label.isNullOrBlank()) put("label", label.trim())
                put("initial_windows", JsonArray(listOf(buildJsonObject { put("layout", shellLayout) })))
            },
        ).obj()
        return result?.get("session_id").str()
    }

    fun destroySession(sessionId: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.destroy", buildJsonObject { put("session_id", sessionId) })
    }

    fun renameSession(sessionId: String, label: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.set_label", buildJsonObject {
            put("session_id", sessionId)
            put("label", label)
        })
    }

    fun renameWindow(sessionId: String, windowId: String, label: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.set_window_label", buildJsonObject {
            put("session_id", sessionId)
            put("window_id", windowId)
            put("label", label)
        })
    }

    fun newWindow(sessionId: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        ensureAttached(sessionId)
        c.call("session.new_window", buildJsonObject {
            put("session_id", sessionId)
            put("layout", shellLayout)
        })
    }

    fun closeWindow(sessionId: String, windowId: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.close_window", buildJsonObject {
            put("session_id", sessionId)
            put("window_id", windowId)
        })
    }

    fun focusWindow(sessionId: String, windowId: String) = act { c ->
        ensureAttached(sessionId)
        c.call("session.focus_window", buildJsonObject {
            put("session_id", sessionId)
            put("window_id", windowId)
        })
    }

    fun focusBlock(sessionId: String, blockId: String) = act { c ->
        ensureAttached(sessionId)
        c.call("session.focus_block", ids(sessionId, blockId))
    }

    /**
     * Shows a block wherever it is, restoring its floating layer if that's minimized. Returns false
     * if it isn't in any window, which only the list of detached panes can show.
     */
    suspend fun revealBlock(sessionId: String, blockId: String): Boolean {
        val c = control ?: throw RexError("disconnected", "Not connected to ${config.label}", 0)
        ensureAttached(sessionId)
        val view = parseView(c.call("session.view", buildJsonObject { put("session_id", sessionId) }))
        val window = view?.windowOf(blockId) ?: return false
        val layer = window.layerOf(blockId)
        if (layer?.floating == true) {
            c.call("session.set_layer_state", layerIds(sessionId, layer.id) {
                put("state", "normal")
                put("focus", true)
            })
        }
        c.call("session.focus_block", ids(sessionId, blockId))
        return true
    }

    private fun layerIds(sessionId: String, layerId: String, extra: JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("session_id", sessionId)
            put("layer_id", layerId)
            extra()
        }

    private fun boundsArray(r: FractionRect) = buildJsonArray { layerBounds(r).forEach { add(JsonPrimitive(it)) } }

    fun newFloatingPane(sessionId: String, windowId: String, bounds: FractionRect, onError: (String) -> Unit = {}) = act(onError) { c ->
        ensureAttached(sessionId)
        c.call("session.new_layer", buildJsonObject {
            put("session_id", sessionId)
            put("window_id", windowId)
            put("bounds", boundsArray(bounds))
            put("layout", shellLayout)
            put("focus", true)
        })
    }

    fun floatBlock(sessionId: String, blockId: String, bounds: FractionRect, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.float_block", ids(sessionId, blockId) { put("bounds", boundsArray(bounds)) })
    }

    fun setLayerMinimized(sessionId: String, layerId: String, minimized: Boolean, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.set_layer_state", layerIds(sessionId, layerId) {
            put("state", if (minimized) "minimized" else "normal")
            if (!minimized) put("focus", true)
        })
    }

    /** Brings a floating layer to the front and gives it focus. */
    fun focusLayer(sessionId: String, layerId: String, onError: (String) -> Unit = {}) = setLayerMinimized(sessionId, layerId, false, onError)

    fun raiseLayer(sessionId: String, layerId: String) = act { c ->
        c.call("session.raise_layer", layerIds(sessionId, layerId))
    }

    fun resizeLayer(sessionId: String, layerId: String, bounds: FractionRect, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.resize_layer", layerIds(sessionId, layerId) { put("bounds", boundsArray(bounds)) })
    }

    fun closeLayer(sessionId: String, layerId: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.close_layer", layerIds(sessionId, layerId))
    }

    fun detachBlock(sessionId: String, blockId: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.detach_block", ids(sessionId, blockId))
    }

    /** Places a detached block in a window of its own. A window can't be made empty, so it starts with a shell. */
    fun placeInNewWindow(sessionId: String, blockId: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        ensureAttached(sessionId)
        val created = c.call("session.new_window", buildJsonObject {
            put("session_id", sessionId)
            put("layout", shellLayout)
        }).obj()
        val shell = created?.get("block_ids").arr()?.firstOrNull().str() ?: throw RexError("bad_reply", "The server didn't make the window", 0)
        try {
            c.call("session.move_block", ids(sessionId, blockId) { put("anchor_block_id", shell) })
        } finally {
            c.call("block.close", ids(sessionId, shell))
        }
        c.call("session.focus_block", ids(sessionId, blockId))
    }

    fun newSplit(sessionId: String, anchorBlockId: String, horizontal: Boolean, before: Boolean = false, onError: (String) -> Unit = {}) = act(onError) { c ->
        ensureAttached(sessionId)
        c.call("session.new_split", buildJsonObject {
            put("session_id", sessionId)
            put("anchor_id", anchorBlockId)
            put("direction", if (horizontal) "horizontal" else "vertical")
            put("side", if (before) "before" else "after")
            put("layout", shellLayout)
            put("focus", true)
        })
    }

    fun closeBlock(sessionId: String, blockId: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("block.close", ids(sessionId, blockId))
    }

    fun zoomBlock(sessionId: String, blockId: String, zoom: Boolean) = act { c ->
        c.call("session.zoom_block", ids(sessionId, blockId) { put("zoom", zoom) })
    }

    fun resizePane(sessionId: String, horizontal: Boolean, beforeId: String, afterId: String, ratio: Float) = act { c ->
        c.call("session.resize_pane", buildJsonObject {
            put("session_id", sessionId)
            put("direction", if (horizontal) "horizontal" else "vertical")
            put("before_block_id", beforeId)
            put("after_block_id", afterId)
            put("ratio", ratio.coerceIn(0.05f, 0.95f).toDouble())
        })
    }

    fun moveBlock(sessionId: String, blockId: String, anchorId: String, horizontal: Boolean, before: Boolean, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.move_block", ids(sessionId, blockId) {
            put("anchor_block_id", anchorId)
            put("direction", if (horizontal) "horizontal" else "vertical")
            put("side", if (before) "before" else "after")
        })
    }

    fun swapBlocks(sessionId: String, a: String, b: String, onError: (String) -> Unit = {}) = act(onError) { c ->
        c.call("session.swap_blocks", buildJsonObject {
            put("session_id", sessionId)
            put("block_id", a)
            put("other_block_id", b)
        })
    }

    suspend fun keymap(): RexKeymap = parseKeymap(control?.call("client.keymap"))

    fun runAction(
        sessionId: String,
        blockId: String?,
        action: String,
        args: JsonElement?,
        onError: (String) -> Unit = {},
    ) = act(onError) { c ->
        ensureAttached(sessionId)
        c.call(
            "client.action",
            buildJsonObject {
                put("action", action)
                put("session_id", sessionId)
                if (blockId != null) put("block_id", blockId)
                if (args != null) put("args", args)
                put("origin", "palette")
            },
            timeoutMs = 30_000,
        )
    }

    suspend fun listHosts(): List<HostEntry> {
        val result = control?.call("hosts.list").obj()
        return result?.get("hosts").arr().orEmpty().mapNotNull { h ->
            val o = h.obj() ?: return@mapNotNull null
            val endpoint = o["endpoints"].arr().orEmpty().mapNotNull { it.str() }
                .firstOrNull { !it.startsWith("unix:") && !it.startsWith("pipe:") } ?: return@mapNotNull null
            HostEntry(o["id"].str().orEmpty(), o["label"].str().orEmpty(), endpoint)
        }
    }

    private companion object {
        const val SLOW_ANSWER = "The server took too long to answer"
        const val MAX_THEME_PROBES = 6
        const val THEME_PROBE_WAIT_MS = 1_500L
        const val CLIPBOARD_PART_BYTES = 262_144

        // Android sends clipboard text in one Binder call, which is limited to about 1 MB and
        // holds each character in two bytes.
        const val MAX_CLIPBOARD_BYTES = 400_000L
    }
}
