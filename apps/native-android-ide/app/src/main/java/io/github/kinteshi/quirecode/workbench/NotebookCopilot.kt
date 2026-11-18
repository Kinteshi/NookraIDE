package io.github.kinteshi.quirecode.workbench

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import io.github.kinteshi.quirecode.language.CopilotSuggestion
import io.github.kinteshi.quirecode.notebook.CellType
import io.github.kinteshi.quirecode.notebook.NotebookCell

/**
 * Copilot sees a notebook as one Python file in Jupytext "percent" form (`# %%` per cell, Markdown
 * as comments), so every cell is context for the one being edited. [startLines] maps a cell id to
 * the line its source starts on.
 */
internal class NotebookMirror(val text: String, val startLines: Map<String, Int>)

internal fun notebookMirror(cells: List<NotebookCell>, forLanguageServer: Boolean = false): NotebookMirror {
    val text = StringBuilder()
    val starts = mutableMapOf<String, Int>()
    var line = 0
    cells.forEach { cell ->
        text.append(if (cell.type == CellType.Code) "# %%\n" else "# %% [markdown]\n")
        line++
        starts[cell.id] = line
        val body = when {
            cell.type != CellType.Code -> cell.source.lines().joinToString("\n") { "# $it" }
            forLanguageServer -> withoutMagics(cell.source)
            else -> cell.source
        }
        text.append(body).append("\n\n")
        line += body.count { it == '\n' } + 2
    }
    return NotebookMirror(text.toString(), starts)
}

/**
 * IPython's magics (`%pip`, `%%time`) and shell escapes (`!ls`) as comments, same length, so a
 * Python language server reads the rest of the cell and its columns still match.
 */
internal fun withoutMagics(source: String): String = source.split('\n').joinToString("\n") { line ->
    val code = line.trimStart()
    if (code.startsWith("%") || code.startsWith("!")) {
        val indent = line.length - code.length
        line.substring(0, indent) + "#" + code.substring(1)
    } else {
        line
    }
}

/** Names IPython defines in every notebook; a plain Python server reports them as undefined. */
internal val IPYTHON_NAMES = setOf("display", "get_ipython", "In", "Out", "exit", "quit")

/** Whether a server's problem in a notebook only comes from it being a notebook (top-level await, IPython's names). */
internal fun isNotebookOnlyProblem(message: String): Boolean =
    IPYTHON_NAMES.any { name -> message.contains("`$name`") || message.contains("\"$name\"") || message.contains("'$name'") } ||
        (message.contains("await", ignoreCase = true) && message.contains("async", ignoreCase = true))

/** Line and column of [offset] in [source]. */
internal fun cellPosition(source: String, offset: Int): Pair<Int, Int> {
    val before = source.substring(0, offset.coerceIn(0, source.length))
    return before.count { it == '\n' } to before.length - (before.lastIndexOf('\n') + 1)
}

/** Offset of [line]:[column] in [source], or null outside it (a column past the end clamps). */
internal fun cellOffset(source: String, line: Int, column: Int): Int? {
    val lines = source.split('\n')
    if (line !in lines.indices) return null
    return lines.take(line).sumOf { it.length + 1 } + column.coerceIn(0, lines[line].length)
}

/** A Copilot suggestion for one cell: replace [start]..[end] of its source with [text]. */
internal data class CellSuggestion(val cellId: String, val start: Int, val end: Int, val text: String, val source: CopilotSuggestion)

internal fun cellSuggestion(cell: NotebookCell, startLine: Int, suggestion: CopilotSuggestion): CellSuggestion? {
    val range = suggestion.range
    val start = cellOffset(cell.source, range.start.line - startLine, range.start.character) ?: return null
    val end = cellOffset(cell.source, range.end.line - startLine, range.end.character) ?: return null
    return CellSuggestion(cell.id, start, end, suggestion.insertText, suggestion)
}

/** What to draw after the caret: the suggestion minus what is already typed, or null if it no longer fits. */
internal fun cellGhostText(source: String, caret: Int, suggestion: CellSuggestion): String? {
    if (caret < suggestion.start || caret > source.length) return null
    return ghostAfterTyped(source.substring(suggestion.start, caret), suggestion.text)?.takeIf { it.isNotEmpty() }
}

/** The next logical word chunk from inline ghost [text] for partial acceptance (`Ctrl+Right`). */
internal fun nextInlineCompletionWord(text: String): String {
    if (text.isEmpty()) return ""
    var index = 0
    if (text.startsWith("\r\n")) {
        index = 2
    } else if (text[0] == '\n') {
        index = 1
    }
    if (index > 0 && (index == text.length || text[index] == '\r' || text[index] == '\n')) {
        return text.substring(0, index)
    }
    while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
    if (index == text.length || text[index] == '\r' || text[index] == '\n') {
        return text.substring(0, index)
    }
    fun isWord(char: Char) = char.isLetterOrDigit() || char == '_'
    if (isWord(text[index])) {
        while (index < text.length && isWord(text[index])) index++
    } else {
        while (index < text.length && !text[index].isWhitespace() && !isWord(text[index])) index++
    }
    return text.substring(0, index)
}

/** Draws [ghost] after [caret] in [color] without changing the text the field edits. */
internal class GhostTextTransformation(
    private val base: VisualTransformation,
    private val caret: Int,
    private val ghost: String,
    private val color: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = base.filter(text).text
        val at = caret.coerceIn(0, styled.length)
        val shown = buildAnnotatedString {
            append(styled.subSequence(0, at))
            withStyle(SpanStyle(color = color, fontStyle = FontStyle.Italic)) { append(ghost) }
            append(styled.subSequence(at, styled.length))
        }
        val length = ghost.length
        return TransformedText(shown, object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset <= at) offset else offset + length
            override fun transformedToOriginal(offset: Int) = when {
                offset <= at -> offset
                offset <= at + length -> at
                else -> offset - length
            }
        })
    }
}
