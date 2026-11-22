package io.github.kinteshi.nookraide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffModelsGitTest {
    private val patch = """
        diff --git a/f.txt b/f.txt
        index 1..2 100644
        --- a/f.txt
        +++ b/f.txt
        @@ -1,3 +1,3 @@
         a
        -b
        +B
         c
        @@ -20,3 +20,3 @@
         x
        -y
        +Y
         z
    """.trimIndent() + "\n"

    @Test
    fun cutsAPatchIntoOneHunkPatches() {
        val (header, hunks) = patchHunks(patch)
        assertTrue(header.startsWith("diff --git") && header.endsWith("+++ b/f.txt\n"))
        assertEquals(2, hunks.size)
        val second = singleHunkPatch(patch, 1)!!
        assertTrue(second.startsWith(header))
        assertTrue("+Y" in second && "+B" !in second)
        assertNull(singleHunkPatch(patch, 2))
        assertNull(singleHunkPatch("@@ -1 +1 @@\n-a\n+b\n", 0))
    }

    @Test
    fun readsBlameAndStashes() {
        val hash = "a".repeat(40)
        val blame = """
            $hash 1 1 1
            author Ann
            author-time 1700000000
            summary First
            filename f.txt
            	line one
            ${"0".repeat(40)} 2 2 1
            author Not Committed Yet
            author-time 1700000100
            summary Version of f.txt from f.txt
            	line two
        """.trimIndent()
        val lines = parseBlame(blame)
        assertEquals(BlameLine(hash, "Ann", 1700000000, "First"), lines[0])
        assertTrue(lines.getValue(1).uncommitted)
        assertEquals("3 days ago", relativeTime(1_000_000, now = 1_000_000 + 3 * 86_400 + 5))
        assertEquals(listOf(0 to "On main: wip", 1 to "WIP on main: abc subject"), parseStashList("stash@{0}: On main: wip\nstash@{1}: WIP on main: abc subject\n"))
    }
}
