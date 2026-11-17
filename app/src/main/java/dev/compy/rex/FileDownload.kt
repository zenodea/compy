package dev.compy.rex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream

/** A file read from a host: its whole path there, and what is in it. */
class Download(val path: String, val bytes: ByteArray)

/**
 * Reads one file from the machine a terminal runs on. [path] may be relative to the terminal's
 * folder. Rex only hands files to a client that speaks API revision 13, so this opens a control
 * connection of its own at that revision and closes it when the file has arrived, which leaves
 * the app's main connection as it is. Throws [RexError] with a message meant for people.
 */
internal suspend fun downloadFile(
    http: OkHttpClient,
    baseUrl: String,
    clientVersion: String,
    sessionId: String,
    blockId: String,
    path: String,
): Download {
    if (path.isEmpty() || path.length > 4096 || '\u0000' in path) throw RexError("bad_path", "That isn't a path Compy can read", 0)
    val control = RexControl(http, baseUrl, "Compy file preview", clientVersion, onEvent = { _, _ -> }, onClosed = { _, _ -> }, apiRevision = DOWNLOAD_API_REVISION)
    var stream: DownloadStream? = null
    try {
        val welcome = control.open(null)
        if (welcome.apiRevision < DOWNLOAD_API_REVISION) {
            throw RexError("not_supported", "This host runs a Rex too old to preview files. Update Rex there and restart its server.", 0)
        }
        control.call("session.attach", buildJsonObject { put("session_id", sessionId) })
        val reply = try {
            control.call(
                "com.superlogical.terminal.download_begin",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("block_id", blockId)
                    put("args", buildJsonObject {
                        put("ref", "download:${java.util.UUID.randomUUID()}")
                        put("path", path)
                    })
                },
            ).obj()
        } catch (e: RexError) {
            if (e.code == "disconnected") throw e
            throw RexError(e.code, "Can't read this file. Check the path, and that the file can be read.", e.status)
        }
        val size = reply?.get("size").long()
        if (size != null && size > MAX_DOWNLOAD_BYTES) throw RexError("too_large", "This file is too large to preview. The most is 16 MB.", 0)
        val ticket = reply?.get("ticket").str() ?: throw RexError("internal", "The server sent no download ticket", 0)
        stream = DownloadStream(http, baseUrl, ticket)
        val bytes = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { stream.read() }
            ?: throw RexError("timeout", "The file took too long to arrive", 0)
        return Download(reply?.get("path").str()?.takeIf { it.isNotEmpty() } ?: path, bytes)
    } finally {
        stream?.cancel()
        control.close()
    }
}

/**
 * The framing of a download: the server's welcome, then the file in parts, then a normal close.
 * Kept apart from the socket so it can be checked without one.
 */
internal class DownloadFrames {
    private val bytes = ByteArrayOutputStream()
    private var welcomed = false

    /** Takes one message from the server. Null when it was taken, or else why the download has failed. */
    fun accept(message: ByteArray): String? {
        if (!welcomed) {
            if (message.size != WELCOME_BYTES || message[0] != WELCOME) return "The server refused the download"
            welcomed = true
            return null
        }
        if (message.size > MAX_DOWNLOAD_BYTES - bytes.size()) return "This file is too large to preview. The most is 16 MB."
        bytes.write(message)
        return null
    }

    /** The whole file, when the server closed the stream normally after its welcome. Null when it was cut short. */
    fun finish(code: Int): ByteArray? = if (welcomed && code == 1000) bytes.toByteArray() else null

    private companion object {
        const val WELCOME: Byte = 0x81.toByte()
        const val WELCOME_BYTES = 32
    }
}

/** One file's `rex.data.bytes.v1` stream, read to its end. */
internal class DownloadStream(http: OkHttpClient, baseUrl: String, ticket: String) {
    private val done = CompletableDeferred<ByteArray>()
    private val frames = DownloadFrames()
    private val socket: WebSocket

    init {
        val request = Request.Builder()
            .url("$baseUrl/api/data")
            .header("Sec-WebSocket-Protocol", UploadStream.SUBPROTOCOL)
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val key = runCatching { java.util.Base64.getUrlDecoder().decode(ticket.trimEnd('=')) }.getOrNull()
                if (key == null || key.size != 16) {
                    fail("The server sent a download ticket Compy can't use")
                    webSocket.cancel()
                    return
                }
                val hello = ByteArray(17)
                hello[0] = 0x01
                key.copyInto(hello, 1)
                webSocket.send(hello.toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val problem = frames.accept(bytes.toByteArray()) ?: return
                fail(problem)
                webSocket.cancel()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                fail("The server sent something that isn't a file")
                webSocket.cancel()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Not the peer's code: one sent without a status arrives as 1005, which can't be sent back.
                webSocket.close(1000, null)
                end(code)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(code)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end(-1)
        })
    }

    private fun end(code: Int) {
        val file = frames.finish(code)
        if (file != null) done.complete(file) else fail("The download stopped before the file had arrived")
    }

    private fun fail(message: String) {
        done.completeExceptionally(RexError("download_failed", message, 0))
    }

    suspend fun read(): ByteArray = done.await()

    fun cancel() = socket.cancel()
}

/** The most of a file that is read for a preview. */
internal const val MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024

/** The API revision in which Rex began handing files to clients. */
internal const val DOWNLOAD_API_REVISION = 13
private const val DOWNLOAD_TIMEOUT_MS = 60_000L
