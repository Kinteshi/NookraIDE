package com.avfstudio.nativeide.workbench

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import com.avfstudio.nativeide.language.CopilotSuggestion
import com.avfstudio.nativeide.notebook.CellType
import com.avfstudio.nativeide.notebook.NotebookCell

/**
 * Copilot sees a notebook as one Python file in Jupytext "percent" form (`# %%` per cell, Markdown
 * as comments), so every cell is context for the one being edited. [startLines] maps a cell id to
 * the line its source starts on.
 */
internal class NotebookMirror(val text: String, val startLines: Map<String, Int>)

internal fun notebookMirror(cells: List<NotebookCell>): NotebookMirror {
    val text = StringBuilder()
    val starts = mutableMapOf<String, Int>()
    var line = 0
    cells.forEach { cell ->
        text.append(if (cell.type == CellType.Code) "# %%\n" else "# %% [markdown]\n")
        line++
        starts[cell.id] = line
        val body = if (cell.type == CellType.Code) cell.source else cell.source.lines().joinToString("\n") { "# $it" }
        text.append(body).append("\n\n")
        line += body.count { it == '\n' } + 2
    }
    return NotebookMirror(text.toString(), starts)
}

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
