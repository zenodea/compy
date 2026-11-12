package dev.compy.rex

import android.util.Base64
import dev.compy.term.GhosttyTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/** Something a terminal view can show: a live pane, or one that was saved to read later. */
interface TerminalSource {
    val terminal: GhosttyTerminal?
    fun addListener(l: TerminalSession.Listener)
    fun removeListener(l: TerminalSession.Listener)
}

/** A terminal read back from a saved pane. It has no server behind it and never changes by itself. */
class SavedTerminal(override val terminal: GhosttyTerminal) : TerminalSource {
    private val listeners = CopyOnWriteArrayList<TerminalSession.Listener>()

    override fun addListener(l: TerminalSession.Listener) {
        listeners += l
        l.onTerminalChanged()
    }

    override fun removeListener(l: TerminalSession.Listener) {
        listeners -= l
    }

    /** Fits the text to the view showing it, which rewraps its lines. */
    fun resize(cols: Int, rows: Int, cellW: Int, cellH: Int) {
        terminal.resize(cols, rows, cellW, cellH)
        listeners.forEach { it.onTerminalChanged() }
    }

    fun close() = terminal.close()
}

/**
 * The live contents of one terminal block: its stream from the server and the local engine that
 * interprets it. Views attach and detach; the stream keeps the engine current between them.
 */
