package io.github.kinteshi.quirecode.document

import io.github.kinteshi.quirecode.transport.FileEntry
import io.github.kinteshi.quirecode.transport.GuestVfs
import io.github.kinteshi.quirecode.transport.GuestVfsException
import io.github.kinteshi.quirecode.transport.IncomingPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

class DocumentRepositoryTest {
    @Test
    fun deviceFilesOpenAndSaveThroughTheirUriWithoutAGuest() {
        val vfs = FakeGuestVfs(ByteArray(0), 1).apply { connected = false }
        val store = MemoryDeviceFiles(mutableMapOf("content://downloads/7" to "# Notes\r\n".toByteArray()))
        val cache = MemoryDocumentCache()
        val repository = DocumentRepository(vfs, cache, store)
        val path = devicePath("content://downloads/7", "notes.md")
        assertTrue(isDevicePath(path))
        assertFalse(isGuestPath(path))
        assertEquals("notes.md", path.substringAfterLast('/'))
        assertEquals("content://downloads/7", deviceUri(path))

        val document = (repository.open(path).join() as OpenDocument.Editable).document
        assertEquals("# Notes\r\n", document.text)
        assertEquals("\r\n", document.eol)
        document.updateText("# Notes\r\nmore\r\n")
        assertTrue(repository.save(document).join() is SaveResult.Saved)
        assertFalse(document.dirty)
        assertEquals("# Notes\r\nmore\r\n", store.files.getValue("content://downloads/7").toString(StandardCharsets.UTF_8))
        assertTrue(repository.hasCopy(path))
    }

    @Test
    fun aDeviceFileWithoutAccessOpensFromItsLastCopy() {
        val store = MemoryDeviceFiles(mutableMapOf("content://a" to "first\n".toByteArray()))
        val cache = MemoryDocumentCache()
        val repository = DocumentRepository(FakeGuestVfs(ByteArray(0), 1), cache, store)
        val path = devicePath("content://a", "a.txt")
        repository.open(path).join()
        store.files.clear()
        assertEquals("first\n", (repository.open(path).join() as OpenDocument.Editable).document.text)
    }

    @Test
    fun offlineAGuestFileOpensFromItsCopy() {
        val vfs = FakeGuestVfs("cached\n".toByteArray(), 5)
        val repository = DocumentRepository(vfs, MemoryDocumentCache())
        repository.open("/workspace/main.py").join()
        vfs.connected = false
        assertTrue(repository.hasCopy("/workspace/main.py"))
        assertFalse(repository.hasCopy("/workspace/other.py"))
        assertEquals("cached\n", (repository.open("/workspace/main.py").join() as OpenDocument.Editable).document.text)
    }

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
    fun sameGuestPathKeepsCopiesAndDraftsSeparateByConnection() {
        val vfs = FakeGuestVfs("guest-a\n".toByteArray(), 50)
        var connection = "guest-a"
        val cache = MemoryDocumentCache()
        val repository = DocumentRepository(vfs, cache, cacheScopeForPath = { connection })
        val document = (repository.open("/workspace/main.py").join() as OpenDocument.Editable).document
        document.updateText("guest-a edit\n")
        vfs.connected = false
        assertEquals(SaveResult.QueuedOffline, repository.save(document).join())

        connection = "guest-b"
        assertFalse(repository.hasCopy("/workspace/main.py"))
        assertTrue(cache.load("connection:guest-a:/workspace/main.py")?.dirty == true)

        connection = "guest-a"
        assertTrue(repository.hasCopy("/workspace/main.py"))
        assertEquals("guest-a edit\n", (repository.open("/workspace/main.py").join() as OpenDocument.Editable).document.text)
    }

    @Test
    fun aDraftCannotBeWrittenToASecondGuestAtTheSamePath() {
        val vfs = FakeGuestVfs("guest-b\n".toByteArray(), 50)
        var connection = "guest-a"
        val cache = MemoryDocumentCache()
        val repository = DocumentRepository(vfs, cache, cacheScopeForPath = { connection })
        val document = (repository.open("/workspace/main.py").join() as OpenDocument.Editable).document
        document.updateText("guest-a edit\n")
        connection = "guest-b"

        val error = runCatching { repository.save(document).join() }.exceptionOrNull()

        assertTrue(error != null)
        assertEquals("guest-b\n", vfs.contents.toString(StandardCharsets.UTF_8))
        assertTrue(cache.load("connection:guest-a:/workspace/main.py")?.dirty == true)
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

    @Test
    fun aViewedBinaryIsDownloadedAndStoredAgainOnlyWhenItChanged() {
        val vfs = FakeGuestVfs(byteArrayOf(0, 1, 2, 3), 10)
        val repo = DocumentRepository(vfs, MemoryDocumentCache())
        val path = "/workspace/logo.png"

        repo.refreshBytesCopy(path, 4, 10).join()
        assertEquals(1, vfs.readRequests.size)
        assertEquals(CopyState.Clean(10L), repo.copyState(path))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), repo.currentCopyBytes(path, 10))

