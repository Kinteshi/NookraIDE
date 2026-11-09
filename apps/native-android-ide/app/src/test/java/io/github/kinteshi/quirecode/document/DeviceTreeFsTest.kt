package io.github.kinteshi.quirecode.document

import io.github.kinteshi.quirecode.transport.SearchQuery
import io.github.kinteshi.quirecode.workbench.isPathWithinRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTreeFsTest {
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FNotes"
    private val root = treeRoot(treeUri)

    @Test
    fun treePathsNameTheirFolderAndThePathBelowIt() {
        assertTrue(isTreePath(root))
        assertFalse(root.removePrefix(TREE_PREFIX).contains('/'))
        val file = "$root/src/main.py"
        assertEquals(root, treeRootOf(file))
        assertEquals(treeUri, treeUriOf(file))
        assertEquals("src/main.py", treeRelative(file))
        assertEquals("", treeRelative(root))
        assertEquals("Notes/src/main.py", treeDisplayPath(file, "Notes"))
        assertEquals("Notes", treeDisplayPath(root, "Notes"))
        assertNull(treeRootOf("/home/droid/Projects/a.py"))
    }

    @Test
    fun deviceFolderFilesArentTheGuests() {
        assertFalse(isGuestPath("$root/a.py"))
        assertTrue(isGuestPath("/home/droid/a.py"))
        assertTrue(isPathWithinRoot("$root/src/a.py", root))
        assertTrue(isPathWithinRoot(root, root))
        assertFalse(isPathWithinRoot("${root}x/a.py", root))
        assertFalse(isPathWithinRoot("$root/a.py", "/home/droid"))
    }

    @Test
    fun globsMatchNamesAndPaths() {
        assertTrue(globMatches("*.py", "src/main.py"))
        assertFalse(globMatches("*.py", "src/main.pyc"))
        assertTrue(globMatches("src/**", "src/a/b.txt"))
        assertTrue(globMatches("node_modules", "web/node_modules/"))
        assertTrue(globMatches("test_?.py", "tests/test_1.py"))
        assertFalse(globMatches("test_?.py", "tests/test_10.py"))
    }

    @Test
    fun searchMatchesLiteralRegexCaseAndWholeWords() {
        fun matches(query: SearchQuery, line: String) = lineMatcher(query)(line)
        assertEquals(listOf(4 to 3, 12 to 3), matches(SearchQuery("foo", listOf(root)), "say foo and FOO"))
        assertEquals(listOf(4 to 3), matches(SearchQuery("foo", listOf(root), caseSensitive = true), "say foo and FOO"))
        assertEquals(listOf(0 to 3), matches(SearchQuery("a.b", listOf(root)), "a.b axb"))
        assertEquals(listOf(0 to 3, 4 to 3), matches(SearchQuery("a.b", listOf(root), regex = true), "a.b axb"))
        assertEquals(listOf(5 to 3), matches(SearchQuery("cat", listOf(root), wholeWord = true), "cats cat"))
    }
}
