package io.github.kinteshi.quirecode.workbench

import java.text.Collator
import java.util.Locale

/** Where each line of [text] starts. */
private fun lineStarts(text: String): IntArray {
    val starts = ArrayList<Int>()
    starts += 0
    text.forEachIndexed { index, char -> if (char == '\n') starts += index + 1 }
    return starts.toIntArray()
}

private fun lineOf(starts: IntArray, offset: Int): Int {
    val found = starts.binarySearch(offset)
    return if (found >= 0) found else -found - 2
}

/**
 * The lines a selection from [start] to [end] covers, as VS Code counts them: a selection that
 * ends at the start of a later line leaves that line out.
 */
private fun selectedLines(starts: IntArray, start: Int, end: Int): IntRange {
    val first = lineOf(starts, start)
    var last = lineOf(starts, end)
    if (last > first && starts[last] == end) last--
    return first..last
}

/** The lines Sort Lines and Delete Duplicate Lines work on: the selected ones, or the whole file for one line. */
private fun linesToReorder(text: String, start: Int, end: Int): Pair<IntRange, List<String>> {
    val lines = text.split('\n')
    val starts = lineStarts(text)
    var range = selectedLines(starts, start, end)
    if (range.first == range.last) {
        // The final line break leaves an empty last "line" that would sort to the top.
        val last = if (lines.size > 1 && lines.last().isEmpty()) lines.lastIndex - 1 else lines.lastIndex
        range = 0..last
    }
    return range to lines
}

private fun replaceLines(lines: List<String>, range: IntRange, replacement: List<String>): TextEdit {
    val updated = lines.subList(0, range.first) + replacement + lines.subList(range.last + 1, lines.size)
    val text = updated.joinToString("\n")
    val selectionStart = updated.subList(0, range.first).sumOf { it.length + 1 }
    val selectionEnd = selectionStart + replacement.joinToString("\n").length
    return TextEdit(text, selectionStart, selectionEnd)
}

/** Sort Lines Ascending/Descending: by the device's collation, as VS Code uses `Intl.Collator`. */
internal fun sortLines(text: String, start: Int, end: Int, descending: Boolean): TextEdit? {
    val (range, lines) = linesToReorder(text, start, end)
    if (range.last <= range.first) return null
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.TERTIARY }
    val selected = lines.subList(range.first, range.last + 1)
    val sorted = selected.sortedWith { a, b -> collator.compare(a.removeSuffix("\r"), b.removeSuffix("\r")) }
        .let { if (descending) it.asReversed() else it }
    if (sorted == selected) return null
    return replaceLines(lines, range, sorted)
}

/** Delete Duplicate Lines: keeps each line's first occurrence. */
internal fun deleteDuplicateLines(text: String, start: Int, end: Int): TextEdit? {
    val (range, lines) = linesToReorder(text, start, end)
    val selected = lines.subList(range.first, range.last + 1)
    val seen = HashSet<String>()
    val kept = selected.filter { seen.add(it.removeSuffix("\r")) }
    if (kept.size == selected.size) return null
    return replaceLines(lines, range, kept)
}

/**
 * Join Lines: the selected lines become one (with no selection, the line and the next), each
 * next line's indentation replaced by one space. The cursor goes to the first join.
 */
internal fun joinLines(text: String, start: Int, end: Int): TextEdit? {
    val starts = lineStarts(text)
    val lines = text.split('\n')
    var range = selectedLines(starts, start, end)
    if (range.first == range.last) {
        if (range.last == lines.lastIndex) return null
        range = range.first..range.first + 1
    }
    val joined = StringBuilder(lines[range.first].removeSuffix("\r"))
    var firstJoin = -1
    for (index in range.first + 1..range.last) {
        val next = lines[index].removeSuffix("\r").trimStart()
        while (joined.isNotEmpty() && (joined.last() == ' ' || joined.last() == '\t')) joined.setLength(joined.length - 1)
        if (firstJoin < 0) firstJoin = joined.length
        if (next.isNotEmpty() && joined.isNotEmpty()) joined.append(' ')
        joined.append(next)
    }
    val carriage = if (lines[range.last].endsWith("\r")) "\r" else ""
    val edit = replaceLines(lines, range, listOf(joined.toString() + carriage))
    return if (start == end) {
        val cursor = edit.selectionStart + firstJoin
        edit.copy(selectionStart = cursor, selectionEnd = cursor)
    } else {
        edit.copy(selectionEnd = edit.selectionEnd - carriage.length)
    }
}

