package dev.compy.rex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FileDownloadTest {
    private val welcome = ByteArray(32).also { it[0] = 0x81.toByte() }

    @Test fun readsAFileSentInParts() {
        val frames = DownloadFrames()
        assertNull(frames.accept(welcome))
        assertNull(frames.accept("hello ".toByteArray()))
        assertNull(frames.accept("world".toByteArray()))
        assertArrayEquals("hello world".toByteArray(), frames.finish(1000))
    }

    @Test fun keepsAFileThatStartsLikeAWelcome() {
        val frames = DownloadFrames()
        frames.accept(welcome)
        frames.accept(welcome)
        assertEquals(32, frames.finish(1000)?.size)
    }

    @Test fun anEmptyFileIsStillAFile() {
        val frames = DownloadFrames()
        frames.accept(welcome)
        assertEquals(0, frames.finish(1000)?.size)
    }

    @Test fun refusesAStreamThatDoesNotStartWithAWelcome() {
        assertNotNull(DownloadFrames().accept("not a welcome".toByteArray()))
        assertNotNull(DownloadFrames().accept(ByteArray(32)))
    }

    @Test fun aStreamCutShortIsNotAFile() {
        val frames = DownloadFrames()
        frames.accept(welcome)
        frames.accept("half".toByteArray())
        assertNull(frames.finish(1006))
        assertNull(DownloadFrames().finish(1000))
    }

    @Test fun stopsAtTheMostItReads() {
        val frames = DownloadFrames()
        frames.accept(welcome)
        assertNull(frames.accept(ByteArray(MAX_DOWNLOAD_BYTES)))
        assertNotNull(frames.accept(ByteArray(1)))
    }
}
