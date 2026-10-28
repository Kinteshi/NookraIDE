package com.avfstudio.nativeide.document

import com.avfstudio.nativeide.transport.FileEntry
import com.avfstudio.nativeide.transport.GuestVfs
import com.avfstudio.nativeide.transport.GuestVfsException
import com.avfstudio.nativeide.transport.IncomingPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

class DocumentRepositoryTest {
    @Test
    fun offlineEditsSurviveReconnectAndSaveWithOptimisticTimestamp() {
        val vfs = FakeGuestVfs("hello\n".toByteArray(), 10)
        val cache = MemoryDocumentCache()
        val repository = DocumentRepository(vfs, cache)
        val document = (repository.open("/workspace/main.py").join() as OpenDocument.Editable).document
        document.updateText("hello world\n")
        vfs.connected = false

        assertEquals(SaveResult.QueuedOffline, repository.save(document).join())
        assertEquals("hello world\n", cache.load(document.path)?.text)

        vfs.connected = true
        val reopened = (repository.open(document.path).join() as OpenDocument.Editable).document
        assertTrue(reopened.dirty)
        assertEquals("hello world\n", reopened.text)
        assertEquals(SaveResult.Saved(11), repository.reconcile(reopened).join())
        assertEquals("hello world\n", vfs.contents.toString(StandardCharsets.UTF_8))
        assertFalse(reopened.dirty)
    }

    @Test
    fun reconnectSurfacesExternalChangesInsteadOfOverwritingThem() {
        val vfs = FakeGuestVfs("old\n".toByteArray(), 20)
        val repository = DocumentRepository(vfs, MemoryDocumentCache())
        val document = (repository.open("/workspace/main.py").join() as OpenDocument.Editable).document
        document.updateText("local edit\n")
        vfs.connected = false
        assertEquals(SaveResult.QueuedOffline, repository.save(document).join())

        vfs.replaceExternally("remote edit\n".toByteArray(), 21)
        vfs.connected = true
        val reopened = (repository.open(document.path).join() as OpenDocument.Editable).document
        assertTrue(reopened.conflict != null)
        val result = repository.reconcile(reopened).join()
        assertTrue(result is SaveResult.Conflict)
        assertEquals("remote edit\n", reopened.conflict?.remoteText)
        assertEquals("local edit\n", reopened.text)

        assertEquals(SaveResult.Saved(22), repository.keepLocalAfterConflict(reopened).join())
        assertEquals("local edit\n", vfs.contents.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun openingFiftyMegabyteFileReadsOnlyTheVisiblePageAndKeepsItReadOnly() {
        val vfs = FakeGuestVfs(ByteArray(0), 30, LARGE_FILE_PAGE_SIZE.toLong() * 50)
        val repository = DocumentRepository(vfs, MemoryDocumentCache())

        val opened = repository.open("/workspace/server.log").join() as OpenDocument.Paged

        assertEquals(50, opened.document.pageCount)
        assertEquals(LARGE_FILE_PAGE_SIZE, opened.document.text.length)
        assertEquals(listOf(0L to LARGE_FILE_PAGE_SIZE + 3), vfs.readRequests)
        val next = repository.page(opened.document.path, opened.document, 1).join()
        assertEquals(1, next.pageIndex)
        assertEquals(LARGE_FILE_PAGE_SIZE.toLong(), next.offset)
        assertEquals(2, vfs.readRequests.size)
        assertEquals(LARGE_FILE_PAGE_SIZE.toLong() - 3, vfs.readRequests.last().first)
    }

    @Test
    fun pagesDoNotSplitOrDuplicateUtf8CharactersAtBoundaries() {
        val pageSize = LARGE_FILE_PAGE_SIZE
        val content = ByteArray(pageSize * 17) { 'a'.code.toByte() }
        val emoji = "🙂".toByteArray(StandardCharsets.UTF_8)
        emoji.copyInto(content, pageSize - 2)
        val vfs = FakeGuestVfs(content, 31)
        val repository = DocumentRepository(vfs, MemoryDocumentCache())

        val first = (repository.open("/workspace/server.log").join() as OpenDocument.Paged).document
        val second = repository.page(first.path, first, 1).join()

        assertTrue(first.text.endsWith("🙂"))
        assertEquals(
            String(content.copyOfRange(0, pageSize * 2), StandardCharsets.UTF_8),
            first.text + second.text,
        )
    }

    @Test
    fun draftCacheRetainsExplicitLineEndingChoice() {
        val cache = MemoryDocumentCache()
        val repository = DocumentRepository(FakeGuestVfs(byteArrayOf(), 32), cache)
        val document = DocumentSession("/workspace/empty.txt", "", 32, dirty = false, eol = "\r\n")

        repository.persistDraft(document)

        assertEquals("\r\n", cache.load(document.path)?.eol)
    }

    @Test
    fun editVersionsOnlyAdvanceWhenTextChanges() {
        val document = DocumentSession("/workspace/a.py", "a", 1, dirty = false)
        document.updateText("a")
        assertEquals(1, document.version)
        assertFalse(document.dirty)

        document.updateText("b")
        assertEquals(2, document.version)
        assertTrue(document.dirty)
        assertEquals("py", document.languageId)
    }

    @Test
    fun detectsLatin1AndPreservesItWhenSaving() {
        val vfs = FakeGuestVfs(byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte()), 40)
        val repository = DocumentRepository(vfs, MemoryDocumentCache())

        val document = (repository.open("/workspace/readme.txt").join() as OpenDocument.Editable).document
        assertEquals("caf\u00e9", document.text)
        assertEquals("ISO-8859-1", document.encoding)
        document.updateText("caf\u00e9!")

        assertEquals(SaveResult.Saved(41), repository.save(document).join())
        assertEquals(
            listOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), '!'.code.toByte()).toList(),
            vfs.contents.toList(),
        )
    }

    @Test
    fun detectsUtf16LittleEndianBomAndKeepsItOnSave() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'x'.code.toByte(), 0, '\n'.code.toByte(), 0)
        val vfs = FakeGuestVfs(bytes, 50)
        val repository = DocumentRepository(vfs, MemoryDocumentCache())

        val document = (repository.open("/workspace/readme.txt").join() as OpenDocument.Editable).document

        assertEquals("UTF-16LE", document.encoding)
        document.updateText(document.text + "y")
        assertEquals(SaveResult.Saved(51), repository.save(document).join())
        assertTrue(vfs.contents.copyOfRange(0, 2).contentEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        assertEquals("\uFEFFx\ny", String(vfs.contents, Charsets.UTF_16LE))
    }

    @Test
    fun editingBackToTheSavedTextIsClean() {
        val document = DocumentSession("/w/a.py", "print(1)\n", baseModifiedTs = 1, dirty = false)
        document.updateText("print(12)\n")
        assertTrue(document.dirty)
        document.updateText("print(1)\n")
        assertFalse(document.dirty)
        document.markSaved("print(2)\n")
        assertTrue(document.dirty)
        // A restored draft never matched a known saved text: it stays unsaved until saved.
        val draft = DocumentSession("/w/b.py", "x", baseModifiedTs = 1, dirty = true)
        draft.updateText("xy")
        draft.updateText("x")
        assertTrue(draft.dirty)
    }
}

