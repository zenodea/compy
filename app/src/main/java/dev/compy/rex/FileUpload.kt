package dev.compy.rex

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream

/**
 * A file to send to a terminal's machine: its name, exact size in bytes, and a way to read it.
 * [discard] runs once the upload is over, to delete a temporary copy.
 */
class UploadFile(val name: String, val size: Long, val discard: () -> Unit = {}, val open: () -> InputStream)

/**
 * One file's `rex.data.bytes.v1` stream. Rex types a batch's paths once every file has arrived, and
 * only then ends each stream, so a batch sends every file before waiting on any of them.
 */
internal class UploadStream(http: OkHttpClient, baseUrl: String, ticket: String) {
    private val welcome = CompletableDeferred<Unit>()
    private val ended = CompletableDeferred<Pair<Int, String>>()
    private val socket: WebSocket

    init {
        val request = Request.Builder()
            .url("$baseUrl/api/data")
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = ByteArray(17)
                hello[0] = HELLO
                Base64.decode(ticket, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).copyInto(hello, 1)
                webSocket.send(hello.toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (welcome.isCompleted) return
                when (bytes.size.takeIf { it > 0 }?.let { bytes[0] }) {
                    WELCOME -> welcome.complete(Unit)
                    else -> welcome.completeExceptionally(RexError("rejected", "The server refused the upload", 0))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Not the peer's code: one sent without a status arrives as 1005, which can't be sent back.
                webSocket.close(1000, null)
                end(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                end(-1, t.message ?: "the connection dropped")
        })
    }

    @Volatile private var closeReason: String? = null

    private fun end(code: Int, reason: String) {
        if (closeReason == null) closeReason = reason.ifEmpty { "code $code" }
        welcome.completeExceptionally(RexError("closed", "The upload connection closed: $reason", 0))
        ended.complete(code to reason)
    }

    /** Sends the file's bytes, then an empty message, which tells the server the file is complete. */
    suspend fun send(file: UploadFile, onSent: (Long) -> Unit) {
        withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { welcome.await() }
            ?: throw RexError("timeout", "The server didn't accept the upload in time", 0)
        // Opening and reading can block for as long as the file's app takes to fetch it.
        withContext(Dispatchers.IO) { sendBytes(file, onSent) }
        socket.send(ByteString.EMPTY)
    }

    private suspend fun sendBytes(file: UploadFile, onSent: (Long) -> Unit) {
        file.open().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                // OkHttp drops the connection when its send queue passes 16 MiB, so wait for room.
                while (socket.queueSize() > QUEUE_LIMIT) {
                    if (ended.isCompleted) break
                    delay(20)
                }
                if (ended.isCompleted || !socket.send(buffer.toByteString(0, n))) {
                    throw RexError("closed", "The upload stopped: ${closeReason ?: "connection lost"}", 0)
                }
                onSent(n.toLong())
            }
        }
    }

    /** Waits for the server to end the stream. Code 1000 means the file arrived and its path was typed. */
    suspend fun result(): Pair<Int, String> = ended.await()

    fun cancel() = socket.cancel()

    companion object {
        const val SUBPROTOCOL = "rex.data.bytes.v1"
        private const val HELLO: Byte = 0x01
        private const val WELCOME: Byte = 0x81.toByte()
        private const val CHUNK_BYTES = 64 * 1024
        private const val QUEUE_LIMIT = 1L shl 20
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
    }
}
