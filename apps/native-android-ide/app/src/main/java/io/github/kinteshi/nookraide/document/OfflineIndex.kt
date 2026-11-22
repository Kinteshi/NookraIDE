package io.github.kinteshi.nookraide.document

import io.github.kinteshi.nookraide.transport.FileEntry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Guest folders kept available offline (G29 step b): each one's listing as of its last sync, so Explorer, Quick Open and
 * search work on it while the guest is away. The files' text is in the document cache ([DocumentRepository.storeCopy]);
 * this is only where they are. One small JSON file per folder, in the app's private storage.
 */
class OfflineIndex(private val directory: File) {
    /** A kept folder: its files and folders below it, what was left out (too large, binary), and when. */
    data class Tree(
        val folder: String,
        val entries: List<FileEntry>,
        val syncedAt: Long,
        /** Files not kept: over the size limit, binary, or past the count or total limits. */
        val skipped: List<String> = emptyList(),
        val bytes: Long = 0,
        /** The guest profile whose files and listing this tree represents. */
        val connectionId: String? = null,
    )

    private val trees = ConcurrentHashMap<String, Tree>()
    private val gson = com.google.gson.Gson()
    @Volatile
    var activeConnectionId: String? = null

    init {
        directory.mkdirs()
        directory.listFiles { file -> file.name.endsWith(".json") }?.forEach { file ->
            runCatching { gson.fromJson(file.readText(), Tree::class.java) }.getOrNull()
                ?.takeIf { it.folder.isNotEmpty() && it.entries != null }
                ?.let { trees[treeKey(it.connectionId, it.folder)] = it }
        }
    }

    private fun treeKey(connectionId: String?, folder: String) = "${connectionId.orEmpty()}\u0000${folder.trimEnd('/')}"

    private fun fileFor(folder: String, connectionId: String? = activeConnectionId): File {
        val source = if (connectionId == null) folder.trimEnd('/') else "$connectionId\u0000${folder.trimEnd('/')}"
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "${digest.take(32)}.json")
    }

    val folders: List<String> get() = trees.values.filter { it.connectionId == activeConnectionId }.map { it.folder }.sorted()

    fun tree(folder: String): Tree? = trees[treeKey(activeConnectionId, folder)]

    fun save(tree: Tree) {
        val scoped = tree.copy(connectionId = tree.connectionId ?: activeConnectionId)
        val target = fileFor(scoped.folder, scoped.connectionId)
        val staged = File(directory, target.name + ".tmp")
        staged.writeText(gson.toJson(scoped))
        if (!staged.renameTo(target)) error("Could not save offline folder index")
        trees[treeKey(scoped.connectionId, scoped.folder)] = scoped
    }

    fun remove(folder: String) {
        val key = treeKey(activeConnectionId, folder)
        trees.remove(key)
        fileFor(folder.trimEnd('/')).delete()
    }

    /** Existing indexes predate connection scoping; associate them with the last-used guest once. */
    fun migrateLegacyScope(connectionId: String?) {
        if (connectionId == null) return
        trees.values.filter { it.connectionId == null }.toList().forEach { legacy ->
            trees.remove(treeKey(null, legacy.folder))
            fileFor(legacy.folder, null).delete()
            val scoped = legacy.copy(connectionId = connectionId)
            val target = fileFor(scoped.folder, connectionId)
            val staged = File(directory, target.name + ".tmp")
            staged.writeText(gson.toJson(scoped))
            if (!staged.renameTo(target)) error("Could not migrate offline folder index")
            trees[treeKey(connectionId, scoped.folder)] = scoped
        }
    }

    /** The kept folder [path] is in (the innermost, if they nest), or null. */
    fun covering(path: String, connectionId: String? = activeConnectionId): Tree? {
        val clean = path.trimEnd('/')
        return trees.values.filter { it.connectionId == connectionId && (clean == it.folder || clean.startsWith(it.folder + "/")) }
            .maxByOrNull { it.folder.length }
    }

    /** Whether [folder] itself is kept (not only inside a kept one). */
    fun isKept(folder: String, connectionId: String? = activeConnectionId): Boolean =
        trees.containsKey(treeKey(connectionId, folder))

    /** Whether [path] is a kept folder, inside one, or above one (then listed down to it). */
    fun serves(path: String, connectionId: String? = activeConnectionId): Boolean =
        covering(path, connectionId) != null || keptBelow(path.trimEnd('/'), connectionId).isNotEmpty()

    /** The kept folders under [folder]. */
    private fun keptBelow(folder: String, connectionId: String? = activeConnectionId): List<String> = trees.values
        .filter { it.connectionId == connectionId && it.folder.startsWith("$folder/") }
        .map { it.folder }

    /** A kept folder's children as last synced; above kept folders, the folders leading to them; else null. */
    fun listDir(path: String): List<FileEntry>? {
        val clean = path.trimEnd('/')
        val tree = covering(clean) ?: return keptBelow(clean)
            .map { it.removePrefix("$clean/").substringBefore('/') }
            .distinct()
            .map { name -> FileEntry(name, "$clean/$name", true, 0, 0) }
            .takeIf { it.isNotEmpty() }
        if (clean != tree.folder && tree.entries.none { it.path == clean && it.isDirectory }) return null
        return tree.entries.filter { it.path.substringBeforeLast('/') == clean }
    }

    fun stat(path: String): FileEntry? {
        val clean = path.trimEnd('/')
        val tree = covering(clean)
            ?: return if (keptBelow(clean).isNotEmpty()) FileEntry(clean.substringAfterLast('/'), clean, true, 0, 0) else null
        if (clean == tree.folder) return FileEntry(clean.substringAfterLast('/'), clean, true, 0, tree.syncedAt)
        return tree.entries.firstOrNull { it.path == clean }
    }

    /** The kept files below [folder], from every kept folder that has some there. */
    fun filesUnder(folder: String): List<FileEntry> {
        val clean = folder.trimEnd('/')
        return trees.values.filter { it.connectionId == activeConnectionId }.flatMap { it.entries }
            .filter { !it.isDirectory && it.path.startsWith("$clean/") }
            .distinctBy { it.path }
    }
}
