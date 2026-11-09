package io.github.kinteshi.quirecode.workbench

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.kinteshi.quirecode.document.VFS_CHUNK_SIZE
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Files in and out of the app (group 9: G25 share, G27 save to the device, G28 import): walking the guest's files,
 * streaming them to a file, a zip or a device folder, and device files into the guest. Blocking; run on the
 * controller's transfer thread, with [cancelled] checked between chunks.
 */

/** A guest file to carry, and its [name] inside what's carried: `src/app.py` for `app.py` in a selected `src`. */
data class TransferFile(val path: String, val name: String, val size: Long)

/** What a selection holds: its files, its folders (by name, so empty ones are kept), and the total size. */
data class TransferPlan(val files: List<TransferFile>, val folders: List<String>, val hasFolders: Boolean) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

/** How far a transfer is, for the progress banner. */
data class TransferProgress(val title: String, val files: Int, val totalFiles: Int, val bytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else if (totalFiles > 0) files.toFloat() / totalFiles else 0f
}

/** A device file or folder picked to import (G28). */
data class DeviceItem(val uri: Uri, val name: String, val folder: Boolean, val size: Long)

class TransferCancelled : IOException("Cancelled")

/**
 * The names [paths] are carried under: each its last segment, with " (2)", " (3)"… when two selected items from
 * different folders share a name, so neither is overwritten in the zip or folder.
 */
fun transferTopNames(paths: List<String>): List<String> {
    val used = mutableSetOf<String>()
    return paths.map { path ->
        val base = path.trimEnd('/').substringAfterLast('/').ifEmpty { "files" }
        var name = base
        var attempt = 2
        while (!used.add(name.lowercase())) name = numberedName(base, attempt++)
        name
    }
}

/** `report.txt` → `report (2).txt`; `src` → `src (2)`; `.env` → `.env (2)`. */
fun numberedName(name: String, attempt: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} ($attempt)${name.substring(dot)}" else "$name ($attempt)"
}

/** The archive's name for [paths]: the item's own name, or its folder's with how many were chosen. */
fun archiveName(paths: List<String>): String {
    val names = transferTopNames(paths)
    if (names.size == 1) return "${names.single()}.zip"
    val parent = paths.first().trimEnd('/').substringBeforeLast('/').substringAfterLast('/').ifEmpty { "files" }
    return "$parent (${names.size} items).zip"
}

/** A file's MIME type from its extension, for the share sheet and the file picker. */
fun mimeTypeFor(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: when (extension) {
            "py", "ipynb", "md", "txt", "json", "yaml", "yml", "toml", "csv", "ts", "js", "kt", "rs", "sh", "c", "h", "cpp" -> "text/plain"
            else -> "application/octet-stream"
        }
}

