package io.github.kinteshi.quirecode.workbench

/** The Search view's options, as the replacement needs them. */
data class SearchPattern(
    val query: String,
    val regex: Boolean,
    val caseSensitive: Boolean,
    val wholeWord: Boolean,
) {
    /** The same pattern ripgrep ran: a literal is escaped, whole word adds `\b` on both sides. */
    fun toRegex(): Regex {
        val body = if (regex) query else Regex.escape(query)
        val pattern = if (wholeWord) "\\b(?:$body)\\b" else body
        return Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }

    /**
     * What one match becomes. With regex on, `$1` and `${name}` refer to groups and `$0` to the
     * whole match (VS Code's `$&` works too); otherwise [replacement] is taken literally.
     */
    fun replacementFor(match: MatchResult, replacement: String): String =
        if (!regex) {
            replacement
        } else {
            expandGroups(match, replacement.replace("$&", "$0"))
        }
}

/** `$0`–`$99` and `${name}` from [match]; `$$` is a dollar sign; anything else stays as written. */
private fun expandGroups(match: MatchResult, template: String): String {
    val out = StringBuilder()
    var index = 0
    while (index < template.length) {
        val char = template[index]
        if (char != '$' || index == template.lastIndex) {
            out.append(char)
            index++
            continue
        }
        val next = template[index + 1]
        when {
            next == '$' -> {
                out.append('$')
                index += 2
            }
            next.isDigit() -> {
                var end = index + 2
                if (end < template.length && template[end].isDigit() && "${next}${template[end]}".toInt() < match.groupValues.size) end++
                val group = template.substring(index + 1, end).toInt()
                out.append(match.groups[group]?.value ?: if (group < match.groupValues.size) "" else template.substring(index, end))
                index = end
            }
            next == '{' && template.indexOf('}', index) > index -> {
                val close = template.indexOf('}', index)
                val name = template.substring(index + 2, close)
                val value = name.toIntOrNull()?.let { match.groups[it]?.value }
                    ?: runCatching { match.groups[name]?.value }.getOrNull()
                out.append(value ?: "")
                index = close + 1
            }
            else -> {
                out.append(char)
                index++
            }
        }
    }
    return out.toString()
}

/** A search match, by 1-based line and 0-based column, as the daemon reports it. */
data class MatchPosition(val line: Int, val column: Int)

/**
 * Replaces the matches of [pattern] in [text] line by line (ripgrep matches never cross a line
 * end, and neither do these). With [only], just the matches that start at those positions.
 * Returns the new text and how many matches were replaced.
 */
fun replaceMatches(text: String, pattern: SearchPattern, replacement: String, only: Set<MatchPosition>? = null): Pair<String, Int> {
    val regex = pattern.toRegex()
    var count = 0
    val out = StringBuilder(text.length)
    var lineNumber = 1
    var start = 0
    while (start <= text.length) {
        val newline = text.indexOf('\n', start)
        val end = if (newline < 0) text.length else newline
        // A CRLF file keeps its \r out of the matching, as ripgrep does.
        val contentEnd = if (end > start && text[end - 1] == '\r') end - 1 else end
        val line = text.substring(start, contentEnd)
        val currentLine = lineNumber
        val replaced = regex.replace(line) { match ->
            if (match.value.isEmpty() || (only != null && MatchPosition(currentLine, match.range.first) !in only)) {
                match.value
            } else {
                count++
                pattern.replacementFor(match, replacement)
            }
        }
        out.append(replaced).append(text, contentEnd, end)
        if (newline < 0) break
        out.append('\n')
        start = newline + 1
        lineNumber++
    }
    return out.toString() to count
}

/** The matched text and what it would become, for the Search view's preview of one match. */
fun replacementPreview(lineText: String, column: Int, pattern: SearchPattern, replacement: String): String? {
    val match = runCatching { pattern.toRegex().find(lineText, column.coerceIn(0, lineText.length)) }.getOrNull() ?: return null
    if (match.range.first != column) return null
    return pattern.replacementFor(match, replacement)
}
