package dev.compy.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPathsTest {
    private fun hit(path: String, line: Int = 0) = TerminalPaths.Hit(path, line)

    @Test fun tellsFilesFromWords() {
        assertTrue(TerminalPaths.candidate("src/main.kt"))
        assertTrue(TerminalPaths.candidate("README.md"))
        assertTrue(TerminalPaths.candidate(".gitignore"))
        assertFalse(TerminalPaths.candidate("hello"))
        assertFalse(TerminalPaths.candidate("3.14"))
        assertFalse(TerminalPaths.candidate("/"))
        assertFalse(TerminalPaths.candidate("www.example.com"))
    }

    @Test fun readsTheLineAPathNames() {
        assertEquals(hit("src/a.kt", 12), TerminalPaths.parse("src/a.kt:12"))
        assertEquals(hit("src/a.kt", 12), TerminalPaths.parse("src/a.kt:12:5"))
        assertEquals(hit("src/a.kt", 12), TerminalPaths.parse("src/a.kt:12-20"))
        assertEquals(hit("src/a.kt"), TerminalPaths.parse(" src/a.kt "))
    }

    @Test fun readsFileLinksAsAgentsWriteThem() {
        assertEquals(hit("/home/me/my work/a.kt"), TerminalPaths.parse("file:///home/me/my%20work/a.kt"))
        assertEquals(hit("/home/me/a.kt", 42), TerminalPaths.parse("file://laptop/home/me/a.kt#L42"))
        assertEquals(hit("/home/me/a.kt", 42), TerminalPaths.parse("file:///home/me/a.kt#L42-L50"))
    }

    @Test fun leavesWebAddressesAlone() {
        assertNull(TerminalPaths.parse("https://example.com/a.kt"))
        assertNull(TerminalPaths.parse("mailto:me@example.com"))
        assertNull(TerminalPaths.parse("12:30"))
    }

    @Test fun findsThePathUnderACell() {
        val rows = listOf("error in src/main.kt:42: unexpected token      ")
        assertEquals(hit("src/main.kt", 42), TerminalPaths.at(rows, 12, 0))
        // The word before it isn't a file.
        assertNull(TerminalPaths.at(rows, 1, 0))
        assertNull(TerminalPaths.at(rows, 0, 3))
    }

    @Test fun dropsWhatSurroundsAPath() {
        assertEquals(hit("docs/guide.md"), TerminalPaths.at(listOf("see (docs/guide.md), then go"), 8, 0))
        assertEquals(hit("my notes/to do.txt"), TerminalPaths.at(listOf("open 'my notes/to do.txt' now"), 10, 0))
    }

    @Test fun followsAPathOntoTheNextRow() {
        val rows = listOf("at app/src/main/java/de", "v/compy/Main.kt:7 here ")
        assertEquals(hit("app/src/main/java/dev/compy/Main.kt", 7), TerminalPaths.at(rows, 5, 0))
        assertEquals(hit("app/src/main/java/dev/compy/Main.kt", 7), TerminalPaths.at(rows, 3, 1))
    }
}