internal class FileTransfers(
    private val vfs: io.github.kinteshi.quirecode.document.RoutingVfs,
    private val resolver: ContentResolver,
    private val cancelled: AtomicBoolean,
) {
    private fun checkCancelled() {
        if (cancelled.get()) throw TransferCancelled()
    }

    private fun <T> java.util.concurrent.CompletableFuture<T>.await(): T = try {
        get(2, TimeUnit.MINUTES)
    } catch (error: java.util.concurrent.ExecutionException) {
        throw (error.cause as? IOException) ?: IOException(error.cause?.message ?: error.message, error.cause)
    }

    /** Walks [paths]: every file under them, named from each selected item down. */
    fun plan(paths: List<String>): TransferPlan {
        val files = mutableListOf<TransferFile>()
        val folders = mutableListOf<String>()
        var hasFolders = false
        fun walk(path: String, name: String) {
            checkCancelled()
            vfs.listDir(path).await().sortedBy { it.name }.forEach { entry ->
                val child = "$name/${entry.name}"
                if (entry.isDirectory) {
                    folders += child
                    walk(entry.path, child)
                } else {
                    files += TransferFile(entry.path, child, entry.size)
                }
            }
        }
        paths.zip(transferTopNames(paths)).forEach { (path, name) ->
            val entry = vfs.stat(path).await()
            if (entry.isDirectory) {
                hasFolders = true
                folders += name
                walk(path, name)
            } else {
                files += TransferFile(path, name, entry.size)
            }
        }
        return TransferPlan(files, folders, hasFolders)
    }

    /** Streams one guest file into [out], [onBytes] after each chunk. */
    fun copyOut(file: TransferFile, out: OutputStream, onBytes: (Int) -> Unit) {
        var offset = 0L
        while (true) {
            checkCancelled()
            val chunk = vfs.read(file.path, offset, VFS_CHUNK_SIZE).await()
            if (chunk.content.isNotEmpty()) {
                out.write(chunk.content)
                offset += chunk.content.size
                onBytes(chunk.content.size)
            }
            if (chunk.eof || chunk.content.isEmpty()) break
        }
    }

    /** [plan] as one zip into [out], folders kept as entries. */
    fun zip(plan: TransferPlan, out: OutputStream, onFile: () -> Unit, onBytes: (Int) -> Unit) {
        ZipOutputStream(out).use { zip ->
            val withFiles = plan.files.map { it.name.substringBeforeLast('/', "") }.toSet()
            // Empty folders as entries of their own; the others come with their files.
            plan.folders.filter { folder -> withFiles.none { it == folder || it.startsWith("$folder/") } }
                .forEach { zip.putNextEntry(ZipEntry("$it/")); zip.closeEntry() }
            plan.files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                copyOut(file, zip, onBytes)
                zip.closeEntry()
                onFile()
            }
        }
    }

    /** [plan] into the device folder [tree] (a picked folder), creating its folders; the provider renames clashes. */
    fun saveToTree(plan: TransferPlan, tree: Uri, onFile: () -> Unit, onBytes: (Int) -> Unit) {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val made = mutableMapOf("" to root)
        // What this save put directly in the picked folder, taken away again if it fails or is cancelled.
        val topLevel = mutableListOf<Uri>()
        fun create(parent: Uri, mime: String, name: String): Uri =
            (DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("Couldn't make $name"))
                .also { if (parent == root) topLevel += it }
        fun folder(name: String): Uri = made.getOrPut(name) {
            create(folder(name.substringBeforeLast('/', "")), DocumentsContract.Document.MIME_TYPE_DIR, name.substringAfterLast('/'))
        }
        try {
            plan.folders.forEach { folder(it) }
            plan.files.forEach { file ->
                checkCancelled()
                val document = create(folder(file.name.substringBeforeLast('/', "")), "application/octet-stream", file.name.substringAfterLast('/'))
                (resolver.openOutputStream(document, "w") ?: throw IOException("Couldn't write ${file.name}")).use { copyOut(file, it, onBytes) }
                onFile()
            }
        } catch (error: Exception) {
            topLevel.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw error
        }
    }

    /** The files under a picked device folder [tree], with their names below it, and its subfolders. */
    fun deviceTree(tree: Uri): Triple<String, List<Pair<DeviceItem, String>>, List<String>> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Imported"
        val files = mutableListOf<Pair<DeviceItem, String>>()
        val folders = mutableListOf<String>()
        fun walk(documentId: String, prefix: String) {
            checkCancelled()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1)?.replace('/', '_') ?: continue
                    val isFolder = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (isFolder) {
                        folders += relative
                        walk(id, relative)
                    } else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        files += DeviceItem(uri, name, false, if (cursor.isNull(3)) 0 else cursor.getLong(3)) to relative
                    }
                }
            }
        }
        walk(rootId, "")
        return Triple(rootName, files, folders)
    }

    /** Whether the guest has something at [path]. */
    fun exists(path: String): Boolean = runCatching { vfs.stat(path).await() }.isSuccess

    /** A free name in [folder] for [name]: itself, or with " (2)", " (3)"… */
    fun freeName(folder: String, name: String): String {
        var candidate = name
        var attempt = 2
        while (exists("$folder/$candidate")) candidate = numberedName(name, attempt++)
        return candidate
    }

    fun makeFolder(path: String) {
        // Fails when it's there already, which is fine: the files go in either way.
        runCatching { vfs.create(path, directory = true).await() }
    }

    /** Streams [input] into the guest file [path], replacing what's there. */
    fun copyIn(input: InputStream, path: String, onBytes: (Int) -> Unit) {
        if (!exists(path)) vfs.create(path, directory = false).await()
        val buffer = ByteArray(VFS_CHUNK_SIZE)
        var offset = 0L
        while (true) {
            checkCancelled()
            var filled = 0
            while (filled < buffer.size) {
                val count = input.read(buffer, filled, buffer.size - filled)
                if (count < 0) break
                filled += count
            }
            if (filled == 0 && offset > 0) break
            vfs.write(path, offset, buffer.copyOf(filled), truncate = offset == 0L, expectedModifiedTs = null).await()
            offset += filled
            onBytes(filled)
            if (filled < buffer.size) break
        }
        vfs.flush(path).await()
    }
}

/** How the save dialog saves: a single file as itself, a zip, or into a device folder. */
enum class SaveHow { File, Zip, Folder }

/** Android's file pickers, launched by the workbench (group 9). */
class DevicePickers(
    val createFile: (suggestedName: String) -> Unit,
    val createZip: (suggestedName: String) -> Unit,
    val openTree: () -> Unit,
    val openFiles: () -> Unit,
)

/** 1536 → "1.5 KB", for sizes said to people. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes bytes"
}
