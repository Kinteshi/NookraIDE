package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.transport.FileEntry
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickOpenWalkTest {
    /** A guest whose listings answer only when the test says, to see how many are asked at once. */
    private class Guest(val tree: Map<String, List<FileEntry>>) {
        val open = ArrayDeque<Pair<String, CompletableFuture<List<FileEntry>>>>()
        var most = 0

        fun list(path: String): CompletableFuture<List<FileEntry>> {
            val answer = CompletableFuture<List<FileEntry>>()
            open.addLast(path to answer)
            most = maxOf(most, open.size)
            return answer
        }

        fun answerAll() {
            while (open.isNotEmpty()) {
                val (path, answer) = open.removeFirst()
                answer.complete(tree[path].orEmpty())
            }
        }
    }

    private fun file(path: String) = entry(path, false)
    private fun folder(path: String) = entry(path, true)

    private fun entry(path: String, directory: Boolean) = FileEntry(path.substringAfterLast('/'), path, directory, 0, 0)

    private fun walk(guest: Guest, max: Int = 1000, done: (List<FileEntry>, Boolean) -> Unit) = QuickOpenWalk(
        list = guest::list,
        isExcluded = { it.name == "secret.txt" },
        walksInto = { it.name != "node_modules" },
        maxFiles = max,
        onProgress = {},
        onDone = done,
    )

    @Test
    fun listsTwoFoldersAtATimeAndFindsEverything() {
        val folders = (0 until 50).map { "/w/d$it" }
        val tree = mapOf("/w" to folders.map(::folder) + folder("/w/node_modules") + file("/w/secret.txt")) +
            folders.associateWith { listOf(file("$it/a.py")) }
        val guest = Guest(tree)
        var found: List<FileEntry>? = null
        var more = true
        walk(guest) { files, truncated -> found = files; more = truncated }.start("/w")
        while (guest.open.isNotEmpty()) guest.answerAll()
        assertEquals(2, guest.most)
        assertEquals(50, found?.size)
        assertFalse(more)
    }

    @Test
    fun nearestFoldersFirstAndStopsAtTheLimit() {
        val tree = mapOf(
            "/w" to listOf(folder("/w/deep"), file("/w/top.py")),
            "/w/deep" to (0 until 10).map { file("/w/deep/f$it.py") },
        )
        val guest = Guest(tree)
        var found: List<FileEntry>? = null
        var more = false
        walk(guest, max = 5) { files, truncated -> found = files; more = truncated }.start("/w")
        while (guest.open.isNotEmpty()) guest.answerAll()
        assertEquals("/w/top.py", found?.first()?.path)
        assertEquals(5, found?.size)
        assertTrue(more)
    }

    @Test
    fun aHugeFolderDoesNotHideASmallOne() {
        val huge = (0 until 50).map { "/w/huge/p$it" }
        val tree = mapOf(
            "/w" to listOf(folder("/w/huge"), folder("/w/small")),
            "/w/huge" to huge.map(::folder),
            "/w/small" to listOf(folder("/w/small/deep")),
            "/w/small/deep" to listOf(file("/w/small/deep/mine.py")),
        ) + huge.associateWith { p -> (0 until 10).map { file("$p/f$it.py") } }
        val guest = Guest(tree)
        var found: List<FileEntry>? = null
        var more = false
        walk(guest, max = 30) { files, truncated -> found = files; more = truncated }.start("/w")
        while (guest.open.isNotEmpty()) guest.answerAll()
        assertTrue(found!!.any { it.name == "mine.py" })
        assertTrue(more)
    }

    @Test
    fun aCancelledWalkStaysQuiet() {
        val guest = Guest(mapOf("/w" to listOf(file("/w/a.py"))))
        var done = false
        val walk = walk(guest) { _, _ -> done = true }
        walk.start("/w")
        walk.cancel()
        guest.answerAll()
        assertFalse(done)
    }
}
