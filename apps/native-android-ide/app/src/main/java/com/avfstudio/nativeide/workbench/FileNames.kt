package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.transport.FileEntry

/**
 * Checks a name typed into New File, New Folder, Rename or Save As. Names are used exactly as
 * typed: no extension is added. [allowSubpath] permits `dir/name`, creating `dir` as needed.
 */
fun entryNameProblem(name: String, allowSubpath: Boolean): String? {
    if (name.isBlank()) return "Enter a name"
    if (name != name.trim()) return "Names can't start or end with spaces"
    if ('\u0000' in name) return "Names can't contain NUL characters"
    if (!allowSubpath && '/' in name) return "Names can't contain /"
    val segments = name.trim('/').split('/')
    if (name.endsWith('/') || segments.any(String::isEmpty)) return "Remove the empty path segment"
    if (segments.any { it == "." || it == ".." }) return "Names can't be . or .."
    return null
}

/** Resolves [name] against [parent] unless it is already absolute. */
fun resolveEntryPath(parent: String, name: String): String =
    if (name.startsWith('/')) name else "${parent.trimEnd('/')}/$name"

/** Maps [path] after [from] was renamed to [to], or null when it isn't [from] or inside it. */
fun renamedPath(path: String, from: String, to: String): String? = when {
    path == from -> to
    path.startsWith("$from/") -> to + path.removePrefix(from)
    else -> null
}

/** The next free `Untitled-N` scratch name, like VS Code's untitled editors. */
fun nextUntitledName(openPaths: Collection<String>): String {
    var number = 1
    while ("scratch:/Untitled-$number" in openPaths) number++
    return "Untitled-$number"
}

/** A row of the Explorer tree: [entry] at [depth] below the shown folder. */
data class ExplorerRow(val entry: FileEntry, val depth: Int, val expanded: Boolean)

/**
 * Flattens the Explorer tree: [top] are the shown folder's entries, and each folder in
 * [expanded] whose children are loaded ([children]) lists them below itself, one level deeper.
 * A collapsed folder keeps its expanded subfolders, so they come back open, as in VS Code.
 */
fun explorerRows(top: List<FileEntry>, expanded: Set<String>, children: Map<String, List<FileEntry>>): List<ExplorerRow> {
    val rows = mutableListOf<ExplorerRow>()
    fun add(entries: List<FileEntry>, depth: Int) {
        entries.forEach { entry ->
            val open = entry.isDirectory && entry.path in expanded
            rows += ExplorerRow(entry, depth, open)
            if (open) children[entry.path]?.let { add(it, depth + 1) }
        }
    }
    add(top, 0)
    return rows
}

/** Explorer order: folders first, then by name. */
fun sortedEntries(entries: List<FileEntry>): List<FileEntry> =
    entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
