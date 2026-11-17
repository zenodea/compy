package dev.compy.ui

import dev.compy.rex.DirEntry
import dev.compy.rex.DirListing
import dev.compy.system.withExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private val place = Place("p", "Windows", "nvim", "api · laptop") {}

    @Test fun findsAPlaceByAnyOfItsWords() {
        assertTrue(matches(place, ""))
        assertTrue(matches(place, "NVIM"))
        assertTrue(matches(place, "laptop nv"))
        assertFalse(matches(place, "nvim desktop"))
    }

    private val folder = DirListing("/home/a", listOf(DirEntry(".git", false), DirEntry("src", false), DirEntry("notes.md", true)))

    @Test fun keepsHiddenNamesBackUntilAskedFor() {
        assertEquals(listOf("..", "src", "notes.md"), folderRows(folder, "").map { it.first })
        assertEquals(listOf(".git"), folderRows(folder, ".g").map { it.first })
        assertEquals(listOf("notes.md"), folderRows(folder, "NOTE").map { it.first })
    }

    @Test fun theRootHasNothingAboveIt() {
        assertEquals(listOf("etc"), folderRows(DirListing("/", listOf(DirEntry("etc", false))), "").map { it.first })
    }

    @Test fun namesAClipboardPictureForWhatItIs() {
        assertEquals("clip.png", withExtension("clip", "image/png"))
        assertEquals("clip.jpg", withExtension("clip", "image/jpeg"))
        assertEquals("drawing.svg", withExtension("drawing", "image/svg+xml"))
        assertEquals("photo.jpg", withExtension("photo.jpg", "image/png"))
        assertEquals("clip", withExtension("clip", null))
        assertEquals(".env", withExtension(".env", null))
    }

    @Test fun readsTextAsLinesAndLeavesOtherFilesAlone() {
        assertEquals(listOf("one", "two"), textLines("one\r\ntwo\n".toByteArray())?.lines)
        assertEquals(listOf(""), textLines(ByteArray(0))?.lines)
        assertNull(textLines(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0, 0, 1)))
    }
}