private class MemoryDocumentCache : DocumentCache {
    private val snapshots = mutableMapOf<String, DocumentSnapshot>()
    override fun load(path: String): DocumentSnapshot? = snapshots[path]
    override fun save(snapshot: DocumentSnapshot) {
        snapshots[snapshot.path] = snapshot
    }
    override fun delete(path: String) {
        snapshots.remove(path)
    }
}

private class FakeGuestVfs(
    initialContent: ByteArray,
    initialTimestamp: Long,
    private var declaredSize: Long = initialContent.size.toLong(),
) : GuestVfs {
    var connected = true
    var contents = initialContent
        private set
    private var modifiedTs = initialTimestamp
    private var stagedBytes: ByteArray? = null
    val readRequests = mutableListOf<Pair<Long, Int>>()

    override fun connect(url: String, token: String): CompletableFuture<Unit> {
        connected = true
        return CompletableFuture.completedFuture(Unit)
    }

    override fun roots() = completed(listOf("/workspace"))
    override fun listDir(path: String) = completed(emptyList<FileEntry>())
    override fun stat(path: String) =
        completed(FileEntry(path.substringAfterLast('/'), path, false, declaredSize, modifiedTs))

    override fun read(path: String, offset: Long, length: Int): CompletableFuture<IncomingPacket.ReadFile> {
        if (!connected) return CompletableFuture.failedFuture(IllegalStateException("disconnected"))
        readRequests += offset to length
        val end = minOf(offset + length, declaredSize)
        val bytes = if (declaredSize > contents.size) {
            ByteArray((end - offset).toInt()) { (('a'.code + ((offset + it) % 26)).toInt()).toByte() }
        } else {
            contents.copyOfRange(offset.toInt(), end.toInt())
        }
        return completed(
            IncomingPacket.ReadFile(offset.let { 0 }, bytes, end >= declaredSize, modifiedTs, declaredSize),
        )
    }

    override fun write(
        path: String,
        offset: Long,
        content: ByteArray,
        truncate: Boolean,
        expectedModifiedTs: Long?,
    ): CompletableFuture<IncomingPacket.Ack> {
        if (!connected) return CompletableFuture.failedFuture(IllegalStateException("disconnected"))
        if (truncate && expectedModifiedTs != null && expectedModifiedTs != modifiedTs) {
            return CompletableFuture.failedFuture(GuestVfsException("Conflict", "remote content changed"))
        }
        val current = if (truncate) ByteArray(0) else stagedBytes ?: ByteArray(0)
        stagedBytes = current + content
        return completed(IncomingPacket.Ack(1, null))
    }

    override fun flush(path: String): CompletableFuture<IncomingPacket.Ack> {
        contents = stagedBytes ?: ByteArray(0)
        stagedBytes = null
        modifiedTs += 1
        return completed(IncomingPacket.Ack(1, modifiedTs))
    }

    override fun isConnected() = connected

    fun replaceExternally(bytes: ByteArray, timestamp: Long) {
        contents = bytes
        declaredSize = bytes.size.toLong()
        modifiedTs = timestamp
    }

    private fun <T> completed(value: T) = CompletableFuture.completedFuture(value)
}
