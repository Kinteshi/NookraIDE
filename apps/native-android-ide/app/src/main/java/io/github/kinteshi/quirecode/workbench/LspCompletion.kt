package io.github.kinteshi.quirecode.workbench

import io.github.kinteshi.quirecode.editor.LspPosition
import io.github.kinteshi.quirecode.editor.LspRange
import io.github.kinteshi.quirecode.editor.LspTextEdit
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * A language server's completion item, as the suggest popup needs it. [insertRange] is what the
 * item replaces up to the cursor (VS Code's default insert mode); [additionalEdits] are the
 * auto-import lines it adds elsewhere.
 */
data class LspCompletionItem(
    val label: String,
    /** The signature after the label, e.g. `(path, mode)` (LSP's `labelDetails.detail`). */
    val labelDetail: String?,
    /** Where it comes from, e.g. the module of an auto-import (`labelDetails.description`), else its `detail`. */
    val description: String?,
    val kind: Int,
    val sortText: String,
    val filterText: String,
    val insertText: String,
    val snippet: Boolean,
    val insertRange: LspRange?,
    val additionalEdits: List<LspTextEdit>,
    val preselect: Boolean,
    /** The item as the server sent it, for `completionItem/resolve`. */
    val raw: JsonObject,
)

data class LspCompletionList(val items: List<LspCompletionItem>, val incomplete: Boolean)

private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

/** `textDocument/completion`'s reply: an array, or a CompletionList with `itemDefaults`. */
fun parseCompletionList(response: JsonElement?): LspCompletionList {
    if (response == null || response.isJsonNull) return LspCompletionList(emptyList(), false)
    val list = response.takeIf { it.isJsonObject }?.asJsonObject
    val items = when {
        response.isJsonArray -> response.asJsonArray
        else -> list?.getAsJsonArray("items")
    } ?: return LspCompletionList(emptyList(), false)
    val defaults = list?.getAsJsonObject("itemDefaults")
    val defaultRange = defaults?.get("editRange")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::editRange)
    val defaultFormat = defaults?.get("insertTextFormat")?.takeIf { it.isJsonPrimitive }?.asInt
    val defaultData = defaults?.get("data")
    val parsed = items.mapNotNull { element ->
        val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        // completionItem/resolve needs the shared `data` on the item itself.
        if (defaultData != null && !item.has("data")) item.add("data", defaultData)
        parseCompletionItem(item, defaultRange, defaultFormat)
    }
    return LspCompletionList(parsed, list?.get("isIncomplete")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
}

/** A plain range, or an InsertReplaceEdit's `insert` side (VS Code's default `editor.suggest.insertMode`). */
private fun editRange(value: JsonObject): LspRange? =
    value.getAsJsonObject("insert")?.toLspRange() ?: value.toLspRange()

internal fun parseCompletionItem(item: JsonObject, defaultRange: LspRange? = null, defaultFormat: Int? = null): LspCompletionItem? {
    val label = item.string("label") ?: return null
    val details = item.getAsJsonObject("labelDetails")
    val textEdit = item.get("textEdit")?.takeIf { it.isJsonObject }?.asJsonObject
    val format = item.get("insertTextFormat")?.takeIf { it.isJsonPrimitive }?.asInt ?: defaultFormat
    val additional = item.getAsJsonArray("additionalTextEdits")?.mapNotNull { edit ->
        val value = edit.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val range = value.getAsJsonObject("range")?.toLspRange() ?: return@mapNotNull null
        LspTextEdit(range, value.string("newText") ?: return@mapNotNull null)
    }.orEmpty()
    return LspCompletionItem(
        label = label,
        labelDetail = details?.string("detail")?.takeIf { it.isNotBlank() },
        description = (details?.string("description") ?: item.string("detail"))?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        kind = item.get("kind")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1,
        sortText = item.string("sortText") ?: label,
        filterText = item.string("filterText") ?: label,
        insertText = textEdit?.string("newText") ?: item.string("insertText") ?: label,
        snippet = format == 2,
        insertRange = textEdit?.let(::editRange) ?: defaultRange,
        additionalEdits = additional,
        preselect = item.get("preselect")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
        raw = item,
    )
}

/** The identifier being typed before [column] on [line]: where it starts. */
fun completionWordStart(line: String, column: Int): Int {
    var start = column.coerceIn(0, line.length)
    while (start > 0 && (isIdentifierCharacter(line[start - 1]) || line[start - 1] == '$')) start--
    return start
}

/**
 * How well [word] (what is typed) matches [candidate], higher is better; null when it doesn't.
 * Like VS Code's fuzzy match: the letters in order, the first one at the start or at a word
 * boundary (`_`, a capital), with points for a prefix, the same case and each boundary hit.
 */
fun completionScore(word: String, candidate: String): Int? {
    if (word.isEmpty()) return 0
    if (candidate.isEmpty()) return null
    var score = 0
    var position = 0
    var previousMatch = -2
    for ((index, char) in word.withIndex()) {
        var found = -1
        var at = position
        while (at < candidate.length) {
            if (candidate[at].equals(char, ignoreCase = true)) {
                // The first letter may only match at the start or at a word boundary.
                if (index > 0 || at == 0 || isWordBoundary(candidate, at)) {
                    found = at
                    break
                }
            }
            at++
        }
        if (found < 0) return null
        score += 1
        if (candidate[found] == char) score += 1
        if (found == 0 || isWordBoundary(candidate, found)) score += 3
        if (found == previousMatch + 1) score += 2
        previousMatch = found
        position = found + 1
    }
    if (candidate.startsWith(word)) score += 10 else if (candidate.startsWith(word, ignoreCase = true)) score += 6
    if (candidate.length == word.length) score += 2
    return score
}

private fun isWordBoundary(text: String, index: Int): Boolean {
    if (index == 0) return true
    val previous = text[index - 1]
    val current = text[index]
    return previous == '_' || previous == '.' || previous == '-' || (current.isUpperCase() && !previous.isUpperCase())
}

/** The items that match [word], best first; ties keep the server's order (its `sortText`). */
fun filterCompletions(items: List<LspCompletionItem>, word: String): List<LspCompletionItem> {
    val scored = items.mapNotNull { item -> completionScore(word, item.filterText)?.let { item to it } }
    return if (word.isEmpty()) {
        scored.sortedWith(compareBy({ it.first.sortText }, { it.first.label })).map { it.first }
    } else {
        scored.sortedWith(compareByDescending<Pair<LspCompletionItem, Int>> { it.second }.thenBy { it.first.sortText }.thenBy { it.first.label })
            .map { it.first }
    }
}

/**
 * A snippet's text without its placeholders (snippets are not supported yet): `${1:name}` keeps
 * `name`, `$1` and `$0` go, `\$` is a dollar. Returns the text and where the cursor goes: the
 * first tab stop, or the end.
 */
fun snippetToPlain(snippet: String): Pair<String, Int> {
    val out = StringBuilder()
    var cursor: Int? = null
    var finalStop: Int? = null
    var index = 0
    fun mark(number: Int) {
        if (number == 0) {
            if (finalStop == null) finalStop = out.length
        } else if (cursor == null) {
            cursor = out.length
        }
    }
    while (index < snippet.length) {
        val char = snippet[index]
        when {
            char == '\\' && index + 1 < snippet.length && snippet[index + 1] in "$}\\" -> {
                out.append(snippet[index + 1])
                index += 2
            }
            char == '$' && index + 1 < snippet.length && snippet[index + 1].isDigit() -> {
                var end = index + 1
                while (end < snippet.length && snippet[end].isDigit()) end++
                mark(snippet.substring(index + 1, end).toInt())
                index = end
            }
            char == '$' && index + 1 < snippet.length && snippet[index + 1] == '{' -> {
                var end = index + 2
                while (end < snippet.length && snippet[end].isDigit()) end++
                val number = snippet.substring(index + 2, end).toIntOrNull()
                if (number == null) {
                    out.append(char)
                    index++
                    continue
                }
                mark(number)
                // ${1:default} keeps the default (nested placeholders lose their markers); ${1|a,b|} keeps a.
                var depth = 1
                var close = end
                while (close < snippet.length && depth > 0) {
                    if (snippet[close] == '{') depth++
                    if (snippet[close] == '}') depth--
                    if (depth > 0) close++
                }
                val body = snippet.substring(end, close.coerceAtMost(snippet.length))
                when {
                    body.startsWith(":") -> out.append(snippetToPlain(body.drop(1)).first)
                    body.startsWith("|") -> out.append(body.drop(1).substringBefore(',').substringBefore('|'))
                }
                index = close + 1
            }
            else -> {
                out.append(char)
                index++
            }
        }
    }
    return out.toString() to (cursor ?: finalStop ?: out.length)
}

/** The edit an accepted item makes on [line] when typing has reached [cursor]: its range's start up to the cursor. */
fun completionEdit(item: LspCompletionItem, line: Int, wordStart: Int, cursor: Int): Pair<LspRange, String> {
    val start = item.insertRange?.start?.takeIf { it.line == line && it.character <= cursor } ?: LspPosition(line, wordStart)
    val text = if (item.snippet) snippetToPlain(item.insertText).first else item.insertText
    return LspRange(start, LspPosition(line, cursor)) to text
}

/** Python and the C family start line comments with these; completion stays quiet inside one. */
fun inLineComment(prefix: String, languageId: String): Boolean {
    val token = when (languageId) {
        "py", "pyi" -> "#"
        "ts", "tsx", "js", "jsx", "mts", "cts", "mjs", "cjs", "rs", "go", "c", "cpp", "java", "kt" -> "//"
        else -> return false
    }
    var quote: Char? = null
    var index = 0
    while (index < prefix.length) {
        val char = prefix[index]
        when {
            quote != null && char == '\\' -> index++
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'' || char == '`') -> quote = char
            quote == null && prefix.startsWith(token, index) -> return true
        }
        index++
    }
    return false
}
