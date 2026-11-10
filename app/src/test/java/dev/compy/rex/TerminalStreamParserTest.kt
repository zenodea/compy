package dev.compy.rex

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class TerminalStreamParserTest {
    private val nonce = 18446744073709551557uL
    private fun rex(json: String) = "\u001b_rex1;$nonce;$json\u001b\\".toByteArray()

    private class Sink {
        var snapshot: ByteArray? = null
        val output = ByteArrayOutputStream()
        val messages = mutableListOf<JsonObject>()
    }

    private fun parser(s: Sink) = TerminalStreamParser(
        nonce,
        onSnapshot = { b, n -> s.snapshot = b.copyOf(n) },
        onOutput = { b, o, l -> s.output.write(b, o, l) },
        onMessage = { s.messages += it },
    )

    private val stream = "SNAPSHOT-BYTES".toByteArray() +
        rex("""{"type":"resize","cols":80,"rows":24}""") +
        "hello \u001b[31mred\u001b[0m".toByteArray() +
        "\u001b_rex1;999;{\"type\":\"fake\"}\u001b\\".toByteArray() +
        rex("""{"type":"child_exited","exit_code":0}""") +
        "tail".toByteArray()

    private val expectedOutput = "hello \u001b[31mred\u001b[0m".toByteArray() +
        "\u001b_rex1;999;{\"type\":\"fake\"}\u001b\\".toByteArray() + "tail".toByteArray()

    private fun verify(s: Sink) {
        assertArrayEquals("SNAPSHOT-BYTES".toByteArray(), s.snapshot)
        assertArrayEquals(expectedOutput, s.output.toByteArray())
        assertEquals(2, s.messages.size)
        assertEquals("resize", s.messages[0]["type"].str())
        assertEquals("child_exited", s.messages[1]["type"].str())
    }

    @Test fun wholeStreamInOneChunk() {
        val s = Sink()
        parser(s).feed(stream)
        verify(s)
    }

    @Test fun everyByteInItsOwnChunk() {
        val s = Sink()
        val p = parser(s)
        for (b in stream) p.feed(byteArrayOf(b))
        verify(s)
    }

    @Test fun everySplitPoint() {
        for (cut in 1 until stream.size) {
            val s = Sink()
            val p = parser(s)
            p.feed(stream.copyOfRange(0, cut))
            p.feed(stream.copyOfRange(cut, stream.size))
            verify(s)
        }
    }

    @Test fun usesOnlyTheGivenLength() {
        val s = Sink()
        val p = parser(s)
        p.feed(stream + ByteArray(32) { 'X'.code.toByte() }, stream.size)
        verify(s)
    }

    @Test fun keepsALargeSnapshotWholeAcrossManyChunks() {
        // Larger than the buffer starts out, and no byte pair in it can begin a Rex message.
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val all = big + rex("""{"type":"resize","cols":80,"rows":24}""") + "tail".toByteArray()
        val s = Sink()
        val p = parser(s)
        var at = 0
        while (at < all.size) {
            val n = minOf(7001, all.size - at)
            p.feed(all.copyOfRange(at, at + n))
            at += n
        }
        assertArrayEquals(big, s.snapshot)
        assertArrayEquals("tail".toByteArray(), s.output.toByteArray())
        assertEquals(1, s.messages.size)
    }

    @Test fun plainEscapeIsNotHeldBack() {
        val s = Sink()
        val p = parser(s)
        p.feed("x".toByteArray() + rex("""{"type":"resize"}"""))
        p.feed("\u001b[0m".toByteArray())
        assertArrayEquals("\u001b[0m".toByteArray(), s.output.toByteArray())
    }
}
