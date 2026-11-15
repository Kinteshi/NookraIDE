package io.github.kinteshi.quirecode.document

import io.github.kinteshi.quirecode.transport.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineIndexTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun entry(path: String, directory: Boolean = false, size: Long = 10) =
        FileEntry(path.substringAfterLast('/'), path, directory, size, 1_000)

    private val project = OfflineIndex.Tree(
        folder = "/home/u/Projects/app",
        entries = listOf(
            entry("/home/u/Projects/app/src", directory = true),
            entry("/home/u/Projects/app/src/main.py"),
            entry("/home/u/Projects/app/README.md"),
            entry("/home/u/Projects/app/node_modules", directory = true),
        ),
        syncedAt = 5,
        skipped = listOf("/home/u/Projects/app/big.bin"),
        bytes = 20,
    )

    @Test
    fun keptFoldersAreListedAsSynced() {
        val index = OfflineIndex(temp.newFolder("index"))
        index.save(project)
        assertEquals(listOf("README.md", "node_modules", "src"), index.listDir("/home/u/Projects/app")!!.map { it.name }.sorted())
        assertEquals(listOf("main.py"), index.listDir("/home/u/Projects/app/src/")!!.map { it.name })
        assertEquals(emptyList<FileEntry>(), index.listDir("/home/u/Projects/app/node_modules"))
        assertNull(index.listDir("/home/u/Projects/app/missing"))
        // Above a kept folder: the way down to it.
        assertEquals(listOf("app"), index.listDir("/home/u/Projects")!!.map { it.name })
        assertEquals(listOf("Projects"), index.listDir("/home/u")!!.map { it.name })
        assertTrue(index.stat("/home/u/Projects")!!.isDirectory)
        assertTrue(index.serves("/home/u/Projects"))
        assertNull(index.listDir("/home/other"))
        assertFalse(index.serves("/home/other"))
        assertTrue(index.stat("/home/u/Projects/app")!!.isDirectory)
        assertEquals(10L, index.stat("/home/u/Projects/app/src/main.py")!!.size)
        assertNull(index.stat("/home/u/Projects/app/nope.txt"))
        assertTrue(index.isKept("/home/u/Projects/app/"))
        assertFalse(index.isKept("/home/u/Projects/app/src"))
        assertEquals("/home/u/Projects/app", index.covering("/home/u/Projects/app/src/main.py")!!.folder)
        assertNull(index.covering("/home/u/Projects/application/x.py"))
        assertEquals(listOf("/home/u/Projects/app/README.md", "/home/u/Projects/app/src/main.py"), index.filesUnder("/home/u/Projects/app").map { it.path }.sorted())
    }

    @Test
    fun keptFoldersSurviveARestartUntilRemoved() {
        val directory = temp.newFolder("index")
        OfflineIndex(directory).save(project)
        val reloaded = OfflineIndex(directory)
        assertEquals(listOf("/home/u/Projects/app"), reloaded.folders)
        assertEquals(project.skipped, reloaded.tree("/home/u/Projects/app")!!.skipped)
        reloaded.remove("/home/u/Projects/app")
        assertTrue(OfflineIndex(directory).folders.isEmpty())
    }

    @Test
    fun sameGuestPathHasSeparateOfflineTreesPerConnection() {
        val directory = temp.newFolder("scoped-index")
        val index = OfflineIndex(directory)
        index.activeConnectionId = "guest-a"
        index.save(project)

        index.activeConnectionId = "guest-b"
        assertTrue(index.folders.isEmpty())
        assertFalse(index.serves(project.folder))
        assertTrue(index.serves(project.folder, "guest-a"))
        index.save(project.copy(entries = listOf(entry("${project.folder}/work-b.py"))))

        index.activeConnectionId = "guest-a"
        assertEquals(listOf("README.md", "node_modules", "src"), index.listDir(project.folder)!!.map { it.name }.sorted())
        index.activeConnectionId = "guest-b"
        assertEquals(listOf("work-b.py"), index.listDir(project.folder)!!.map { it.name })

        val reloaded = OfflineIndex(directory)
        reloaded.activeConnectionId = "guest-a"
        assertEquals(listOf("README.md", "node_modules", "src"), reloaded.listDir(project.folder)!!.map { it.name }.sorted())
        reloaded.activeConnectionId = "guest-b"
        assertEquals(listOf("work-b.py"), reloaded.listDir(project.folder)!!.map { it.name })
    }

    @Test
    fun legacyIndexCanBeAssignedToTheLastUsedConnection() {
        val directory = temp.newFolder("legacy-index")
        OfflineIndex(directory).save(project)
        val migrated = OfflineIndex(directory)
        migrated.migrateLegacyScope("guest-a")
        migrated.activeConnectionId = "guest-a"

        assertTrue(migrated.serves(project.folder))
        assertEquals("guest-a", migrated.tree(project.folder)?.connectionId)
    }
}