        // Opened again unchanged: no second download.
        repo.refreshBytesCopy(path, 4, 10).join()
        assertEquals(1, vfs.readRequests.size)

        // Changed on the guest: the copy is stale, so it's read and kept again.
        assertNull(repo.currentCopyBytes(path, 20))
        vfs.replaceExternally(byteArrayOf(9, 9), 20)
        repo.refreshBytesCopy(path, 2, 20).join()
        assertEquals(2, vfs.readRequests.size)
        assertArrayEquals(byteArrayOf(9, 9), repo.currentCopyBytes(path, 20))
    }

    @Test
    fun storeCopyKeepsCleanTextFilesAndSkipsBinary() {
        val vfs = FakeGuestVfs("print('hi')\n".toByteArray(), 10)
        val cache = MemoryDocumentCache()
        val repo = DocumentRepository(vfs, cache)
        assertEquals(CopyState.None, repo.copyState("/workspace/main.py"))

        val kept = repo.storeCopy("/workspace/main.py", 12, 10).join()
        assertTrue(kept)
        assertEquals(CopyState.Clean(10L), repo.copyState("/workspace/main.py"))
        assertEquals("print('hi')\n", repo.copyText("/workspace/main.py"))

        // Unsaved local edits are never replaced
        cache.save(DocumentSnapshot("/workspace/main.py", "local edits\n", 10, dirty = true, encoding = "UTF-8", eol = "\n"))
        assertEquals(CopyState.Edited, repo.copyState("/workspace/main.py"))
        assertTrue(repo.storeCopy("/workspace/main.py", 12, 20).join())
        assertEquals("local edits\n", repo.copyText("/workspace/main.py"))

        // forgetCleanCopy leaves dirty edits alone
        repo.forgetCleanCopy("/workspace/main.py")
        assertEquals(CopyState.Edited, repo.copyState("/workspace/main.py"))

        // forgetCleanCopy removes clean copies
        cache.save(DocumentSnapshot("/workspace/other.py", "clean\n", 5, dirty = false, encoding = "UTF-8", eol = "\n"))
        assertEquals(CopyState.Clean(5L), repo.copyState("/workspace/other.py"))
        repo.forgetCleanCopy("/workspace/other.py")
        assertEquals(CopyState.None, repo.copyState("/workspace/other.py"))

        // Binary files with NUL in the first 8 KB are skipped
        vfs.replaceExternally(byteArrayOf(0, 1, 2, 3), 30)
        val binaryKept = repo.storeCopy("/workspace/binary.dat", 4, 30).join()
        assertFalse(binaryKept)
        assertEquals(CopyState.None, repo.copyState("/workspace/binary.dat"))

        // Image files are kept as raw bytes and remain readable offline via readBytes, without polluting text search
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x0A)
        vfs.replaceExternally(pngBytes, 40)
        val imageKept = repo.storeCopy("/workspace/chart.png", pngBytes.size.toLong(), 40).join()
        assertTrue(imageKept)
        assertEquals(CopyState.Clean(40L), repo.copyState("/workspace/chart.png"))
        assertEquals(null, repo.copyText("/workspace/chart.png"))

        vfs.connected = false
        assertTrue(pngBytes.contentEquals(repo.readBytes("/workspace/chart.png", 1024).join()))
        assertEquals("local edits\n", String(repo.readBytes("/workspace/main.py", 1024).join(), Charsets.UTF_8))
    }
}

private class MemoryDeviceFiles(val files: MutableMap<String, ByteArray>) : DeviceFileStore {
    override fun read(uri: String): ByteArray = files[uri] ?: throw java.io.IOException("No access to $uri")
    override fun write(uri: String, bytes: ByteArray) {
        files[uri] = bytes
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
