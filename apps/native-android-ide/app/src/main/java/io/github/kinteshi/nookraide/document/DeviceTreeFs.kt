package io.github.kinteshi.nookraide.document

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.kinteshi.nookraide.transport.FileEntry
import io.github.kinteshi.nookraide.transport.GuestVfsException
import io.github.kinteshi.nookraide.transport.IncomingPacket
import io.github.kinteshi.nookraide.transport.SearchEvent
import io.github.kinteshi.nookraide.transport.SearchQuery
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A folder on the device as a workspace (G29): one picked with Android's folder picker (Documents, a USB drive, a synced
 * folder), worked on without a guest. Its files have paths like a guest's, under a root that names the folder's tree
 * URI: `tree:/<encoded tree URI>/src/main.py`. The rest of the app sees them through the same calls as the guest's
 * files ([RoutingVfs]); terminals, language servers and the rest of what runs in the guest don't apply to them.
 */
const val TREE_PREFIX = "tree:/"

fun isTreePath(path: String): Boolean = path.startsWith(TREE_PREFIX)

/** The workspace root for a picked folder's tree URI. */
fun treeRoot(treeUri: String): String = TREE_PREFIX + URLEncoder.encode(treeUri, StandardCharsets.UTF_8.name())

/** The root of the device folder [path] is in (`tree:/<encoded>`). */
fun treeRootOf(path: String): String? = path.takeIf(::isTreePath)?.let { TREE_PREFIX + it.removePrefix(TREE_PREFIX).substringBefore('/') }

/** The tree URI behind a tree path. */
fun treeUriOf(path: String): String? =
    treeRootOf(path)?.removePrefix(TREE_PREFIX)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }

/** The path below its folder's root ("" for the root itself). */
fun treeRelative(path: String): String = path.removePrefix(treeRootOf(path) ?: return path).trimStart('/')

/**
 * How a tree path reads to people: the folder's name and the path below it (`Notes/src/main.py`). [folderName] is the
 * folder's display name, when known.
 */
fun treeDisplayPath(path: String, folderName: String?): String {
    val name = folderName ?: treeUriOf(path)?.let { Uri.decode(it).substringAfterLast(':').substringAfterLast('/') }?.ifEmpty { null } ?: "Device folder"
    val relative = treeRelative(path)
    return if (relative.isEmpty()) name else "$name/$relative"
}

/** Glob to regex for search include and exclude (`*.py`, `src` with a double star, `test_?.py`): on the name, or the relative path. */
internal fun globMatches(glob: String, relativePath: String): Boolean {
    val pattern = buildString {
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && glob.getOrNull(i + 1) == '*' -> { append(".*"); i++; if (glob.getOrNull(i + 1) == '/') i++ }
                c == '*' -> append("[^/]*")
                c == '?' -> append("[^/]")
                else -> append(Regex.escape(c.toString()))
            }
            i++
        }
    }
    val regex = Regex(pattern)
    val name = relativePath.substringAfterLast('/')
    return regex.matches(relativePath) || regex.matches(name) || relativePath.split('/').dropLast(1).any { regex.matches(it) } ||
        regex.matches(relativePath.trimEnd('/') + "/") || (glob.endsWith("/**") && relativePath.startsWith(glob.removeSuffix("/**") + "/"))
}

