package com.avfstudio.nativeide.document

import com.avfstudio.nativeide.transport.GuestVfs
import com.avfstudio.nativeide.transport.GuestVfsException
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.CharBuffer
import java.util.concurrent.CompletableFuture
import kotlin.math.ceil

const val MAX_EDITABLE_FILE_SIZE = 16L * 1024 * 1024
const val MAX_PAGED_FILE_SIZE = 256L * 1024 * 1024
const val LARGE_FILE_PAGE_SIZE = 1024 * 1024
const val VFS_CHUNK_SIZE = 4 * 1024 * 1024

data class DocumentSnapshot(
    val path: String,
    val text: String,
    val modifiedTs: Long?,
    val dirty: Boolean,
    val encoding: String = "UTF-8",
    val eol: String = if (text.contains("\r\n")) "\r\n" else "\n",
)

interface DocumentCache {
    fun load(path: String): DocumentSnapshot?
    fun save(snapshot: DocumentSnapshot)
    fun delete(path: String)
    /** Whether a copy of [path] is kept, without reading it. */
    fun contains(path: String): Boolean = load(path) != null
}

sealed interface OpenDocument {
    data class Editable(val document: DocumentSession) : OpenDocument
    data class Paged(val document: PagedDocument) : OpenDocument
}

data class DocumentSession(
    val path: String,
    var text: String,
    var baseModifiedTs: Long?,
    var dirty: Boolean,
    var version: Long = 1,
    var conflict: RemoteConflict? = null,
    var encoding: String = "UTF-8",
    var eol: String = if (text.contains("\r\n")) "\r\n" else "\n",
    /** Shown but not held as text (an image in the viewer): never cached, saved or sent to servers. */
    val binary: Boolean = false,
) {
    /** Chosen in the status bar (or converted to); wins over detection and the settings. */
    var indentation: com.avfstudio.nativeide.editor.Indentation? = null
    /** What `editor.detectIndentation` found when the file opened; null when nothing was indented. */
    var detectedIndentation: com.avfstudio.nativeide.editor.Indentation? = null
    var indentationDetected = false
    val languageId: String = path.substringAfterLast('.', "").lowercase()
    val expectedModifiedTs: Long? get() = baseModifiedTs
    /** The text as last loaded or saved, when known: editing back to it (undo) clears [dirty], as in VS Code. */
    var savedText: String? = if (dirty) null else text

    fun updateText(nextText: String) {
        if (text != nextText) {
            text = nextText
            val firstLineBreak = nextText.indexOf('\n')
            if (firstLineBreak >= 0) eol = if (firstLineBreak > 0 && nextText[firstLineBreak - 1] == '\r') "\r\n" else "\n"
            version += 1
            dirty = nextText != savedText
        }
    }

    /** Records that [savedText] reached the guest (or the local draft); later edits make it dirty again. */
    fun markSaved(savedText: String) {
        this.savedText = savedText
        dirty = text != savedText
    }
}

data class PagedDocument(
    val path: String,
    val size: Long,
    val modifiedTs: Long,
    val offset: Long,
    val text: String,
    val pageIndex: Int,
    val pageCount: Int,
    val encoding: String = "UTF-8",
) {
    val canGoPrevious: Boolean get() = pageIndex > 0
    val canGoNext: Boolean get() = pageIndex + 1 < pageCount
}

data class RemoteConflict(
    val remoteText: String,
    val remoteModifiedTs: Long,
    val remoteEncoding: String = "UTF-8",
)

data class DecodedDocument(val text: String, val encoding: String)

sealed interface SaveResult {
    data class Saved(val modifiedTs: Long) : SaveResult
    data object QueuedOffline : SaveResult
    data class Conflict(val localText: String, val remote: RemoteConflict) : SaveResult
}