/**
 * Transpose Characters: swaps the characters either side of the cursor and moves past them; at
 * the end of a line, the last two. Null at the start of a line.
 */
internal fun transposeCharacters(text: String, cursor: Int): TextEdit? {
    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
    var lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
    if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd--
    if (cursor <= lineStart || lineEnd - lineStart < 2) return null
    val left = if (cursor >= lineEnd) lineEnd - 2 else cursor - 1
    val swapped = StringBuilder(text).apply {
        setCharAt(left, text[left + 1])
        setCharAt(left + 1, text[left])
    }.toString()
    val after = (left + 2).coerceAtMost(lineEnd)
    return TextEdit(swapped, after, after)
}

/** Trim Trailing Whitespace (Ctrl+K Ctrl+X): every line, keeping the cursor's place. */
internal fun trimTrailingWhitespace(text: String, cursor: Int): TextEdit? {
    val trimmed = text.split('\n').joinToString("\n") { line ->
        val carriage = if (line.endsWith("\r")) "\r" else ""
        line.removeSuffix("\r").trimEnd(' ', '\t') + carriage
    }
    if (trimmed == text) return null
    // Removed characters before the cursor move it back.
    val before = text.substring(0, cursor.coerceIn(0, text.length))
    val kept = before.split('\n').let { parts ->
        parts.dropLast(1).sumOf { it.removeSuffix("\r").trimEnd(' ', '\t').length + if (it.endsWith("\r")) 2 else 1 } + parts.last().length
    }
    val position = kept.coerceAtMost(trimmed.length)
    return TextEdit(trimmed, position, position)
}

enum class CaseTransform(val title: String) {
    Upper("Uppercase"),
    Lower("Lowercase"),
    Title("Title Case"),
    Snake("Snake Case"),
    Kebab("Kebab Case"),
}

/** VS Code's command ids for the case transforms. */
internal val CASE_COMMANDS = mapOf(
    "editor.action.transformToUppercase" to CaseTransform.Upper,
    "editor.action.transformToLowercase" to CaseTransform.Lower,
    "editor.action.transformToTitlecase" to CaseTransform.Title,
    "editor.action.transformToSnakecase" to CaseTransform.Snake,
    "editor.action.transformToKebabcase" to CaseTransform.Kebab,
)

/** VS Code's Transform to …: the selection, or the word at the cursor when nothing is selected. */
internal fun transformCase(text: String, start: Int, end: Int, transform: CaseTransform): TextEdit? {
    var from = start
    var to = end
    if (from == to) {
        while (from > 0 && isIdentifierCharacter(text[from - 1])) from--
        while (to < text.length && isIdentifierCharacter(text[to])) to++
        if (from == to) return null
    }
    val original = text.substring(from, to)
    val changed = when (transform) {
        CaseTransform.Upper -> original.uppercase()
        CaseTransform.Lower -> original.lowercase()
        CaseTransform.Title -> original.lowercase().replace(Regex("(^|[^\\p{L}\\p{N}'])(\\p{L})")) { it.groupValues[1] + it.groupValues[2].uppercase() }
        CaseTransform.Snake -> original
            .replace(Regex("(\\p{Ll})(\\p{Lu})"), "$1_$2")
            .replace(Regex("(\\p{Lu})(\\p{Lu}\\p{Ll})"), "$1_$2")
            .replace(Regex("[-\\s]+"), "_")
            .lowercase()
        CaseTransform.Kebab -> original
            .replace(Regex("(\\p{Ll})(\\p{Lu})"), "$1-$2")
            .replace(Regex("(\\p{Lu})(\\p{Lu}\\p{Ll})"), "$1-$2")
            .replace(Regex("[_\\s]+"), "-")
            .lowercase()
    }
    if (changed == original) return null
    return TextEdit(text.substring(0, from) + changed + text.substring(to), from, from + changed.length)
}

