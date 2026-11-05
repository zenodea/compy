package dev.compy.rex

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderNameTest {
    @Test fun readsFileUrls() {
        assertEquals("my project", folderName("file://laptop/Users/a/my%20project"))
    }

    @Test fun readsPlainPaths() {
        assertEquals("compy", folderName("/Users/a/compy/"))
    }

    @Test fun keepsTheRoot() {
        assertEquals("/", folderName("file:///"))
    }

    @Test fun nothingForNothing() {
        assertEquals(null, folderName(null))
    }
}