/** The search's line matcher: literal or regex, case, whole word; each match's 0-based start and length in the line. */
internal fun lineMatcher(query: SearchQuery): (String) -> List<Pair<Int, Int>> {
    val source = if (query.regex) query.query else Regex.escape(query.query)
    val bounded = if (query.wholeWord) "\\b(?:$source)\\b" else source
    val options = if (query.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
    val regex = Regex(bounded, options)
    return { line -> regex.findAll(line).filter { it.range.last >= it.range.first }.map { it.range.first to it.value.length }.toList() }
}

class DeviceTreeFs(private val resolver: ContentResolver, private val executor: ExecutorService) {
    /** A path's document id, from listing its parent; dropped where the folder changes. */
    private val ids = ConcurrentHashMap<String, String>()
    /** Bytes written since the last flush, per path (writes come in chunks, the file is written at flush). */
    private val staged = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val searches = ConcurrentHashMap<Long, AtomicBoolean>()
    private val searchIds = AtomicLong(1_000_000_000L)

    private fun <T> async(work: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(work, executor)

    private fun treeUri(path: String): Uri = Uri.parse(treeUriOf(path) ?: throw GuestVfsException("InvalidPath", "Not a device folder path"))

    private fun documentUri(path: String, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri(path), id)

    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    private data class Row(val id: String, val name: String, val directory: Boolean, val size: Long, val modified: Long)

    private fun rows(path: String, id: String): List<Row> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri(path), id)
        val cursor = try {
            resolver.query(children, columns, null, null, null)
        } catch (error: SecurityException) {
            throw GuestVfsException("PermissionDenied", "Nookra IDE no longer has access to this folder; open it again")
        } ?: throw IOException("Couldn't list ${treeRelative(path).ifEmpty { "the folder" }}")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        Row(
                            id = it.getString(0),
                            name = it.getString(1) ?: continue,
                            directory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = if (it.isNull(3)) 0 else it.getLong(3),
                            modified = if (it.isNull(4)) 0 else it.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    private fun row(path: String): Row {
        val id = resolve(path)
        val cursor = resolver.query(documentUri(path, id), columns, null, null, null) ?: throw IOException("Couldn't read $path")
        return cursor.use {
            if (!it.moveToFirst()) throw notFound(path)
            Row(it.getString(0), it.getString(1) ?: "", it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (it.isNull(3)) 0 else it.getLong(3), if (it.isNull(4)) 0 else it.getLong(4))
        }
    }

    private fun notFound(path: String) = GuestVfsException("NotFound", "${treeRelative(path).ifEmpty { "The folder" }} doesn't exist")

    /** The document id of [path], listing its parents as needed. */
    private fun resolve(path: String): String {
        val clean = path.trimEnd('/')
        ids[clean]?.let { return it }
        val root = treeRootOf(clean) ?: throw notFound(path)
        if (clean == root) return DocumentsContract.getTreeDocumentId(treeUri(clean)).also { ids[clean] = it }
        val parent = clean.substringBeforeLast('/')
        val parentId = resolve(parent)
        rows(parent, parentId).forEach { ids["$parent/${it.name}"] = it.id }
        return ids[clean] ?: throw notFound(path)
    }

    private fun forget(path: String) {
        val clean = path.trimEnd('/')
        ids.keys.filter { it == clean || it.startsWith("$clean/") }.forEach(ids::remove)
    }

    private fun entry(parent: String, row: Row) =
        FileEntry(row.name, "${parent.trimEnd('/')}/${row.name}", row.directory, row.size, row.modified)

    fun listDir(path: String): CompletableFuture<List<FileEntry>> = async { listNow(path) }

    private fun listNow(path: String): List<FileEntry> {
        val id = resolve(path)
        return rows(path, id).map { row ->
            ids["${path.trimEnd('/')}/${row.name}"] = row.id
            entry(path, row)
        }
    }

    fun stat(path: String): CompletableFuture<FileEntry> = async {
        val row = row(path)
        FileEntry(row.name.ifEmpty { path.substringAfterLast('/') }, path.trimEnd('/'), row.directory, row.size, row.modified)
    }

    fun read(path: String, offset: Long, length: Int): CompletableFuture<IncomingPacket.ReadFile> = async { readNow(path, offset, length) }

    private fun readNow(path: String, offset: Long, length: Int): IncomingPacket.ReadFile {
        val row = row(path)
        if (row.directory) throw GuestVfsException("IsADirectory", "cannot read a directory as a file")
        val content = (resolver.openInputStream(documentUri(path, row.id)) ?: throw IOException("Couldn't open $path")).use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val step = input.skip(offset - skipped)
                if (step <= 0) break
                skipped += step
            }
            val buffer = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val count = input.read(buffer, filled, length - filled)
                if (count < 0) break
                filled += count
            }
            buffer.copyOf(filled)
        }
        val size = maxOf(row.size, offset + content.size)
        return IncomingPacket.ReadFile(0, content, offset + content.size >= size, row.modified, size)
    }

    fun write(path: String, offset: Long, content: ByteArray, truncate: Boolean, expectedModifiedTs: Long?): CompletableFuture<IncomingPacket.Ack> = async {
        if (offset == 0L && expectedModifiedTs != null) {
            val current = runCatching { row(path).modified }.getOrNull()
            // Changed by another app since it was read: the editor's conflict view, as with the guest.
            if (current != null && current != 0L && current != expectedModifiedTs) throw GuestVfsException("Conflict", "changed on the device since it was opened")
        }
        val buffer = if (truncate || offset == 0L) ByteArrayOutputStream().also { staged[path] = it } else staged.getOrPut(path) { ByteArrayOutputStream() }
        buffer.write(content)
        IncomingPacket.Ack(0, null)
    }

    fun flush(path: String): CompletableFuture<IncomingPacket.Ack> = async {
        val bytes = staged.remove(path)?.toByteArray() ?: byteArrayOf()
        val uri = documentUri(path, resolve(path))
        val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: throw IOException("Couldn't write $path")
        stream.use { it.write(bytes) }
        IncomingPacket.Ack(0, runCatching { row(path).modified }.getOrNull())
    }

    private fun exists(path: String): Boolean = runCatching { resolve(path) }.isSuccess

    fun create(path: String, directory: Boolean): CompletableFuture<IncomingPacket.Ack> = async { createNow(path, directory) }

    private fun createNow(path: String, directory: Boolean): IncomingPacket.Ack {
        val clean = path.trimEnd('/')
        forget(clean)
        if (exists(clean)) {
            // As the guest's: a folder is made with its parents, and one that's there already is fine.
            if (directory && row(clean).directory) return IncomingPacket.Ack(0, null)
            throw GuestVfsException("AlreadyExists", "${clean.substringAfterLast('/')} already exists")
        }
        val parent = clean.substringBeforeLast('/')
        if (treeRootOf(parent) != parent && !exists(parent)) createNow(parent, true)
        val name = clean.substringAfterLast('/')
        val mime = if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
        val made = DocumentsContract.createDocument(resolver, documentUri(parent, resolve(parent)), mime, name)
            ?: throw IOException("Couldn't make $name")
        ids[clean] = DocumentsContract.getDocumentId(made)
        return IncomingPacket.Ack(0, null)
    }

    fun rename(from: String, to: String): CompletableFuture<IncomingPacket.Ack> = async {
        val source = from.trimEnd('/')
        val target = to.trimEnd('/')
        if (treeRootOf(source) != treeRootOf(target)) throw GuestVfsException("InvalidPath", "Can't move between device folders")
        forget(target)
        if (exists(target)) throw GuestVfsException("AlreadyExists", "${target.substringAfterLast('/')} already exists")
        val sourceParent = source.substringBeforeLast('/')
        val targetParent = target.substringBeforeLast('/')
        var uri = documentUri(source, resolve(source))
        if (sourceParent != targetParent) {
            uri = DocumentsContract.moveDocument(resolver, uri, documentUri(sourceParent, resolve(sourceParent)), documentUri(targetParent, resolve(targetParent)))
                ?: throw IOException("This folder's app can't move files")
        }
        val name = target.substringAfterLast('/')
        if (source.substringAfterLast('/') != name) {
            uri = DocumentsContract.renameDocument(resolver, uri, name) ?: throw IOException("Couldn't rename to $name")
        }
        forget(source)
        ids[target] = DocumentsContract.getDocumentId(uri)
        IncomingPacket.Ack(0, null)
    }

    /** Deletes for good: a device folder has no trash the app can restore from. */
    fun delete(path: String): CompletableFuture<IncomingPacket.Ack> = async {
        val uri = documentUri(path, resolve(path))
        if (!DocumentsContract.deleteDocument(resolver, uri)) throw IOException("Couldn't delete ${path.substringAfterLast('/')}")
        forget(path)
        IncomingPacket.Ack(0, null)
    }

    /**
     * Find in files over the folder (the guest's search is ripgrep): text files up to 2 MB, line by line, streamed as the
     * daemon's are. [isExcluded] is `files.exclude`.
     */
    fun search(query: SearchQuery, isExcluded: (String) -> Boolean, listener: (SearchEvent) -> Unit): Long {
        val requestId = searchIds.incrementAndGet()
        val cancelled = AtomicBoolean(false)
        searches[requestId] = cancelled
        executor.execute {
            val started = System.currentTimeMillis()
            var total = 0
            var truncated = false
            try {
                val matcher = lineMatcher(query)
                val root = query.roots.first()
                fun walk(folder: String) {
                    for (entry in listNow(folder).sortedBy { it.name }) {
                        if (cancelled.get() || truncated) return
                        if (isExcluded(entry.path)) continue
                        val relative = treeRelative(entry.path)
                        if (entry.isDirectory) {
                            if (entry.name != ".git" && query.exclude.none { globMatches(it, "$relative/") }) walk(entry.path)
                            continue
                        }
                        if (entry.size > MAX_SEARCHED_FILE) continue
                        if (query.include.isNotEmpty() && query.include.none { globMatches(it, relative) }) continue
                        if (query.exclude.any { globMatches(it, relative) }) continue
                        val bytes = runCatching { readNow(entry.path, 0, entry.size.toInt().coerceAtLeast(1)).content }.getOrNull() ?: continue
                        if (bytes.take(8_000).any { it == 0.toByte() }) continue
                        String(bytes, StandardCharsets.UTF_8).lineSequence().forEachIndexed { index, line ->
                            if (truncated) return@forEachIndexed
                            matcher(line).forEach { (column, length) ->
                                if (total >= query.maxResults) {
                                    truncated = true
                                    return@forEach
                                }
                                total++
                                listener(SearchEvent.Match(requestId, entry.path, index + 1, column, length, line.take(2_048)))
                            }
                        }
                    }
                }
                walk(root)
                if (!cancelled.get()) listener(SearchEvent.Done(requestId, total, truncated, (System.currentTimeMillis() - started).toInt()))
            } catch (error: Throwable) {
                if (!cancelled.get()) listener(SearchEvent.Error(requestId, (error.cause ?: error).message ?: "Search failed"))
            } finally {
                searches.remove(requestId)
            }
        }
        return requestId
    }

    fun cancelSearch(requestId: Long): Boolean = searches.remove(requestId)?.let { it.set(true); true } ?: false

    /** The picked folder's own name, for titles and Recent. */
    fun folderName(root: String): String? = runCatching {
        val uri = documentUri(root, DocumentsContract.getTreeDocumentId(treeUri(root)))
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** Whether the app still holds the folder's grant (it can be revoked in Android's settings). */
    fun hasAccess(root: String): Boolean {
        val uri = treeUriOf(root) ?: return false
        return resolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }
    }

    companion object {
        const val MAX_SEARCHED_FILE = 2L * 1024 * 1024
    }
}

