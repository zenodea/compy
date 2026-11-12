package dev.compy.data

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPaneStoreTest {
    private val dir: File = Files.createTempDirectory("compy-saved").toFile()
    private var clock = 1000L

    private fun store(maxCount: Int = 40, maxBytes: Long = 1 shl 20, maxOne: Long = 1 shl 20) =
        SavedPaneStore(dir, maxCount, maxBytes, maxOne) { clock++ }

    /** Bytes that don't compress, so a file is about as large as what went in. */
    private fun noise(size: Int) = Random(size).nextBytes(size)

    @After
    fun tidy() {
        dir.deleteRecursively()
    }

    @Test
    fun savesAndReadsBackAfterARestart() {
        val saved = store().save("claude", "studio · api", SavedPane.CHAT, "hello".toByteArray())!!
        val again = store().also { it.load() }
        assertEquals(listOf(saved), again.list.value)
        assertArrayEquals("hello".toByteArray(), again.read(saved.id))
    }

    @Test
    fun keepsOnlyTheNewestFew() {
        val s = store(maxCount = 3)
        val all = (1..5).map { s.save("pane $it", "", SavedPane.TERMINAL, "x$it".toByteArray())!! }
        assertEquals(listOf("pane 5", "pane 4", "pane 3"), s.list.value.map { it.title })
        // The ones that were dropped take their files with them.
        assertNull(s.read(all[0].id))
        assertEquals(3, dir.listFiles()!!.count { it.name.endsWith(".gz") })
    }

    @Test
    fun dropsTheOldestToStayWithinItsSize() {
        val s = store(maxBytes = 10_000)
        repeat(4) { s.save("pane $it", "", SavedPane.TERMINAL, noise(4_000 + it)) }
        assertTrue(s.totalBytes <= 10_000)
        assertEquals(listOf("pane 3", "pane 2"), s.list.value.map { it.title })
        assertEquals(s.totalBytes, dir.listFiles()!!.filter { it.name.endsWith(".gz") }.sumOf { it.length() })
    }

    @Test
    fun refusesOnePaneThatIsTooLarge() {
        val s = store(maxOne = 1_000)
        s.save("small", "", SavedPane.TERMINAL, "ok".toByteArray())
        assertNull(s.save("huge", "", SavedPane.TERMINAL, noise(5_000)))
        assertEquals(listOf("small"), s.list.value.map { it.title })
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".gz") })
    }

    @Test
    fun deletesOneOrAll() {
        val s = store()
        val a = s.save("a", "", SavedPane.CHAT, "a".toByteArray())!!
        s.save("b", "", SavedPane.CHAT, "b".toByteArray())
        s.delete(a.id)
        assertEquals(listOf("b"), s.list.value.map { it.title })
        assertNull(s.read(a.id))
        s.deleteAll()
        assertTrue(s.list.value.isEmpty())
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".gz") })
        assertTrue(store().also { it.load() }.list.value.isEmpty())
    }

    @Test
    fun tidiesFilesNothingListsAndEntriesWithNoFile() {
        val s = store()
        val kept = s.save("kept", "", SavedPane.CHAT, "k".toByteArray())!!
        val lost = s.save("lost", "", SavedPane.CHAT, "l".toByteArray())!!
        File(dir, "${lost.id}.gz").delete()
        File(dir, "stray.gz").writeText("left by a save that was cut short")
        File(dir, "index.json.new").writeText("[]")
        val again = store().also { it.load() }
        assertEquals(listOf("kept"), again.list.value.map { it.title })
        assertFalse(File(dir, "stray.gz").exists())
        assertFalse(File(dir, "index.json.new").exists())
        assertNotNull(again.read(kept.id))
    }

    @Test
    fun anUnreadableIndexStartsEmptyAndClearsItsFiles() {
        store().save("a", "", SavedPane.CHAT, "a".toByteArray())
        File(dir, "index.json").writeText("not json")
        val again = store().also { it.load() }
        assertTrue(again.list.value.isEmpty())
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".gz") })
    }
}
