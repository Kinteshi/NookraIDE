package io.github.kinteshi.quirecode.workbench

/** Comment delimiters per language id (file extension), following VS Code's language configurations. */
internal data class CommentTokens(val line: String?, val block: Pair<String, String>?)

internal fun commentTokens(languageId: String): CommentTokens = when (languageId) {
    "py", "pyi" -> CommentTokens("#", "\"\"\"" to "\"\"\"")
    "sh", "bash", "zsh", "yaml", "yml", "toml", "r", "conf", "ini", "cfg", "mk", "dockerfile" -> CommentTokens("#", null)
    "sql" -> CommentTokens("--", "/*" to "*/")
    "lua" -> CommentTokens("--", "--[[" to "]]")
    "html", "htm", "xml", "svg", "md", "markdown", "vue" -> CommentTokens(null, "<!--" to "-->")
    "css" -> CommentTokens(null, "/*" to "*/")
    "", "txt", "json" -> CommentTokens(null, null)
    else -> CommentTokens("//", "/*" to "*/")
}

/**
 * Toggle Line Comment over whole lines: uncomments when every non-blank line is commented,
 * otherwise comments them all at the shallowest indentation (blank lines are left alone).
 */
internal fun toggleLineComments(lines: List<String>, token: String): List<String> {
    val content = lines.filter(String::isNotBlank)
    if (content.isEmpty()) return lines
    if (content.all { it.trimStart().startsWith(token) }) {
        return lines.map { line ->
            val start = line.indexOfFirst { !it.isWhitespace() }
            if (start < 0 || !line.startsWith(token, start)) {
                line
            } else {
                val end = start + token.length
                line.removeRange(start, if (line.getOrNull(end) == ' ') end + 1 else end)
            }
        }
    }
    val indent = content.minOf { line -> line.indexOfFirst { !it.isWhitespace() } }
    return lines.map { if (it.isBlank()) it else it.substring(0, indent) + "$token " + it.substring(indent) }
}

/** Result of an edit: the new text and the selection [selectionStart, selectionEnd) to restore. */
internal data class TextEdit(val text: String, val selectionStart: Int, val selectionEnd: Int)

/** Toggle Block Comment: unwraps a selection that is (or sits inside) [open]…[close], else wraps it. */
internal fun toggleBlockComment(text: String, start: Int, end: Int, open: String, close: String): TextEdit {
    val selected = text.substring(start, end)
    if (selected.length >= open.length + close.length && selected.startsWith(open) && selected.endsWith(close)) {
        val inner = selected.substring(open.length, selected.length - close.length).removePrefix(" ").removeSuffix(" ")
        return TextEdit(text.replaceRange(start, end, inner), start, start + inner.length)
    }
    val before = text.substring(0, start).trimEnd(' ')
    val after = text.substring(end).trimStart(' ')
    if (before.endsWith(open) && after.startsWith(close)) {
        val openStart = before.length - open.length
        val closeEnd = text.length - after.length + close.length
        return TextEdit(text.replaceRange(openStart, closeEnd, selected), openStart, openStart + selected.length)
    }
    val wrapped = "$open $selected $close"
    val innerStart = start + open.length + 1
    return TextEdit(text.replaceRange(start, end, wrapped), innerStart, innerStart + selected.length)
}

/** The span that changed between [old] and [new]: old [start, oldEnd) became new [start, newEnd). */
internal data class ChangedSpan(val start: Int, val oldEnd: Int, val newEnd: Int)

internal fun changedSpan(old: String, new: String): ChangedSpan {
    var start = 0
    val limit = minOf(old.length, new.length)
    while (start < limit && old[start] == new[start]) start++
    var oldEnd = old.length
    var newEnd = new.length
    while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    return ChangedSpan(start, oldEnd, newEnd)
}
