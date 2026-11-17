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

class FolderPathTest {
    @Test fun readsTheWholePathOfAFileUrl() {
        assertEquals("/Users/a/my project", folderPath("file://laptop/Users/a/my%20project"))
        assertEquals("/home/a/c++", folderPath("file:///home/a/c++"))
        assertEquals("/srv/app", folderPath("/srv/app"))
    }

    @Test fun nothingWithoutAPath() {
        assertEquals(null, folderPath(null))
        assertEquals(null, folderPath("file://laptop"))
        assertEquals(null, folderPath("relative/place"))
    }

    @Test fun quotesAPathAsOneWord() {
        assertEquals("'/a/my work'", shellQuoted("/a/my work"))
        assertEquals("'/a/it'\\''s'", shellQuoted("/a/it's"))
    }

    @Test fun listsFoldersAheadOfFiles() {
        val listing = parseDirListing(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"path":"/home/a","entries":[{"name":"b.txt","file":true},{"name":"Zed"},{"name":"apps","file":false}]}""",
            ),
        )!!
        assertEquals(listOf("apps", "Zed", "b.txt"), listing.entries.map { it.name })
        assertEquals("/home/a/apps", listing.child("apps"))
        assertEquals("/home", listing.child(".."))
        assertEquals("/", DirListing("/home", emptyList()).child(".."))
        assertEquals("/etc", DirListing("/", emptyList()).child("etc"))
    }
}
