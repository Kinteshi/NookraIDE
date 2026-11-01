package com.avfstudio.nativeide.workbench

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A terminal's output as plain lines, for finding text and links in it: escape sequences
 * (colours, cursor moves, titles) are dropped, `\r` starts the line over (progress bars keep
 * their last state) and backspace takes a character back. The terminal library keeps its own
 * screen; this copy only reads the same bytes. Older lines go past [maxLines].
 */
class TerminalTextLog(private val maxLines: Int = 10_000) {
    private val lines = ArrayDeque<String>()
    private val current = StringBuilder()
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)
    private var state = State.Text
    /** Lines added since the start, so a list of matches can tell when it went stale. */
    var revision = 0L
        private set

    private enum class State { Text, Escape, Csi, Osc, OscEscape, Charset }

    @Synchronized
    fun append(bytes: ByteArray) {
        val input = ByteBuffer.wrap(pending + bytes)
        val chars = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, chars, false)
        pending = ByteArray(input.remaining()).also(input::get)
        chars.flip()
        while (chars.hasRemaining()) accept(chars.get())
        revision++
    }

    private fun accept(char: Char) {
        when (state) {
            State.Escape -> state = when (char) {
                '[' -> State.Csi
                ']', 'P', '_', '^' -> State.Osc
                '(', ')', '*', '+', '#', '%' -> State.Charset
                else -> State.Text
            }
            State.Csi -> if (char in '@'..'~') state = State.Text
            State.Osc -> when (char) {
                '\u0007' -> state = State.Text
                '\u001b' -> state = State.OscEscape
            }
            State.OscEscape -> state = if (char == '\\') State.Text else State.Osc
            State.Charset -> state = State.Text
            State.Text -> when (char) {
                '\u001b' -> state = State.Escape
                '\n' -> commit()
                '\r' -> carriage = true
                '\b' -> if (current.isNotEmpty()) current.setLength(current.length - 1)
                '\t' -> overwrite("    ")
                else -> if (char >= ' ') overwrite(char.toString())
            }
        }
    }

    /** A `\r` not followed by `\n` means the next text replaces the line (a progress bar's redraw). */
    private var carriage = false

    private fun overwrite(text: String) {
        if (carriage) {
            current.setLength(0)
            carriage = false
        }
        current.append(text)
    }

    private fun commit() {
        carriage = false
        lines.addLast(current.toString())
        current.setLength(0)
        while (lines.size > maxLines) lines.removeFirst()
    }

    /** All lines, oldest first, with the line being written last. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList() + listOfNotNull(current.toString().takeIf(String::isNotEmpty))

    @Synchronized
    fun clear() {
        lines.clear()
        current.setLength(0)
        carriage = false
        state = State.Text
        revision++
    }
}

/** A line of the terminal's output that has the find text. */
data class TerminalMatch(val line: Int, val text: String, val ranges: List<IntRange>)

fun findInTerminal(lines: List<String>, query: String, caseSensitive: Boolean, limit: Int = 500): List<TerminalMatch> {
    if (query.isEmpty()) return emptyList()
    val matches = mutableListOf<TerminalMatch>()
    lines.forEachIndexed { index, line ->
        val ranges = mutableListOf<IntRange>()
        var from = line.indexOf(query, 0, ignoreCase = !caseSensitive)
        while (from >= 0) {
            ranges += from until from + query.length
            from = line.indexOf(query, from + query.length, ignoreCase = !caseSensitive)
        }
        if (ranges.isNotEmpty()) matches += TerminalMatch(index, line, ranges)
    }
    return matches.takeLast(limit)
}

/** A link in terminal output: a web address, or a file with an optional line and column. */
sealed interface TerminalLink {
    val label: String

    data class Web(val url: String) : TerminalLink {
        override val label: String get() = url
    }

    data class File(val path: String, val line: Int?, val column: Int?) : TerminalLink {
        override val label: String get() = path + (line?.let { ":$it" } ?: "") + (column?.let { ":$it" } ?: "")
    }
}

private val webLink = Regex("""https?://[^\s<>"'`]+""")
/** Python's `File "/x/y.py", line 12`. */
private val pythonFrame = Regex("""File "([^"]+)", line (\d+)""")
/** `src/app.py:12:5`, `./x.ts:3`, `/abs/x.rs:10` (compilers, linters, test runners): a name with an extension, then a line. */
private val fileLine = Regex("""(?<![\w/.~-])((?:~|\.{1,2})?/?[\w.\-]+(?:/[\w.\-]+)*\.[A-Za-z][A-Za-z0-9]{0,9}):(\d+)(?::(\d+))?""")

/**
 * The links in [lines], newest first, each once: VS Code's "Open Detected Link". Web addresses
 * lose trailing punctuation; file links need a line number, which keeps out plain words with dots.
 */
fun detectTerminalLinks(lines: List<String>, limit: Int = 200): List<TerminalLink> {
    val links = LinkedHashMap<String, TerminalLink>()
    for (line in lines.asReversed()) {
        val found = mutableListOf<Pair<Int, TerminalLink>>()
        webLink.findAll(line).forEach { match ->
            found += match.range.first to TerminalLink.Web(match.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '\'', '"'))
        }
        pythonFrame.findAll(line).forEach { match ->
            found += match.range.first to TerminalLink.File(match.groupValues[1], match.groupValues[2].toInt(), null)
        }
        fileLine.findAll(line).forEach { match ->
            if (webLink.findAll(line).any { match.range.first in it.range }) return@forEach
            found += match.range.first to TerminalLink.File(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toIntOrNull())
        }
        found.sortedBy { it.first }.forEach { (_, link) -> links.putIfAbsent(link.label, link) }
        if (links.size >= limit) break
    }
    return links.values.take(limit)
}
