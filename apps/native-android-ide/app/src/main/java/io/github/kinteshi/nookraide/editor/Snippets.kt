package io.github.kinteshi.nookraide.editor

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** A span of a snippet's text, end exclusive. */
data class SnippetSpan(val start: Int, val end: Int)

/** A tab stop: `$1`, `${1:default}` or `${1|a,b|}`, with every place it occurs (mirrors). */
data class SnippetStop(val index: Int, val spans: List<SnippetSpan>, val choices: List<String>? = null)

/** A snippet's text with its tab stops in Tab order (1, 2, … and 0, the final stop, last). */
data class ParsedSnippet(val text: String, val stops: List<SnippetStop>)

/**
 * Parses TextMate/VS Code snippet syntax: `$1`, `${1}`, `${1:default}` (defaults can nest
 * stops), `${1|one,two|}`, `$NAME`, `${NAME}`, `${NAME:default}` and `${NAME/regex/format/flags}`.
 * [variable] answers a variable's value; an unknown one is inserted by name, as VS Code does.
 * Without a `$0` the final stop is at the end.
 */
fun parseSnippet(body: String, variable: (String) -> String? = { null }): ParsedSnippet {
    // Mirrors (`$1` after `${1:name}`) show their stop's text: a first pass finds it.
    val first = SnippetParser(body, variable, emptyMap()).also { it.parse(until = null) }
    val defaults = first.marks.groupBy { it.index }.mapValues { (_, marks) ->
        marks.maxByOrNull { it.end - it.start }?.let { first.out.substring(it.start, it.end) }.orEmpty()
    }
    val parser = SnippetParser(body, variable, defaults)
    parser.parse(until = null)
    val text = parser.out.toString()
    val byIndex = parser.marks.groupBy { it.index }
    val stops = byIndex.keys.sortedWith(compareBy({ it == 0 }, { it })).map { index ->
        val marks = byIndex.getValue(index)
        SnippetStop(index, marks.map { SnippetSpan(it.start, it.end) }.sortedBy { it.start }, marks.firstNotNullOfOrNull { it.choices })
    }
    return ParsedSnippet(text, if (stops.none { it.index == 0 }) stops + SnippetStop(0, listOf(SnippetSpan(text.length, text.length))) else stops)
}

