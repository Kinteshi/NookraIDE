package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.transport.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerTreeTest {
    private fun dir(path: String) = FileEntry(path.substringAfterLast('/'), path, true, 0, 0)
    private fun file(path: String) = FileEntry(path.substringAfterLast('/'), path, false, 0, 0)

    private val top = sortedEntries(listOf(file("/w/main.py"), dir("/w/src"), dir("/w/docs")))
    private val children = mapOf(
        "/w/src" to listOf(dir("/w/src/pkg"), file("/w/src/a.py")),
        "/w/src/pkg" to listOf(file("/w/src/pkg/b.py")),
    )

    @Test
    fun foldersComeFirstAndOpenFoldersListTheirChildrenBelow() {
        val rows = explorerRows(top, setOf("/w/src", "/w/src/pkg"), children)
        assertEquals(
            listOf("docs" to 0, "src" to 0, "pkg" to 1, "b.py" to 2, "a.py" to 1, "main.py" to 0),
            rows.map { it.entry.name to it.depth },
        )
        assertEquals(listOf(false, true, true, false, false, false), rows.map { it.expanded })
    }

    @Test
    fun aClosedFolderHidesItsOpenSubfolders() {
        val rows = explorerRows(top, setOf("/w/src/pkg"), children)
        assertEquals(listOf("docs", "src", "main.py"), rows.map { it.entry.name })
    }

    @Test
    fun anOpenFolderStillLoadingShowsNoChildren() {
        val rows = explorerRows(top, setOf("/w/docs"), children)
        assertEquals(listOf(true), rows.filter { it.entry.name == "docs" }.map { it.expanded })
        assertEquals(3, rows.size)
    }
}