class TerminalSession(
    val sessionId: String,
    val blockId: String,
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val scope: CoroutineScope,
    /** Issues a new ticket for this block. */
    private val requestTicket: suspend () -> String,
    /** Reads the screen as escape sequences, for when the snapshot can't be decoded. */
    private val readScreen: suspend () -> String?,
    /** Reports the colors the host gave this terminal. */
    private val onHostTheme: (TerminalColors) -> Unit = {},
    /** The host's usual colors, for a terminal that doesn't report any of its own. */
    private val hostFallback: () -> TerminalColors? = { null },
) : TerminalSource {
    interface Listener {
        /** New output arrived, or anything else that changes what's drawn. */
        fun onTerminalChanged()
    }

    enum class State { Connecting, Live, Reconnecting, Closed }

    @Volatile override var terminal: GhosttyTerminal? = null
        private set
    @Volatile var state = State.Connecting
        private set
    /** The size the server says this terminal is. */
    @Volatile var cols = 0
        private set
    @Volatile var rows = 0
        private set
    /** True after a snapshot failed to decode and the screen came from a plain fetch instead. */
    @Volatile var usedFallback = false
        private set
    @Volatile var childExited = false
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var socket: WebSocket? = null
    private var parser: TerminalStreamParser? = null
    private var token: ByteArray? = null
    private var nonce: ULong = 0u
    private var received = 0L
    private var generation = 0
    private var retryJob: Job? = null
    private var retries = 0
    private var stopped = false
    @Volatile private var theme: TerminalColors? = null
    @Volatile private var localTheme: TerminalColors? = null

    override fun addListener(l: Listener) {
        listeners += l
        l.onTerminalChanged()
    }

    override fun removeListener(l: Listener) {
        listeners -= l
    }

    /** The session's state as a flow, for showing connection problems on the pane. */
    val liveState = kotlinx.coroutines.flow.MutableStateFlow(State.Connecting)
    val fallbackInUse = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun changed() {
        liveState.value = state
        fallbackInUse.value = usedFallback
        listeners.forEach { it.onTerminalChanged() }
    }

    @Synchronized
    fun start() {
        if (socket != null || stopped) return
        openNew()
    }

    /** Connects again after the network or app returned, keeping the stream when possible. */
    @Synchronized
    fun reconnect() {
        if (stopped || state == State.Closed) return
        retryJob?.cancel()
        socket?.cancel()
        socket = null
        if (token != null) openResume() else openNew()
    }

    @Synchronized
    fun stop() {
        stopped = true
        retryJob?.cancel()
        socket?.close(1000, null)
        socket = null
        state = State.Closed
        terminal?.close()
        terminal = null
        changed()
    }

    /** Drops the socket without ending the stream, so it can resume within the server's window. */
    @Synchronized
    fun pause() {
        // Cancelling a socket reports a failure. A new generation makes that report stale, so it
        // doesn't schedule a retry that would reopen the stream half a second later.
        generation++
        retryJob?.cancel()
        socket?.cancel()
        socket = null
        if (state != State.Closed) state = State.Reconnecting
    }

    /** Applies the colors chosen in Compy, or null to follow what the server says. */
    fun setLocalTheme(colors: TerminalColors?) {
        localTheme = colors
        applyColors()
        changed()
    }

    /** Call when the host's usual colors change. */
    fun hostColorsChanged() {
        applyColors()
        changed()
    }

    private fun applyColors() {
        val t = terminal ?: return
        val c = localTheme ?: theme ?: hostFallback()
        t.setColors(c?.foreground ?: 0, c?.background ?: 0, c?.cursor ?: 0, c?.palette)
    }

    @Synchronized
    private fun openNew() {
        val gen = ++generation
        state = if (terminal == null) State.Connecting else State.Reconnecting
        scope.launch {
            val ticket = try {
                requestTicket()
            } catch (e: Exception) {
                scheduleRetry(gen, fresh = true)
                return@launch
            }
            synchronized(this@TerminalSession) {
                if (gen != generation || stopped) return@launch
                parser = null
                received = 0
                token = null
                val hello = ByteArray(17)
                hello[0] = HELLO
                try {
                    Base64.decode(ticket, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).copyInto(hello, 1)
                } catch (e: RuntimeException) {
                    // Not base64, or longer than a ticket: ask for another rather than crash.
                    scheduleRetry(gen, fresh = true)
                    return@launch
                }
                connect(gen, hello)
            }
        }
    }

    @Synchronized
    private fun openResume() {
        val t = token ?: return openNew()
        val gen = ++generation
        state = State.Reconnecting
        val resume = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
        resume.put(RESUME)
        resume.put(t)
        resume.putLong(received)
        connect(gen, resume.array())
    }

    private fun connect(gen: Int, handshake: ByteArray) {
        val request = Request.Builder()
            .url("$baseUrl/api/data")
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        socket = http.newWebSocket(request, DataListener(gen, handshake))
    }

    @Synchronized
    private fun scheduleRetry(gen: Int, fresh: Boolean) {
        if (gen != generation || stopped) return
        socket = null
        state = State.Reconnecting
        if (fresh) token = null
        changed()
        val wait = minOf(10_000L, 500L shl minOf(retries, 5))
        retries++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            synchronized(this@TerminalSession) {
                if (gen == generation && !stopped) {
                    if (token != null) openResume() else openNew()
                }
            }
        }
    }

    private inner class DataListener(val gen: Int, val handshake: ByteArray) : WebSocketListener() {
        private var welcomed = false
        private var closeHandled = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(handshake.toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            synchronized(this@TerminalSession) {
                if (gen != generation) return
                val data = bytes.toByteArray()
                if (welcomed) {
                    received += data.size
                    parser?.feed(data)
                } else {
                    handshakeReply(webSocket, data)
                }
            }
            // Also after the handshake: a resumed stream may send nothing more for a long time,
            // and the pane would keep saying it's reconnecting.
            changed()
        }

        private fun handshakeReply(webSocket: WebSocket, data: ByteArray) {
            if (data.isEmpty()) return
            when (data[0]) {
                WELCOME -> if (data.size >= 32) {
                    welcomed = true
                    retries = 0
                    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    token = data.copyOfRange(1, 17)
                    val newNonce = b.getLong(24).toULong()
                    val resumed = parser != null && newNonce == nonce
                    nonce = newNonce
                    if (!resumed) {
                        parser = TerminalStreamParser(
                            nonce,
                            onSnapshot = ::handleSnapshot,
                            onOutput = { d, o, l -> terminal?.write(d, o, l) },
                            onMessage = ::handleMessage,
                        )
                    }
                    state = State.Live
                }
                REJECT -> {
                    // A refused RESUME or HELLO means starting over with a new ticket.
                    token = null
                    parser = null
                    // Cancelling reports a failure, which mustn't be taken for a dropped connection.
                    // That scheduled a second retry, and retried a block with no terminal for ever.
                    closeHandled = true
                    webSocket.cancel()
                    val reason = if (data.size > 1) data[1].toInt() else 0
                    if (reason == REJECT_UNAVAILABLE && !childExited && terminal == null) {
                        state = State.Closed
                        socket = null
                    } else {
                        scheduleRetry(gen, fresh = true)
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            handleClose(code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = handleClose(code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = handleClose(1006)

        private fun handleClose(code: Int) {
            synchronized(this@TerminalSession) {
                // A close arrives as closing and then closed, and each would schedule a retry.
                if (closeHandled || gen != generation || stopped) return
                closeHandled = true
                when (code) {
                    CLOSE_BLOCK_CLOSED, 1000 -> {
                        state = State.Closed
                        socket = null
                    }
                    1013, 1008, 1011 -> scheduleRetry(gen, fresh = true)
                    else -> scheduleRetry(gen, fresh = false)
                }
            }
            changed()
        }
    }

    private fun handleSnapshot(bytes: ByteArray, len: Int) {
        val old = terminal
        val decoded = GhosttyTerminal.fromSnapshot(bytes, len)
        if (decoded != null) {
            usedFallback = false
            terminal = decoded
        } else {
            usedFallback = true
            terminal = GhosttyTerminal.create(maxOf(cols, 80), maxOf(rows, 24))
            scope.launch {
                val screen = readScreen() ?: return@launch
                // The fetched screen is older than bytes that may already have arrived, so clear
                // and redraw only the part of the screen it describes.
                terminal?.write(screen.toByteArray())
                changed()
            }
        }
        old?.close()
        applyColors()
    }

    private fun handleMessage(msg: JsonObject) {
        when (msg["type"].str()) {
            "resize" -> {
                val c = msg["cols"].int() ?: return
                val r = msg["rows"].int() ?: return
                cols = c
                rows = r
                terminal?.resize(c, r, msg["cell_width_px"].int() ?: 0, msg["cell_height_px"].int() ?: 0)
            }
            "theme" -> {
                val colors = TerminalColors.fromRexTheme(msg)
                theme = colors
                onHostTheme(colors)
                applyColors()
            }
            "child_exited" -> childExited = true
        }
    }

    companion object {
        const val SUBPROTOCOL = "rex.data.terminal.v1"
        private const val HELLO: Byte = 0x01
        private const val RESUME: Byte = 0x02
        private val WELCOME: Byte = 0x81.toByte()
        private val REJECT: Byte = 0x82.toByte()
        private const val REJECT_UNAVAILABLE = 0x03
        private const val CLOSE_BLOCK_CLOSED = 4000
    }
}

/** Default colors for a terminal. Each is 0xFFRRGGBB, or 0 for the built-in value. */
data class TerminalColors(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val palette: IntArray?,
    /** "light" or "dark" when the host says; otherwise worked out from the background. */
    val scheme: String? = null,
) {
    // Arrays compare by identity, which would make every theme a terminal reports look new.
    override fun equals(other: Any?): Boolean = this === other || other is TerminalColors &&
        foreground == other.foreground && background == other.background && cursor == other.cursor &&
        scheme == other.scheme && palette.contentEquals(other.palette)

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * foreground + background) + cursor) + palette.contentHashCode()) + scheme.hashCode()

    companion object {
        private fun hex(s: String?): Int {
            val clean = s?.trim()?.removePrefix("#") ?: return 0
            if (clean.length != 6) return 0
            return (clean.toIntOrNull(16) ?: return 0) or (0xFF shl 24)
        }

        fun fromRexTheme(msg: JsonObject): TerminalColors {
            val changes = msg["palette"].arr().orEmpty().mapNotNull { it.str() }
            val palette = if (changes.isEmpty()) null else BUILTIN_PALETTE.copyOf().also { p ->
                for (change in changes) {
                    val parts = change.split("=", limit = 2)
                    val index = parts.getOrNull(0)?.toIntOrNull() ?: continue
                    val color = hex(parts.getOrNull(1))
                    if (index in 0..255 && color != 0) p[index] = color
                }
            }
            return TerminalColors(hex(msg["foreground"].str()), hex(msg["background"].str()), hex(msg["cursor"].str()), palette, msg["scheme"].str())
        }

        /** The standard xterm 256-color palette. */
        val BUILTIN_PALETTE: IntArray = IntArray(256).also { p ->
            val base = intArrayOf(
                0x000000, 0xCC0000, 0x4E9A06, 0xC4A000, 0x3465A4, 0x75507B, 0x06989A, 0xD3D7CF,
                0x555753, 0xEF2929, 0x8AE234, 0xFCE94F, 0x729FCF, 0xAD7FA8, 0x34E2E2, 0xEEEEEC,
            )
            for (i in 0 until 16) p[i] = base[i] or (0xFF shl 24)
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            var n = 16
            for (r in steps) for (g in steps) for (b in steps) {
                p[n++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            for (i in 0 until 24) {
                val v = 8 + i * 10
                p[n++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }
}
