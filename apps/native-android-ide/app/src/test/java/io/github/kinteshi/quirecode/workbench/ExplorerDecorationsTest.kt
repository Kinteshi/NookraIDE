package io.github.kinteshi.quirecode.workbench

import io.github.kinteshi.quirecode.transport.GitFileStatus
import io.github.kinteshi.quirecode.transport.GitStatusEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExplorerDecorationsTest {
    private fun entry(path: String, status: GitFileStatus) = GitStatusEntry(path, null, ".", "M", status)

    @Test
    fun marksFilesAndTheirFolders() {
        val marks = explorerDecorations(
            "/w",
            listOf(entry("src/a.py", GitFileStatus.Modified), entry("src/new.py", GitFileStatus.Untracked), entry("b.py", GitFileStatus.Conflicted)),
            mapOf("/w/src/a.py" to (2 to 1), "/w/lib/c.py" to (0 to 3)),
        )
        assertEquals(FileDecoration(DecorationTone.Error, "M", 3), marks["/w/src/a.py"])
        assertEquals(FileDecoration(DecorationTone.Untracked, "U"), marks["/w/src/new.py"])
        assertEquals(FileDecoration(DecorationTone.Conflict, "!"), marks["/w/b.py"])
        assertEquals(FileDecoration(DecorationTone.Warning, null, 3), marks["/w/lib/c.py"])
        assertEquals(FileDecoration(DecorationTone.Error, folder = true), marks["/w/src"])
        assertEquals(FileDecoration(DecorationTone.Warning, folder = true), marks["/w/lib"])
        assertNull(marks["/w"])
    }

    @Test
    fun eachKindCanBeTurnedOff() {
        val git = listOf(entry("a.py", GitFileStatus.Modified))
        val problems = mapOf("/w/a.py" to (1 to 0))
        assertEquals(FileDecoration(DecorationTone.Error, null, 1), explorerDecorations("/w", git, problems, showGit = false)["/w/a.py"])
        assertEquals(FileDecoration(DecorationTone.Modified, "M"), explorerDecorations("/w", git, problems, showProblems = false)["/w/a.py"])
    }
}
