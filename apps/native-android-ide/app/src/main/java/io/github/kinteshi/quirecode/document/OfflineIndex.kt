package io.github.kinteshi.quirecode.document

import io.github.kinteshi.quirecode.transport.FileEntry
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
    )

    private val trees = ConcurrentHashMap<String, Tree>()
    private val gson = com.google.gson.Gson()

    init {
        directory.mkdirs()
        directory.listFiles { file -> file.name.endsWith(".json") }?.forEach { file ->
            runCatching { gson.fromJson(file.readText(), Tree::class.java) }.getOrNull()
                ?.takeIf { it.folder.isNotEmpty() && it.entries != null }
                ?.let { trees[it.folder] = it }
        }
    }

    private fun fileFor(folder: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(folder.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "${digest.take(32)}.json")
    }

    val folders: List<String> get() = trees.keys.sorted()

    fun tree(folder: String): Tree? = trees[folder.trimEnd('/')]

    fun save(tree: Tree) {
        val staged = File(directory, fileFor(tree.folder).name + ".tmp")
        staged.writeText(gson.toJson(tree))
        staged.renameTo(fileFor(tree.folder))
        trees[tree.folder] = tree
    }

    fun remove(folder: String) {
        trees.remove(folder.trimEnd('/'))
        fileFor(folder.trimEnd('/')).delete()
    }

    /** The kept folder [path] is in (the innermost, if they nest), or null. */
    fun covering(path: String): Tree? {
        val clean = path.trimEnd('/')
        return trees.values.filter { clean == it.folder || clean.startsWith(it.folder + "/") }.maxByOrNull { it.folder.length }
    }

    /** Whether [folder] itself is kept (not only inside a kept one). */
    fun isKept(folder: String): Boolean = trees.containsKey(folder.trimEnd('/'))

    /** Whether [path] is a kept folder, inside one, or above one (then listed down to it). */
    fun serves(path: String): Boolean = covering(path) != null || keptBelow(path.trimEnd('/')).isNotEmpty()

    /** The kept folders under [folder]. */
    private fun keptBelow(folder: String): List<String> = trees.keys.filter { it.startsWith("$folder/") }

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
        return trees.values.flatMap { it.entries }
            .filter { !it.isDirectory && it.path.startsWith("$clean/") }
            .distinctBy { it.path }
    }
}
