package dev.compy.rex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Details from a control connection's welcome message. */
data class Welcome(
    val clientId: String,
    val reconnectToken: String?,
    val resumed: Boolean,
    val apiRevision: Int,
)

/**
 * One control connection: a WebSocket carrying JSON requests, responses and events. The owner
 * decides what to do when it closes; this class only reports it.
 */
class RexControl(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val clientLabel: String,
    private val clientVersion: String,
    private val onEvent: (type: String, payload: JsonObject?) -> Unit,
    private val onClosed: (reason: String, code: Int) -> Unit,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val nextId = AtomicLong(1)
    private val welcome = CompletableDeferred<Welcome>()
    private var socket: WebSocket? = null
    @Volatile private var closed = false

    suspend fun open(reconnectToken: String?): Welcome {
        val request = Request.Builder()
            .url("$baseUrl/api/control")
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        socket = http.newWebSocket(request, Listener(reconnectToken))
        return withTimeout(CONNECT_TIMEOUT_MS) { welcome.await() }
    }

    /** Sends a request and waits for its response. Throws [RexError] when the server refuses it. */
    suspend fun call(method: String, payload: JsonObject? = null, timeoutMs: Long = CALL_TIMEOUT_MS): JsonElement? {
        val id = nextId.getAndIncrement().toString()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        if (!send(id, method, payload)) {
            pending.remove(id)
            throw RexError("disconnected", "Not connected to the server", 0)
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Sends a request without waiting. Requests go out in order, which keeps typing in order. */
    fun fire(method: String, payload: JsonObject) {
        send(nextId.getAndIncrement().toString(), method, payload)
    }

    private fun send(id: String, method: String, payload: JsonObject?): Boolean {
        val message = buildJsonObject {
            put("type", "request")
            put("id", id)
            put("method", method)
            if (payload != null) put("payload", payload)
        }
        return socket?.send(message.toString()) == true
    }

    /** Ends the client on the server at once. Use only when the app really is going away. */
    fun close() {
        closed = true
        socket?.close(1000, null)
    }

    /** Drops the connection without a close frame so the server keeps the client for a resume. */
    fun abandon() {
        closed = true
        socket?.cancel()
    }

    private inner class Listener(private val token: String?) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = buildJsonObject {
                put("type", "hello")
                put("api_revision", API_REVISION)
                if (token != null) put("reconnect_token", token)
                put("client", buildJsonObject {
                    put("kind", "app")
                    put("platform", "other")
                    put("name", "Compy")
                    put("version", clientVersion)
                    put("label", clientLabel.take(48))
                })
            }
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
            when (msg["type"].str()) {
                "welcome" -> {
                    if (msg["protocol"].str() != SUBPROTOCOL) {
                        welcome.completeExceptionally(RexError("protocol", "Unexpected protocol", 0))
                        webSocket.cancel()
                        return
                    }
                    val client = msg["client"].obj()
                    welcome.complete(
                        Welcome(
                            clientId = client?.get("id").str().orEmpty(),
                            reconnectToken = client?.get("reconnect_token").str(),
                            resumed = client?.get("resumed").bool() == true,
                            apiRevision = msg["api_revision"].int() ?: 0,
                        ),
                    )
                }
                "response" -> {
                    val deferred = pending.remove(msg["id"].str().orEmpty()) ?: return
                    val error = msg["error"].obj()
                    if (error != null) {
                        deferred.completeExceptionally(
                            RexError(
                                error["code"].str() ?: "internal",
                                error["message"].str() ?: "The server refused the request",
                                error["status"].int() ?: 0,
                            ),
                        )
                    } else {
                        deferred.complete(msg["payload"])
                    }
                }
                "event" -> {
                    val event = msg["payload"].obj() ?: return
                    onEvent(event["type"].str().orEmpty(), event["payload"].obj())
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            finish(reason.ifEmpty { "Connection closed" }, code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(reason.ifEmpty { "Connection closed" }, code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = when (response?.code) {
                403 -> "This server doesn't allow your account"
                else -> t.message ?: "Connection failed"
            }
            finish(reason, response?.code ?: 1006)
        }

        private fun finish(reason: String, code: Int) {
            val error = RexError("disconnected", reason, code)
            welcome.completeExceptionally(error)
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
            if (!closed) onClosed(reason, code)
        }
    }

    companion object {
        const val SUBPROTOCOL = "rex.control.v1"
        const val API_REVISION = 10
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CALL_TIMEOUT_MS = 15_000L
    }
}