/**
 * The file calls the app makes, sent where the path lives: a device folder's to [DeviceTreeFs], the rest to the guest.
 * A device folder is there with or without the guest ([isConnected] by path).
 */
class RoutingVfs(
    private val guest: io.github.kinteshi.nookraide.transport.GuestVfsClient,
    val device: DeviceTreeFs,
    /** Guest folders kept offline (G29 b): listed from here while the guest is away. */
    val offline: OfflineIndex? = null,
) : io.github.kinteshi.nookraide.transport.GuestVfs {
    override fun connect(url: String, token: String): CompletableFuture<Unit> = guest.connect(url, token)
    override fun roots(): CompletableFuture<List<String>> = guest.roots()

    /** Whether [path] is listed from a kept folder's last sync now: the guest is away and the folder is kept. */
    fun servesOffline(path: String, connectionId: String? = offline?.activeConnectionId): Boolean =
        !isTreePath(path) && !guest.isConnected() && offline?.serves(path, connectionId) == true

    override fun listDir(path: String) = when {
        isTreePath(path) -> device.listDir(path)
        servesOffline(path) -> offline?.listDir(path)?.let { CompletableFuture.completedFuture(it) }
            ?: CompletableFuture.failedFuture(GuestVfsException("NotFound", "Not in the offline copy"))
        else -> guest.listDir(path)
    }

    override fun stat(path: String) = when {
        isTreePath(path) -> device.stat(path)
        servesOffline(path) -> offline?.stat(path)?.let { CompletableFuture.completedFuture(it) }
            ?: CompletableFuture.failedFuture(GuestVfsException("NotFound", "Not in the offline copy"))
        else -> guest.stat(path)
    }
    override fun read(path: String, offset: Long, length: Int) = if (isTreePath(path)) device.read(path, offset, length) else guest.read(path, offset, length)
    override fun write(path: String, offset: Long, content: ByteArray, truncate: Boolean, expectedModifiedTs: Long?) =
        if (isTreePath(path)) device.write(path, offset, content, truncate, expectedModifiedTs) else guest.write(path, offset, content, truncate, expectedModifiedTs)
    override fun flush(path: String) = if (isTreePath(path)) device.flush(path) else guest.flush(path)
    override fun isConnected(): Boolean = guest.isConnected()
    override fun isConnected(path: String): Boolean = isTreePath(path) || guest.isConnected()
    fun create(path: String, directory: Boolean) = if (isTreePath(path)) device.create(path, directory) else guest.create(path, directory)
    fun rename(from: String, to: String) = if (isTreePath(from)) device.rename(from, to) else guest.rename(from, to)
    fun delete(path: String) = if (isTreePath(path)) device.delete(path) else guest.delete(path)
}
