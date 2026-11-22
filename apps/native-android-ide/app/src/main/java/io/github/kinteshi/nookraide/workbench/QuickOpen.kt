package io.github.kinteshi.nookraide.workbench

import io.github.kinteshi.nookraide.transport.FileEntry
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Folders listed at a time for Quick Open's index; the app's other requests share the guest with them. */
private const val QUICK_OPEN_LISTINGS = 2

private const val QUICK_OPEN_MAX_DEPTH = 8
private const val QUICK_OPEN_PROGRESS_MS = 300L

/**
 * Walks a folder for Quick Open: nearest folders first and only [QUICK_OPEN_LISTINGS] at a time.
 * The AVF guest collapses when many threads read at once: asking for 1 000 folders together made
 * a notebook take 7 s to open instead of 0.4 s. The top-level folders take turns, so one huge
 * folder doesn't use up [maxFiles] before the small ones are seen. Callbacks run on the listing's thread.
 */
internal class QuickOpenWalk(
    private val list: (String) -> java.util.concurrent.CompletableFuture<List<FileEntry>>,
    private val isExcluded: (FileEntry) -> Boolean,
    private val walksInto: (FileEntry) -> Boolean,
    private val maxFiles: Int,
    private val onProgress: (List<FileEntry>) -> Unit,
    /** The files found, and whether the folder has more than [maxFiles]. */
    private val onDone: (List<FileEntry>, Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Folders to list, by top-level folder ("" for the root); [turns] says whose turn it is. */
    private val waiting = LinkedHashMap<String, ArrayDeque<Pair<String, Int>>>()
    private val turns = ArrayDeque<String>()
    private var root = ""
    private val files = ArrayList<FileEntry>()
    private var listing = 0
    private var truncated = false
    private var finished = false
    private var lastProgress = 0L

    fun start(root: String) {
        lastProgress = now()
        synchronized(this) {
            this.root = root.trimEnd('/')
            enqueue("", root, 0)
        }
        next()
    }

    private fun enqueue(branch: String, path: String, depth: Int) {
        waiting.getOrPut(branch) { ArrayDeque<Pair<String, Int>>().also { turns.addLast(branch) } }.addLast(path to depth)
    }

    /** The next folder, from the top-level folder whose turn it is. */
    private fun nextFolder(): Pair<String, Int>? {
        val branch = turns.removeFirstOrNull() ?: return null
        val queue = waiting.getValue(branch)
        val folder = queue.removeFirst()
        if (queue.isEmpty()) waiting.remove(branch) else turns.addLast(branch)
        return folder
    }

    /** Ends the walk with no more callbacks (another folder opened, or a newer walk began). */
    @Synchronized
    fun cancel() {
        finished = true
        waiting.clear()
        turns.clear()
    }

    private fun next() {
        val started = mutableListOf<Pair<String, Int>>()
        var done = false
        synchronized(this) {
            if (finished) return
            while (listing < QUICK_OPEN_LISTINGS) {
                started += nextFolder() ?: break
                listing++
            }
            if (listing == 0 && waiting.isEmpty()) {
                finished = true
                done = true
            }
        }
        if (done) onDone(files, truncated)
        started.forEach { (path, depth) ->
            list(path).whenComplete { children, _ -> arrived(children.orEmpty(), depth) }
        }
    }

    private fun arrived(children: List<FileEntry>, depth: Int) {
        var progress: List<FileEntry>? = null
        synchronized(this) {
            listing--
            if (finished) return
            val shown = children.filterNot(isExcluded)
            val found = shown.filterNot(FileEntry::isDirectory)
            val room = maxFiles - files.size
            if (found.size > room) truncated = true
            files += found.take(room)
            if (files.size >= maxFiles) {
                if (waiting.isNotEmpty() || shown.any(FileEntry::isDirectory)) truncated = true
                waiting.clear()
                turns.clear()
            } else if (depth < QUICK_OPEN_MAX_DEPTH) {
                shown.filter { it.isDirectory && walksInto(it) }.forEach { folder ->
                    enqueue(folder.path.removePrefix("$root/").substringBefore('/'), folder.path, depth + 1)
                }
            }
            val time = now()
            if (time - lastProgress >= QUICK_OPEN_PROGRESS_MS) {
                lastProgress = time
                progress = files.toList()
            }
        }
        progress?.let(onProgress)
        next()
    }
}

/** What Quick Open lists, chosen by the first character typed, as in VS Code. */
enum class QuickOpenMode(val prefix: String, val title: String) {
    Files("", "Go to File"),
    Commands(">", "Show and Run Commands"),
    EditorSymbols("@", "Go to Symbol in Editor"),
    WorkspaceSymbols("#", "Go to Symbol in Workspace"),
    Line(":", "Go to Line/Column"),
    Help("?", "Help"),
}

fun quickOpenMode(query: String): QuickOpenMode =
    QuickOpenMode.entries.firstOrNull { it.prefix.isNotEmpty() && query.startsWith(it.prefix) } ?: QuickOpenMode.Files

sealed interface QuickOpenItem {
    data class File(val entry: FileEntry) : QuickOpenItem
    data class Command(val command: EditorCommand, val keybinding: String) : QuickOpenItem
    data class Symbol(val name: String, val kind: Int, val detail: String, val location: LanguageLocation) : QuickOpenItem
    /** Moves the cursor to [line] and [column] (1-based). */
    data class Line(val line: Int, val column: Int?, val title: String) : QuickOpenItem
    /** One of the prefixes, listed by `?`; picking it starts that mode. */
    data class Prefix(val mode: QuickOpenMode) : QuickOpenItem
    /** A hint with nothing to pick (no symbols, no server, how to use `:`). */
    data class Message(val text: String) : QuickOpenItem
}

/** `42`, `42:7` or `42,7` → line and optional column; null when it isn't a position. */
fun parseLinePosition(text: String): Pair<Int, Int?>? {
    val match = Regex("""^\s*(\d+)\s*(?:[:,]\s*(\d+)\s*)?$""").find(text) ?: return null
    val line = match.groupValues[1].toIntOrNull() ?: return null
    return line to match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull()
}

/** The `:` entry: where the cursor would go, or how to use it. */
fun lineItem(query: String, lineCount: Int, currentLine: Int): QuickOpenItem {
    val (line, column) = parseLinePosition(query)
        ?: return QuickOpenItem.Message("Current line: $currentLine. Type a line number between 1 and $lineCount to go to it.")
    val target = line.coerceIn(1, lineCount.coerceAtLeast(1))
    return QuickOpenItem.Line(target, column, if (column != null) "Go to line $target, column $column" else "Go to line $target")
}

/** LSP SymbolKind names, for the detail beside a symbol. */
fun symbolKindName(kind: Int): String = when (kind) {
    1 -> "file"; 2 -> "module"; 3 -> "namespace"; 4 -> "package"; 5 -> "class"; 6 -> "method"
    7 -> "property"; 8 -> "field"; 9 -> "constructor"; 10 -> "enum"; 11 -> "interface"
    12 -> "function"; 13 -> "variable"; 14 -> "constant"; 15 -> "string"; 16 -> "number"
    17 -> "boolean"; 18 -> "array"; 19 -> "object"; 20 -> "key"; 21 -> "null"
    22 -> "enum member"; 23 -> "struct"; 24 -> "event"; 25 -> "operator"; 26 -> "type parameter"
    else -> "symbol"
}

/** The active file's symbols matching [query], in file order, with their kind and line. */
fun editorSymbolItems(symbols: List<OutlineSymbol>, uri: String, query: String): List<QuickOpenItem> =
    flattenOutline(symbols).map { it.first }
        .filter { fuzzyMatch(query.trim(), it.name) }
        .map { symbol ->
            val line = symbol.range.getAsJsonObject("start")?.get("line")?.asInt?.plus(1)
            QuickOpenItem.Symbol(symbol.name, symbol.kind, "${symbolKindName(symbol.kind)}${line?.let { " · line $it" }.orEmpty()}", LanguageLocation(uri, symbol.range))
        }

/**
 * `workspace/symbol` results (SymbolInformation, or WorkspaceSymbol whose location may lack a
 * range: it then opens at the top of the file). [relative] shortens a path for the detail.
 */
fun workspaceSymbolItems(response: JsonElement?, relative: (String) -> String): List<QuickOpenItem.Symbol> {
    if (response == null || !response.isJsonArray) return emptyList()
    return response.asJsonArray.mapNotNull { value ->
        val symbol = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val name = symbol.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
        val location = symbol.getAsJsonObject("location") ?: return@mapNotNull null
        val uri = location.get("uri")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
        val range = location.getAsJsonObject("range") ?: topOfFile()
        val path = runCatching { java.net.URI(uri).path }.getOrNull() ?: uri
        val container = symbol.get("containerName")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotEmpty)
        val line = range.getAsJsonObject("start")?.get("line")?.asInt?.plus(1)
        val detail = listOfNotNull(container, "${relative(path)}${line?.let { ":$it" }.orEmpty()}").joinToString(" · ")
        QuickOpenItem.Symbol(name, symbol.get("kind")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0, detail, LanguageLocation(uri, range))
    }
}

private fun topOfFile(): JsonObject {
    fun position() = JsonObject().apply {
        addProperty("line", 0)
        addProperty("character", 0)
    }
    return JsonObject().apply {
        add("start", position())
        add("end", position())
    }
}

/** Exact and prefix matches first, then the rest in the server's order. */
fun rankSymbols(items: List<QuickOpenItem.Symbol>, query: String): List<QuickOpenItem.Symbol> {
    val text = query.trim()
    return items.filter { fuzzyMatch(text, it.name) }.sortedBy { item ->
        when {
            item.name.equals(text, ignoreCase = true) -> 0
            item.name.startsWith(text, ignoreCase = true) -> 1
            else -> 2
        }
    }
}