private val OPENERS = mapOf('(' to ')', '[' to ']', '{' to '}')
private val CLOSERS = OPENERS.entries.associate { (open, close) -> close to open }

/**
 * Expand Selection without a language server: the ranges around [start]..[end] (as start and end
 * offsets, like `EditorSurface.selectionOffsets`), smallest first —
 * the word, a string's content and the string, each bracket's content and the brackets, the
 * line's text, the line, the indented block it heads or sits in, and the whole file.
 */
internal fun smartSelectRanges(text: String, start: Int, end: Int): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    fun add(from: Int, to: Int) {
        if (from <= start && to >= end && (from < start || to > end)) ranges += from..to
    }
    // The word.
    var wordStart = start
    while (wordStart > 0 && isIdentifierCharacter(text[wordStart - 1])) wordStart--
    var wordEnd = end
    while (wordEnd < text.length && isIdentifierCharacter(text[wordEnd])) wordEnd++
    if (wordStart < wordEnd) add(wordStart, wordEnd)
    // A string on the line: from the last unescaped quote before to the matching one after.
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    var quote: Char? = null
    var quoteStart = -1
    var index = lineStart
    while (index < lineEnd) {
        val char = text[index]
        when {
            quote != null && char == '\\' -> index++
            quote != null && char == quote -> {
                if (quoteStart < start && index >= end) {
                    add(quoteStart + 1, index)
                    add(quoteStart, index + 1)
                }
                quote = null
            }
            quote == null && (char == '"' || char == '\'' || char == '`') -> {
                quote = char
                quoteStart = index
            }
        }
        index++
    }
    // Enclosing brackets, innermost first.
    var depthSearch = start - 1
    val stack = ArrayDeque<Char>()
    while (depthSearch >= 0) {
        val char = text[depthSearch]
        if (char in CLOSERS) {
            stack.addLast(char)
        } else if (char in OPENERS) {
            if (stack.isNotEmpty() && CLOSERS[stack.last()] == char) {
                stack.removeLast()
            } else {
                val close = matchingClose(text, depthSearch)
                if (close != null && close >= end) {
                    add(depthSearch + 1, close)
                    add(depthSearch, close + 1)
                }
            }
        }
        depthSearch--
    }
    // The line's text, then the line.
    val firstLineStart = lineStart
    val lastLineEnd = lineEnd
    val content = text.substring(firstLineStart, lastLineEnd)
    add(firstLineStart + (content.length - content.trimStart().length), lastLineEnd - (content.length - content.trimEnd().length))
    add(firstLineStart, lastLineEnd)
    // The indented block: a line and the deeper lines after it, then the block the line sits in.
    val lines = text.split('\n')
    val starts = lineStarts(text)
    val firstLine = lineOf(starts, firstLineStart)
    val lastLine = lineOf(starts, lastLineEnd)
    fun indent(line: Int) = lines[line].let { if (it.isBlank()) Int.MAX_VALUE else it.length - it.trimStart().length }
    fun blockEnd(header: Int): Int {
        var last = header
        var next = header + 1
        while (next < lines.size && (lines[next].isBlank() || indent(next) > indent(header))) {
            if (!lines[next].isBlank()) last = next
            next++
        }
        return last
    }
    fun lineRangeEnd(line: Int) = starts[line] + lines[line].length
    var header = firstLine
    repeat(20) {
        val last = blockEnd(header)
        if (last >= lastLine) add(starts[header], lineRangeEnd(last))
        // The enclosing header: the nearest line above with less indentation.
        val own = indent(header)
        var above = header - 1
        while (above >= 0 && (lines[above].isBlank() || indent(above) >= own)) above--
        if (above < 0) return@repeat
        header = above
    }
    add(0, text.length)
    return ranges.distinct().sortedBy { it.last - it.first }
}

private fun matchingClose(text: String, open: Int): Int? {
    val opener = text[open]
    val closer = OPENERS[opener] ?: return null
    var depth = 0
    for (index in open until text.length) {
        when (text[index]) {
            opener -> depth++
            closer -> if (--depth == 0) return index
        }
    }
    return null
}
