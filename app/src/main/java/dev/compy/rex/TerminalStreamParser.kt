package dev.compy.rex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream

/**
 * Splits a terminal stream into its three parts: the leading Ghostty snapshot, program output, and
 * Rex's own messages. A Rex message is `ESC _ rex1;<nonce>;<json> ESC \`, and the first one comes
 * straight after the snapshot, so its marker also shows where the snapshot ends. That keeps this
 * independent of the snapshot's internal format.
 */
class TerminalStreamParser(
    nonce: ULong,
    private val onSnapshot: (ByteArray, Int) -> Unit,
    private val onOutput: (ByteArray, Int, Int) -> Unit,
    private val onMessage: (JsonObject) -> Unit,
) {
    private val marker = "\u001b_rex1;$nonce;".toByteArray(Charsets.US_ASCII)
    private val snapshot = ByteArrayOutputStream(64 * 1024)
    private var inSnapshot = true
    private var pending = ByteArray(0)
    private var searchedTo = 0

    fun feed(data: ByteArray, len: Int = data.size) {
        if (inSnapshot) {
            snapshot.write(data, 0, len)
            feedSnapshot()
            return
        }
        scan(data, len)
    }

    private fun feedSnapshot() {
        val bytes = snapshot.toByteArray()
        val start = maxOf(0, searchedTo - marker.size)
        val at = indexOf(bytes, marker, start, bytes.size)
        if (at < 0) {
            searchedTo = bytes.size
            return
        }
        inSnapshot = false
        onSnapshot(bytes, at)
        snapshot.reset()
        scan(bytes.copyOfRange(at, bytes.size), bytes.size - at)
    }

    private fun scan(chunk: ByteArray, chunkLen: Int) {
        val data: ByteArray
        val len: Int
        if (pending.isEmpty()) {
            data = chunk
            len = chunkLen
        } else {
            data = pending + chunk.copyOf(chunkLen)
            len = data.size
            pending = ByteArray(0)
        }
        var flushed = 0
        var i = 0
        while (i < len) {
            if (data[i] != ESC) {
                i++
                continue
            }
            val matched = matchPrefix(data, i, len)
            if (matched == marker.size) {
                val end = findTerminator(data, i + marker.size, len)
                if (end < 0) {
                    if (len - i > MAX_MESSAGE) {
                        // No real Rex message is this large, so treat it as output.
                        i += marker.size
                        continue
                    }
                    if (i > flushed) onOutput(data, flushed, i - flushed)
                    pending = data.copyOfRange(i, len)
                    return
                }
                if (i > flushed) onOutput(data, flushed, i - flushed)
                parseMessage(data, i + marker.size, end)
                i = end + 2
                flushed = i
            } else if (i + matched == len) {
                // The chunk ends partway through what may be the marker.
                if (i > flushed) onOutput(data, flushed, i - flushed)
                pending = data.copyOfRange(i, len)
                return
            } else {
                i++
            }
        }
        if (len > flushed) onOutput(data, flushed, len - flushed)
    }

    private fun matchPrefix(data: ByteArray, at: Int, len: Int): Int {
        var n = 0
        while (n < marker.size && at + n < len && data[at + n] == marker[n]) n++
        return n
    }

    private fun findTerminator(data: ByteArray, from: Int, len: Int): Int {
        var i = from
        while (i + 1 < len) {
            if (data[i] == ESC && data[i + 1] == BACKSLASH) return i
            i++
        }
        return -1
    }

    private fun parseMessage(data: ByteArray, from: Int, to: Int) {
        val text = String(data, from, to - from, Charsets.UTF_8)
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        onMessage(obj)
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        var i = from
        val last = to - needle.size
        outer@ while (i <= last) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private companion object {
        const val ESC: Byte = 0x1b
        const val BACKSLASH: Byte = 0x5c
        const val MAX_MESSAGE = 1 shl 20
    }
}