class DocumentRepository(
    private val vfs: GuestVfs,
    private val cache: DocumentCache,
    private val deviceFiles: DeviceFileStore? = null,
) {
    fun open(path: String): CompletableFuture<OpenDocument> =
        if (isDevicePath(path)) openDeviceFile(path) else openGuestFile(path)

    private fun openGuestFile(path: String): CompletableFuture<OpenDocument> =
        vfs.stat(path).thenCompose { entry ->
            if (entry.isDirectory) {
                return@thenCompose CompletableFuture.failedFuture(
                    IllegalArgumentException("Cannot open a directory as a document"),
                )
            }
            if (entry.size > MAX_PAGED_FILE_SIZE) {
                return@thenCompose CompletableFuture.failedFuture(
                    IOException("File exceeds the 256 MiB paged-file limit"),
                )
            }
            if (entry.size > MAX_EDITABLE_FILE_SIZE) {
                return@thenCompose readPage(path, entry.size, entry.modifiedTs, 0L).thenApply {
                    OpenDocument.Paged(it)
                }
            }

            val cached = cache.load(path)
            if (cached?.dirty == true) {
                val document = DocumentSession(
                    path = path,
                    text = cached.text,
                    baseModifiedTs = cached.modifiedTs,
                    dirty = true,
                    encoding = cached.encoding,
                    eol = cached.eol,
                )
                if (cached.modifiedTs != entry.modifiedTs) {
                    return@thenCompose readAll(path, entry.size).thenApply { remote ->
                        document.conflict = RemoteConflict(remote.text, entry.modifiedTs, remote.encoding)
                        OpenDocument.Editable(document)
                    }
                }
                return@thenCompose CompletableFuture.completedFuture(OpenDocument.Editable(document))
            }

            readAll(path, entry.size).thenApply { decoded ->
                val document = DocumentSession(
                    path,
                    decoded.text,
                    entry.modifiedTs,
                    dirty = false,
                    encoding = decoded.encoding,
                    eol = if (decoded.text.contains("\r\n")) "\r\n" else "\n",
                )
                cache.save(
                    DocumentSnapshot(
                        path,
                        decoded.text,
                        entry.modifiedTs,
                        dirty = false,
                        encoding = decoded.encoding,
                        eol = document.eol,
                    ),
                )
                OpenDocument.Editable(document)
            }
        }.exceptionallyCompose { error ->
            val cached = cache.load(path)
            if (cached == null) {
                CompletableFuture.failedFuture(unwrap(error))
            } else {
                CompletableFuture.completedFuture(
                    OpenDocument.Editable(
                        DocumentSession(
                            path = cached.path,
                            text = cached.text,
                            baseModifiedTs = cached.modifiedTs,
                            dirty = cached.dirty,
                            encoding = cached.encoding,
                            eol = cached.eol,
                        ),
                    ),
                )
            }
        }

    fun save(document: DocumentSession): CompletableFuture<SaveResult> {
        val charset = when (document.encoding.uppercase()) {
            "ISO-8859-1" -> StandardCharsets.ISO_8859_1
            "UTF-16LE" -> StandardCharsets.UTF_16LE
            "UTF-16BE" -> StandardCharsets.UTF_16BE
            else -> StandardCharsets.UTF_8
        }
        val savingText = document.text
        val bytes = try {
            val encoded = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(savingText))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (error: java.nio.charset.CharacterCodingException) {
            return CompletableFuture.failedFuture(
                IOException("Document contains characters that cannot be represented as ${document.encoding}", error),
            )
        }
        if (bytes.size > MAX_EDITABLE_FILE_SIZE) {
            return CompletableFuture.failedFuture(
                IOException("Editable files are limited to 16 MiB; large files are read-only and paged"),
            )
        }

        cache.save(
            DocumentSnapshot(
                document.path,
                document.text,
                document.baseModifiedTs,
                dirty = true,
                encoding = document.encoding,
                eol = document.eol,
            ),
        )
        if (isDevicePath(document.path)) return saveDeviceFile(document, savingText, bytes)
        if (!vfs.isConnected()) {
            return CompletableFuture.completedFuture(SaveResult.QueuedOffline)
        }
        return writeAll(document.path, bytes, document.baseModifiedTs)
            .thenApply<SaveResult> { modifiedTs ->
                document.baseModifiedTs = modifiedTs
                document.markSaved(savingText)
                document.conflict = null
                cache.save(
                    DocumentSnapshot(
                        document.path,
                        document.text,
                        modifiedTs,
                        // Typing that went on during the write stays unsaved.
                        dirty = document.dirty,
                        encoding = document.encoding,
                        eol = document.eol,
                    ),
                )
                SaveResult.Saved(modifiedTs)
            }
            .exceptionallyCompose { error ->
                val cause = unwrap(error)
                if (cause is GuestVfsException && cause.code == "Conflict") {
                    loadRemoteConflict(document).thenApply<SaveResult> { conflict ->
                        document.conflict = conflict
                        SaveResult.Conflict(document.text, conflict)
                    }
                } else if (cause is IOException) {
                    CompletableFuture.completedFuture<SaveResult>(SaveResult.QueuedOffline)
                } else {
                    CompletableFuture.failedFuture<SaveResult>(cause)
                }
            }
    }

    /** Whether this device keeps a copy of [path], so it opens without the guest. */
    fun hasCopy(path: String): Boolean = cache.contains(path)

    /** A whole file's bytes, for files the editor doesn't open (preview images); fails past [limit]. */
    fun readBytes(path: String, limit: Int): CompletableFuture<ByteArray> {
        if (isDevicePath(path)) return readDeviceBytes(path, limit)
        return vfs.stat(path).thenCompose { entry ->
            if (entry.isDirectory || entry.size > limit) {
                return@thenCompose CompletableFuture.failedFuture<ByteArray>(IOException("$path is not a file under ${limit / (1024 * 1024)} MiB"))
            }
            val output = ByteArrayOutputStream(entry.size.toInt())
            fun readNext(offset: Long): CompletableFuture<ByteArray> =
                vfs.read(path, offset, minOf(VFS_CHUNK_SIZE.toLong(), entry.size - offset).toInt().coerceAtLeast(1)).thenCompose { response ->
                    output.write(response.content)
                    val next = offset + response.content.size
                    when {
                        response.eof || next >= entry.size -> CompletableFuture.completedFuture(output.toByteArray())
                        response.content.isEmpty() -> CompletableFuture.failedFuture(IOException("Guest returned an empty non-final file chunk"))
                        else -> readNext(next)
                    }
                }
            readNext(0)
        }
    }

    private fun readDeviceBytes(path: String, limit: Int): CompletableFuture<ByteArray> {
        val store = deviceFiles ?: return CompletableFuture.failedFuture(IOException("Device files can't be read here"))
        val uri = deviceUri(path) ?: return CompletableFuture.failedFuture(IllegalArgumentException("Not a device file: $path"))
        return CompletableFuture.supplyAsync {
            store.read(uri).also { if (it.size > limit) throw IOException("$path is over ${limit / (1024 * 1024)} MiB") }
        }
    }

    /**
     * A device file, read through its content URI. An unsaved draft wins, as for guest files;
     * without access any more (the file moved, the permission went), the last copy opens.
     */
    private fun openDeviceFile(path: String): CompletableFuture<OpenDocument> {
        val store = deviceFiles ?: return CompletableFuture.failedFuture(IOException("Device files can't be opened here"))
        val uri = deviceUri(path) ?: return CompletableFuture.failedFuture(IllegalArgumentException("Not a device file: $path"))
        return CompletableFuture.supplyAsync {
            val cached = cache.load(path)
            if (cached?.dirty == true) {
                return@supplyAsync OpenDocument.Editable(
                    DocumentSession(path, cached.text, null, dirty = true, encoding = cached.encoding, eol = cached.eol),
                )
            }
            val read = runCatching { store.read(uri) }
            val bytes = read.getOrNull()
            when {
                bytes != null -> {
                    if (bytes.size > MAX_EDITABLE_FILE_SIZE) throw IOException("Device files over 16 MiB can't be opened")
                    val decoded = decodeDocument(bytes)
                    val eol = if (decoded.text.contains("\r\n")) "\r\n" else "\n"
                    cache.save(DocumentSnapshot(path, decoded.text, null, dirty = false, encoding = decoded.encoding, eol = eol))
                    OpenDocument.Editable(DocumentSession(path, decoded.text, null, dirty = false, encoding = decoded.encoding, eol = eol))
                }
                cached != null -> OpenDocument.Editable(
                    DocumentSession(path, cached.text, null, dirty = false, encoding = cached.encoding, eol = cached.eol),
                )
                else -> throw read.exceptionOrNull() ?: IOException("Can't read $path")
            }
        }
    }

    private fun saveDeviceFile(document: DocumentSession, savingText: String, bytes: ByteArray): CompletableFuture<SaveResult> {
        val store = deviceFiles ?: return CompletableFuture.failedFuture(IOException("Device files can't be saved here"))
        val uri = deviceUri(document.path) ?: return CompletableFuture.failedFuture(IllegalArgumentException("Not a device file"))
        return CompletableFuture.supplyAsync {
            store.write(uri, bytes)
            document.markSaved(savingText)
            cache.save(DocumentSnapshot(document.path, document.text, null, dirty = document.dirty, encoding = document.encoding, eol = document.eol))
            SaveResult.Saved(System.currentTimeMillis())
        }
    }

    /** Drops the cached draft for a document that was renamed, deleted or saved elsewhere. */
    fun forget(path: String) {
        cache.delete(path)
    }

    fun persistDraft(document: DocumentSession) {
        if (document.binary) return
        cache.save(
            DocumentSnapshot(
                document.path,
                document.text,
                document.baseModifiedTs,
                dirty = document.dirty,
                encoding = document.encoding,
                eol = document.eol,
            ),
        )
    }

    fun reconcile(document: DocumentSession): CompletableFuture<SaveResult> {
        if (!document.dirty) return CompletableFuture.completedFuture(SaveResult.Saved(document.baseModifiedTs ?: 0L))
        if (!vfs.isConnected()) return CompletableFuture.completedFuture(SaveResult.QueuedOffline)
        return vfs.stat(document.path).thenCompose<SaveResult> { entry ->
            if (document.baseModifiedTs != null && entry.modifiedTs != document.baseModifiedTs) {
                loadRemoteConflict(document).thenApply<SaveResult> { conflict ->
                    document.conflict = conflict
                    SaveResult.Conflict(document.text, conflict)
                }
            } else {
                save(document)
            }
        }
    }

    fun keepLocalAfterConflict(document: DocumentSession): CompletableFuture<SaveResult> {
        val remote = document.conflict
            ?: return CompletableFuture.failedFuture(IllegalStateException("Document has no pending conflict"))
        document.baseModifiedTs = remote.remoteModifiedTs
        return save(document)
    }

    fun discardLocalForRemote(document: DocumentSession): CompletableFuture<DocumentSession> {
        val remote = document.conflict
            ?: return CompletableFuture.failedFuture(IllegalStateException("Document has no pending conflict"))
        document.text = remote.remoteText
        document.baseModifiedTs = remote.remoteModifiedTs
        document.encoding = remote.remoteEncoding
        document.eol = if (remote.remoteText.contains("\r\n")) "\r\n" else "\n"
        document.markSaved(remote.remoteText)
        document.conflict = null
        cache.save(
            DocumentSnapshot(
                document.path,
                document.text,
                document.baseModifiedTs,
                dirty = false,
                encoding = document.encoding,
                eol = document.eol,
            ),
        )
        return CompletableFuture.completedFuture(document)
    }

    fun page(path: String, current: PagedDocument, direction: Int): CompletableFuture<PagedDocument> {
        val nextIndex = (current.pageIndex + direction).coerceIn(0, current.pageCount - 1)
        val nextOffset = nextIndex.toLong() * LARGE_FILE_PAGE_SIZE
        return readPage(path, current.size, current.modifiedTs, nextOffset, current.encoding)
    }

    private fun readAll(path: String, size: Long): CompletableFuture<DecodedDocument> {
        val output = ByteArrayOutputStream(size.toInt())
        var offset = 0L
        fun readNext(): CompletableFuture<Unit> {
            if (offset >= size) return CompletableFuture.completedFuture(Unit)
            val amount = minOf(VFS_CHUNK_SIZE.toLong(), size - offset).toInt()
            return vfs.read(path, offset, amount).thenCompose { response ->
                output.write(response.content)
                offset += response.content.size
                if (response.content.isEmpty() && !response.eof) {
                    return@thenCompose CompletableFuture.failedFuture(IOException("Guest returned an empty non-final file chunk"))
                }
                if (response.eof) CompletableFuture.completedFuture(Unit) else readNext()
            }
        }
        return readNext().thenApply {
            decodeDocument(output.toByteArray())
        }
    }

    private fun readPage(
        path: String,
        size: Long,
        modifiedTs: Long,
        offset: Long,
        knownEncoding: String? = null,
    ): CompletableFuture<PagedDocument> {
        val overlap = if (knownEncoding == null || knownEncoding == "UTF-8") 3L else 0L
        val readOffset = (offset - overlap).coerceAtLeast(0L)
        val length = minOf(
            LARGE_FILE_PAGE_SIZE.toLong() + (offset - readOffset) + overlap,
            size - readOffset,
        ).coerceAtLeast(1L).toInt()
        return vfs.read(path, readOffset, length).thenApply { response ->
            val pages = ceil(size.toDouble() / LARGE_FILE_PAGE_SIZE).toInt().coerceAtLeast(1)
            val decoded = decodePage(response.content, readOffset, offset, size, knownEncoding)
            PagedDocument(
                path = path,
                size = size,
                modifiedTs = response.modifiedTs,
                offset = offset,
                text = decoded.text,
                pageIndex = (offset / LARGE_FILE_PAGE_SIZE).toInt(),
                pageCount = pages,
                encoding = decoded.encoding,
            )
        }
    }

    /** Writes [bytes] as [path]'s whole content, in chunks the daemon takes, then flushes it. */
    fun writeAll(path: String, bytes: ByteArray, expectedModifiedTs: Long?): CompletableFuture<Long> {
        if (bytes.isEmpty()) {
            return vfs.write(path, 0L, byteArrayOf(), truncate = true, expectedModifiedTs)
                .thenCompose { vfs.flush(path) }
                .thenApply { it.modifiedTs ?: 0L }
        }
        var offset = 0
        fun writeNext(): CompletableFuture<Long> {
            if (offset >= bytes.size) {
                return vfs.flush(path).thenApply { it.modifiedTs ?: 0L }
            }
            val end = minOf(offset + VFS_CHUNK_SIZE, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val first = offset == 0
            return vfs.write(
                path = path,
                offset = offset.toLong(),
                content = chunk,
                truncate = first,
                expectedModifiedTs = if (first) expectedModifiedTs else null,
            ).thenCompose {
                offset = end
                writeNext()
            }
        }
        return writeNext()
    }

    private fun loadRemoteConflict(document: DocumentSession): CompletableFuture<RemoteConflict> =
        vfs.stat(document.path).thenCompose { entry ->
            if (entry.size > MAX_EDITABLE_FILE_SIZE) {
                CompletableFuture.failedFuture(
                    IOException("Conflicting remote file is too large to merge as an editable document"),
                )
            } else {
                readAll(document.path, entry.size).thenApply {
                    RemoteConflict(it.text, entry.modifiedTs, it.encoding)
                }
            }
        }

    private fun unwrap(error: Throwable): Throwable {
        var cause = error
        while ((cause is java.util.concurrent.CompletionException || cause is java.util.concurrent.ExecutionException) &&
            cause.cause != null
        ) {
            cause = cause.cause!!
        }
        return cause
    }

    private fun decodeDocument(bytes: ByteArray): DecodedDocument {
        val (charset, encoding) = when {
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                StandardCharsets.UTF_16LE to "UTF-16LE"
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                StandardCharsets.UTF_16BE to "UTF-16BE"
            else -> StandardCharsets.UTF_8 to "UTF-8"
        }
        if (encoding != "UTF-16LE" && encoding != "UTF-16BE" && bytes.any { it == 0.toByte() }) {
            throw IOException("Binary files cannot be opened as text documents")
        }
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            DecodedDocument(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString(), encoding)
        } catch (_: java.nio.charset.CharacterCodingException) {
            if (encoding != "UTF-8") throw IOException("Text file encoding is invalid")
            DecodedDocument(
                StandardCharsets.ISO_8859_1.decode(java.nio.ByteBuffer.wrap(bytes)).toString(),
                "ISO-8859-1",
            )
        }
    }

    private fun decodePage(
        bytes: ByteArray,
        byteStart: Long,
        logicalOffset: Long,
        size: Long,
        knownEncoding: String?,
    ): DecodedDocument {
        val encoding = knownEncoding ?: when {
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> "UTF-16LE"
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> "UTF-16BE"
            else -> "UTF-8"
        }
        val charset = when (encoding) {
            "UTF-16LE" -> StandardCharsets.UTF_16LE
            "UTF-16BE" -> StandardCharsets.UTF_16BE
            "ISO-8859-1" -> StandardCharsets.ISO_8859_1
            else -> StandardCharsets.UTF_8
        }
        if (encoding != "UTF-8") {
            val startIndex = (logicalOffset - byteStart).toInt().coerceIn(0, bytes.size)
            return DecodedDocument(String(bytes, startIndex, bytes.size - startIndex, charset), encoding)
        }

        var startIndex = (logicalOffset - byteStart).toInt().coerceIn(0, bytes.size)
        while (startIndex < bytes.size && isUtf8Continuation(bytes[startIndex])) startIndex++
        val logicalEnd = minOf(size, logicalOffset + LARGE_FILE_PAGE_SIZE) - byteStart
        var endIndex = logicalEnd.toInt().coerceIn(startIndex, bytes.size)
        while (endIndex < bytes.size && isUtf8Continuation(bytes[endIndex])) endIndex++
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return DecodedDocument(
            decoder.decode(java.nio.ByteBuffer.wrap(bytes, startIndex, endIndex - startIndex)).toString(),
            encoding,
        )
    }

    private fun isUtf8Continuation(byte: Byte): Boolean = byte.toInt() and 0xC0 == 0x80

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