private class SnippetParser(
    private val body: String,
    private val variable: (String) -> String?,
    /** Each stop's text, for its mirrors. */
    private val mirrorText: Map<Int, String>,
) {
    class Mark(val index: Int, val start: Int, val end: Int, val choices: List<String>?)

    val out = StringBuilder()
    val marks = mutableListOf<Mark>()
    private var at = 0

    /** Reads text until [until] (`}` inside a placeholder) or the end. */
    fun parse(until: Char?) {
        while (at < body.length) {
            val char = body[at]
            when {
                char == until -> return
                char == '\\' && at + 1 < body.length && body[at + 1] in "\$}\\" -> {
                    out.append(body[at + 1])
                    at += 2
                }
                char == '$' && at + 1 < body.length && body[at + 1].isDigit() -> {
                    val start = out.length
                    val index = number(at + 1)
                    out.append(mirrorText[index].orEmpty())
                    marks += Mark(index, start, out.length, null)
                }
                char == '$' && at + 1 < body.length && isNameStart(body[at + 1]) -> {
                    at++
                    val name = name()
                    out.append(variable(name) ?: name)
                }
                char == '$' && at + 1 < body.length && body[at + 1] == '{' -> if (!braced()) {
                    out.append(char)
                    at++
                }
                else -> {
                    out.append(char)
                    at++
                }
            }
        }
    }

    /** `${…}` at [at]: a stop or a variable; false (nothing read) when it is neither. */
    private fun braced(): Boolean {
        val open = at
        at += 2
        if (at < body.length && body[at].isDigit()) {
            val index = number(at)
            val start = out.length
            when {
                at < body.length && body[at] == '}' -> {
                    at++
                    out.append(mirrorText[index].orEmpty())
                }
                at < body.length && body[at] == ':' -> {
                    at++
                    parse(until = '}')
                    at++
                }
                at < body.length && body[at] == '|' -> {
                    val choices = choices()
                    out.append(choices.firstOrNull().orEmpty())
                    marks += Mark(index, start, out.length, choices)
                    return true
                }
                // `${1/regex/format/}` transforms a stop's text; its mirror here takes it as is.
                at < body.length && body[at] == '/' -> {
                    skipToClose()
                    out.append(mirrorText[index].orEmpty())
                }
                else -> {
                    at = open
                    return false
                }
            }
            marks += Mark(index, start, out.length, null)
            return true
        }
        if (at < body.length && isNameStart(body[at])) {
            val name = name()
            val value = variable(name)
            when {
                at < body.length && body[at] == '}' -> {
                    at++
                    out.append(value ?: name)
                }
                at < body.length && body[at] == ':' -> {
                    at++
                    if (value != null) {
                        // The default isn't used: read it into a throwaway parser.
                        val skip = SnippetParser(body, variable, mirrorText).also { it.at = at; it.parse(until = '}') }
                        at = skip.at + 1
                        out.append(value)
                    } else {
                        parse(until = '}')
                        at++
                    }
                }
                at < body.length && body[at] == '/' -> {
                    val (regex, format, flags) = transform()
                    out.append(applySnippetTransform(value.orEmpty(), regex, format, flags))
                }
                else -> {
                    at = open
                    return false
                }
            }
            return true
        }
        at = open
        return false
    }

    private fun number(from: Int): Int {
        var end = from
        while (end < body.length && body[end].isDigit()) end++
        val value = body.substring(from, end).toIntOrNull() ?: 0
        at = end
        return value
    }

    private fun isNameStart(char: Char) = char == '_' || char in 'A'..'Z' || char in 'a'..'z'

    private fun name(): String {
        val start = at
        while (at < body.length && (body[at] == '_' || body[at].isLetterOrDigit())) at++
        return body.substring(start, at)
    }

    /** `|a,b|}` with `\,` and `\|` escaped. */
    private fun choices(): List<String> {
        at++
        val choices = mutableListOf<String>()
        val current = StringBuilder()
        while (at < body.length) {
            val char = body[at]
            when {
                char == '\\' && at + 1 < body.length && body[at + 1] in ",|\\\$}" -> {
                    current.append(body[at + 1])
                    at += 2
                    continue
                }
                char == ',' -> {
                    choices += current.toString()
                    current.setLength(0)
                }
                char == '|' && at + 1 < body.length && body[at + 1] == '}' -> {
                    choices += current.toString()
                    at += 2
                    return choices
                }
                else -> current.append(char)
            }
            at++
        }
        choices += current.toString()
        return choices
    }

    /** `/regex/format/flags}` after a variable. */
    private fun transform(): Triple<String, String, String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        at++
        var depth = 0
        while (at < body.length && parts.size < 2) {
            val char = body[at]
            if (char == '\\' && at + 1 < body.length && body[at + 1] == '/') {
                current.append('/')
                at += 2
                continue
            }
            // The format's `${1:/upcase}` has slashes of its own.
            if (char == '$' && at + 1 < body.length && body[at + 1] == '{') depth++
            if (char == '}' && depth > 0) depth--
            if (char == '/' && depth == 0) {
                parts += current.toString()
                current.setLength(0)
            } else {
                current.append(char)
            }
            at++
        }
        val flagsStart = at
        while (at < body.length && body[at] != '}') at++
        val flags = body.substring(flagsStart, at)
        at++
        return Triple(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, flags)
    }

    private fun skipToClose() {
        var depth = 1
        while (at < body.length && depth > 0) {
            when (body[at]) {
                '\\' -> at++
                '{' -> depth++
                '}' -> depth--
            }
            at++
        }
    }
}

/**
 * A snippet transform's replacement: [format] refers to groups as `$1`, `${1}`,
 * `${1:/upcase}` (downcase, capitalize, camelcase, pascalcase), `${1:+if}`, `${1:-else}` and
 * `${1:?if:else}`. Flags: `g` every match, `i` ignoring case.
 */
fun applySnippetTransform(value: String, regex: String, format: String, flags: String): String {
    val pattern = runCatching {
        Regex(regex, if ('i' in flags) setOf(RegexOption.IGNORE_CASE) else emptySet())
    }.getOrNull() ?: return value
    val replace: (MatchResult) -> String = { match -> formatSnippetGroups(format, match) }
    return if ('g' in flags) pattern.replace(value, replace) else pattern.find(value)?.let { match ->
        value.replaceRange(match.range, replace(match))
    } ?: value
}

