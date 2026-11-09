package io.github.kinteshi.quirecode.workbench

import org.junit.Assert.assertEquals
import org.junit.Test

class FileTransfersTest {
    @Test
    fun selectedItemsKeepTheirNamesAndClashesGetNumbers() {
        assertEquals(listOf("src", "app.py"), transferTopNames(listOf("/home/u/p/src", "/home/u/p/app.py")))
        assertEquals(listOf("README.md", "README (2).md", "readme (3).md"), transferTopNames(listOf("/a/README.md", "/b/README.md", "/c/readme.md")))
        assertEquals(listOf("files"), transferTopNames(listOf("/")))
    }

    @Test
    fun numberedNamesKeepTheExtension() {
        assertEquals("report (2).txt", numberedName("report.txt", 2))
        assertEquals("src (3)", numberedName("src", 3))
        assertEquals(".env (2)", numberedName(".env", 2))
        assertEquals("archive.tar (2).gz", numberedName("archive.tar.gz", 2))
    }

    @Test
    fun archivesAreNamedAfterWhatsInThem() {
        assertEquals("src.zip", archiveName(listOf("/home/u/p/src")))
        assertEquals("p (3 items).zip", archiveName(listOf("/home/u/p/a.py", "/home/u/p/b.py", "/home/u/p/c")))
    }

    @Test
    fun sizesForPeople() {
        assertEquals("512 bytes", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 MB", formatBytes(2L * 1024 * 1024))
        assertEquals("1.0 GB", formatBytes(1L shl 30))
    }

    @Test
    fun progressFraction() {
        assertEquals(0.5f, TransferProgress("t", 1, 4, 50, 100).fraction)
        assertEquals(0.25f, TransferProgress("t", 1, 4, 0, 0).fraction)
    }
}
