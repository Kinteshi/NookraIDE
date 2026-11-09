package io.github.kinteshi.quirecode.workbench

/** Matches the find widget counts and highlights at most (VS Code stops at 19,999 too). */
const val FIND_MATCH_LIMIT = 20_000

/** One match in the editor's text, by offset: [start] inclusive, [end] exclusive. */
data class FindMatch(val start: Int, val end: Int)

/** The editor's matches for [pattern], or why there are none to look for. */
data class FindResult(val matches: List<FindMatch>, val error: String? = null, val limited: Boolean = false)

/**
 * The find widget's pattern as a regex over the whole text: `^` and `$` work per line, as in
 * VS Code, and a regex may match across lines (`\n`).
 */
internal fun SearchPattern.toFindRegex(): Regex {
    val base = toRegex()
    return Regex(base.pattern, base.options + RegexOption.MULTILINE)
}

/** Matches of [pattern] in [text], within [scope] (offsets) when "Find in Selection" is on. */
fun findMatches(text: String, pattern: SearchPattern, scope: IntRange? = null, limit: Int = FIND_MATCH_LIMIT): FindResult {
    if (pattern.query.isEmpty()) return FindResult(emptyList())
    val regex = try {
        pattern.toFindRegex()
    } catch (error: IllegalArgumentException) {
        return FindResult(emptyList(), "Invalid regular expression")
    }
    val from = scope?.first?.coerceIn(0, text.length) ?: 0
    val to = scope?.let { (it.last + 1).coerceIn(from, text.length) } ?: text.length
    val matches = ArrayList<FindMatch>()
    var match = regex.find(text, from)
    while (match != null && match.range.first < to) {
        val end = match.range.last + 1
        // Empty matches (`^`, `\b`) have nothing to show or replace.
        if (end > match.range.first && end <= to) {
            if (matches.size == limit) return FindResult(matches, limited = true)
            matches += FindMatch(match.range.first, end)
        }
        match = match.next()
    }
    return FindResult(matches)
}

/** [text] as a regex that matches it literally (the find box's seed when regex is on). */
fun escapeRegexLiteral(text: String): String = text.replace(Regex("""[\\^$.|?*+()\[\]{}]""")) { "\\" + it.value }

/** The first match at or after [offset], wrapping to the first one; -1 with none. */
fun matchAtOrAfter(matches: List<FindMatch>, offset: Int): Int {
    if (matches.isEmpty()) return -1
    val index = matches.indexOfFirst { it.start >= offset }
    return if (index >= 0) index else 0
}

/** The last match before [offset], wrapping to the last one; -1 with none. */
fun matchBefore(matches: List<FindMatch>, offset: Int): Int {
    if (matches.isEmpty()) return -1
    val index = matches.indexOfLast { it.start < offset }
    return if (index >= 0) index else matches.lastIndex
}

/**
 * VS Code's "Preserve Case": the replacement takes the matched text's case when it is all
 * upper case, all lower case, or capitalised.
 */
fun preserveCase(matched: String, replacement: String): String = when {
    replacement.isEmpty() || matched.none(Char::isLetter) -> replacement
    matched == matched.uppercase() -> replacement.uppercase()
    matched == matched.lowercase() -> replacement.lowercase()
    matched.first().isUpperCase() -> replacement.replaceFirstChar(Char::uppercaseChar)
    else -> replacement
}

/**
 * What [match] becomes. With regex on, `$1` and `${name}` refer to groups, and `\n` and `\t`
 * are a newline and a tab, as in VS Code's replace box.
 */
fun findReplacement(text: String, match: FindMatch, pattern: SearchPattern, replacement: String, keepCase: Boolean): String {
    val matched = text.substring(match.start, match.end)
    val value = if (!pattern.regex) {
        replacement
    } else {
        val result = runCatching { pattern.toFindRegex().find(text, match.start) }.getOrNull()
            ?.takeIf { it.range.first == match.start }
        val escaped = unescapeReplacement(replacement)
        if (result == null) escaped else pattern.replacementFor(result, escaped)
    }
    return if (keepCase) preserveCase(matched, value) else value
}

private fun unescapeReplacement(value: String): String {
    if ('\\' !in value) return value
    val out = StringBuilder()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        val next = value.getOrNull(index + 1)
        if (char == '\\' && next != null && next in "nt\\") {
            out.append(
                when (next) {
                    'n' -> '\n'
                    't' -> '\t'
                    else -> '\\'
                },
            )
            index += 2
        } else {
            out.append(char)
            index++
        }
    }
    return out.toString()
}
