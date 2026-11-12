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

class ShortFolderTest {
    @org.junit.Test
    fun shortensUnderAHomeFolder() {
        org.junit.Assert.assertEquals("~/compy", shortFolder("/Users/someone/compy"))
        org.junit.Assert.assertEquals("~/work/api", shortFolder("file://host.local/home/someone/work/api"))
        org.junit.Assert.assertEquals("~", shortFolder("/Users/someone"))
        org.junit.Assert.assertEquals("~", shortFolder("/root"))
        org.junit.Assert.assertEquals("/var/log", shortFolder("/var/log"))
        org.junit.Assert.assertEquals("~/my project", shortFolder("file:///Users/someone/my%20project"))
        org.junit.Assert.assertNull(shortFolder(""))
        org.junit.Assert.assertNull(shortFolder(null))
    }
}