private fun formatSnippetGroups(format: String, match: MatchResult): String {
    val out = StringBuilder()
    var at = 0
    fun group(index: Int) = if (index < match.groups.size) match.groups[index]?.value.orEmpty() else ""
    while (at < format.length) {
        val char = format[at]
        when {
            char == '\\' && at + 1 < format.length -> {
                out.append(
                    when (format[at + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> format[at + 1]
                    },
                )
                at += 2
            }
            char == '$' && at + 1 < format.length && format[at + 1].isDigit() -> {
                var end = at + 1
                while (end < format.length && format[end].isDigit()) end++
                out.append(group(format.substring(at + 1, end).toInt()))
                at = end
            }
            char == '$' && format.startsWith("\${", at) -> {
                val close = format.indexOf('}', at)
                if (close < 0) {
                    out.append(format.substring(at))
                    break
                }
                val inner = format.substring(at + 2, close)
                val index = inner.takeWhile(Char::isDigit).toIntOrNull()
                val rest = inner.dropWhile(Char::isDigit)
                val value = index?.let(::group).orEmpty()
                out.append(
                    when {
                        index == null -> ""
                        rest.isEmpty() -> value
                        rest == ":/upcase" -> value.uppercase()
                        rest == ":/downcase" -> value.lowercase()
                        rest == ":/capitalize" -> value.replaceFirstChar { it.uppercase() }
                        rest == ":/camelcase" -> words(value).mapIndexed { i, word -> if (i == 0) word.lowercase() else word.lowercase().replaceFirstChar { it.uppercase() } }.joinToString("")
                        rest == ":/pascalcase" -> words(value).joinToString("") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
                        rest.startsWith(":+") -> if (value.isNotEmpty()) rest.drop(2) else ""
                        rest.startsWith(":?") -> rest.drop(2).split(':', limit = 2).let { parts -> if (value.isNotEmpty()) parts[0] else parts.getOrElse(1) { "" } }
                        rest.startsWith(":-") -> value.ifEmpty { rest.drop(2) }
                        rest.startsWith(":") -> value.ifEmpty { rest.drop(1) }
                        else -> value
                    },
                )
                at = close + 1
            }
            else -> {
                out.append(char)
                at++
            }
        }
    }
    return out.toString()
}

private fun words(value: String): List<String> = value.split(Regex("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])")).filter(String::isNotEmpty)

/**
 * A snippet body ready for the line it goes into: the body's tabs become the editor's
 * indentation unit, and lines after the first get the current line's indentation, as VS Code
 * inserts snippets.
 */
fun indentSnippetBody(body: String, lineIndentation: String, indentUnit: String): String {
    val lines = body.split('\n')
    return lines.mapIndexed { index, line ->
        val leading = line.takeWhile { it == '\t' }
        val converted = indentUnit.repeat(leading.length) + line.drop(leading.length)
        if (index == 0) converted else lineIndentation + converted
    }.joinToString("\n")
}

/** What a snippet's variables read: the editor's state when it is inserted. */
data class SnippetContext(
    val filePath: String,
    val workspace: String?,
    val selectedText: String = "",
    val currentLine: String = "",
    val currentWord: String = "",
    val lineIndex: Int = 0,
    val clipboard: String? = null,
    val lineComment: String? = null,
    val blockComment: Pair<String, String>? = null,
    val now: Date = Date(),
)

/** VS Code's snippet variables; null for a name it doesn't know (the name is inserted then). */
fun snippetVariable(name: String, context: SnippetContext): String? {
    fun date(pattern: String) = SimpleDateFormat(pattern, Locale.ENGLISH).format(context.now)
    val fileName = context.filePath.substringAfterLast('/')
    return when (name) {
        "TM_SELECTED_TEXT", "SELECTION" -> context.selectedText
        "TM_CURRENT_LINE" -> context.currentLine
        "TM_CURRENT_WORD" -> context.currentWord
        "TM_LINE_INDEX" -> context.lineIndex.toString()
        "TM_LINE_NUMBER" -> (context.lineIndex + 1).toString()
        "TM_FILENAME" -> fileName
        "TM_FILENAME_BASE" -> fileName.substringBeforeLast('.', fileName)
        "TM_DIRECTORY" -> context.filePath.substringBeforeLast('/', "")
        "TM_FILEPATH" -> context.filePath
        "RELATIVE_FILEPATH" -> context.workspace?.let { root -> context.filePath.removePrefix(root.trimEnd('/') + "/") } ?: context.filePath
        "WORKSPACE_NAME" -> context.workspace?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        "WORKSPACE_FOLDER" -> context.workspace.orEmpty()
        "CLIPBOARD" -> context.clipboard.orEmpty()
        "CURRENT_YEAR" -> date("yyyy")
        "CURRENT_YEAR_SHORT" -> date("yy")
        "CURRENT_MONTH" -> date("MM")
        "CURRENT_MONTH_NAME" -> date("MMMM")
        "CURRENT_MONTH_NAME_SHORT" -> date("MMM")
        "CURRENT_DATE" -> date("dd")
        "CURRENT_DAY_NAME" -> date("EEEE")
        "CURRENT_DAY_NAME_SHORT" -> date("EEE")
        "CURRENT_HOUR" -> date("HH")
        "CURRENT_MINUTE" -> date("mm")
        "CURRENT_SECOND" -> date("ss")
        "CURRENT_SECONDS_UNIX" -> (context.now.time / 1000).toString()
        "CURRENT_TIMEZONE_OFFSET" -> date("XXX")
        "RANDOM" -> (0 until 6).joinToString("") { (0..9).random().toString() }
        "RANDOM_HEX" -> (0 until 6).joinToString("") { "0123456789abcdef".random().toString() }
        "UUID" -> UUID.randomUUID().toString()
        "LINE_COMMENT" -> context.lineComment ?: context.blockComment?.first
        "BLOCK_COMMENT_START" -> context.blockComment?.first ?: context.lineComment
        "BLOCK_COMMENT_END" -> context.blockComment?.second ?: ""
        else -> null
    }
}

/**
 * An inserted snippet while Tab moves through its stops: where each stop's spans are in the
 * document now ([onEdit] follows the edits), and which stop is active. Typing in the active
 * stop grows it; the editor copies its text to the stop's other spans (mirrors).
 */
class SnippetSession(base: Int, parsed: ParsedSnippet) {
    class Stop(val index: Int, val spans: MutableList<IntArray>, val choices: List<String>?)

    val stops: List<Stop> = parsed.stops.map { stop ->
        Stop(stop.index, stop.spans.mapTo(mutableListOf()) { intArrayOf(base + it.start, base + it.end) }, stop.choices)
    }
    /** Position in [stops]. */
    var current = 0
        private set

    val active: Stop get() = stops[current]
    val isFinal: Boolean get() = active.index == 0

    /** The snippet's whole extent now: the cursor leaving it ends the session. */
    val extent: IntRange
        get() {
            val starts = stops.flatMap { stop -> stop.spans.map { it[0] } }
            val ends = stops.flatMap { stop -> stop.spans.map { it[1] } }
            return (starts.minOrNull() ?: 0)..(ends.maxOrNull() ?: 0)
        }

    /** Moves to the next (or previous) stop; false past either end. */
    fun move(forward: Boolean): Boolean {
        val next = current + if (forward) 1 else -1
        if (next !in stops.indices) return false
        current = next
        return true
    }

    /**
     * Follows an edit at [start] that removed [removed] characters and inserted [inserted]: spans
     * after it shift, a span it falls in grows or shrinks, and the active stop's spans take typing
     * at their end too.
     */
    fun onEdit(start: Int, removed: Int, inserted: Int) {
        val end = start + removed
        val delta = inserted - removed
        stops.forEachIndexed { stopIndex, stop ->
            val growsAtEnd = stopIndex == current
            stop.spans.forEach { span ->
                val (spanStart, spanEnd) = span[0] to span[1]
                when {
                    // Before the span (an insert at its start belongs to it only when it's empty and active).
                    end < spanStart || (end == spanStart && removed == 0 && !(growsAtEnd && spanStart == spanEnd)) -> {
                        span[0] = spanStart + delta
                        span[1] = spanEnd + delta
                    }
                    // After the span (typing right after the active one extends it).
                    start > spanEnd || (start == spanEnd && !growsAtEnd) -> Unit
                    // Inside it.
                    start >= spanStart && end <= spanEnd -> span[1] = spanEnd + delta
                    // Across its edge: it keeps what's left of it.
                    else -> {
                        span[0] = minOf(spanStart, start)
                        span[1] = maxOf(start + inserted, spanEnd + delta).coerceAtLeast(span[0])
                    }
                }
            }
        }
    }
}
