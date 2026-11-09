package io.github.kinteshi.quirecode.workbench

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Editing keys for notebook cells, which are plain text fields rather than the code editor. */
internal object CellTextEdits {
    /** Tab: indents the lines of a multi-line selection, otherwise replaces the selection with [tabInsertion]. */
    fun indent(value: TextFieldValue, tabSize: Int, insertSpaces: Boolean): TextFieldValue {
        val text = value.text
        val selection = value.selection
        if (!spansLines(text, selection)) {
            val start = selection.min
            val prefix = text.substring(lineStart(text, start), start)
            val insertion = tabInsertion(prefix, tabSize, insertSpaces)
            val updated = text.substring(0, start) + insertion + text.substring(selection.max)
            return TextFieldValue(updated, TextRange(start + insertion.length))
        }
        val unit = if (insertSpaces) " ".repeat(tabSize) else "\t"
        return editLines(value) { line -> if (line.isBlank()) line else unit + line }
    }

    /** Shift+Tab: removes one indentation level (a tab, or up to [tabSize] spaces) from each selected line. */
    fun outdent(value: TextFieldValue, tabSize: Int): TextFieldValue = editLines(value) { line ->
        when {
            line.startsWith("\t") -> line.substring(1)
            else -> line.substring(line.takeWhile { it == ' ' }.length.coerceAtMost(tabSize))
        }
    }

    /** Enter: a new line with the current line's indentation, one level deeper after a trailing ':'. */
    fun newline(value: TextFieldValue, tabSize: Int, insertSpaces: Boolean): TextFieldValue {
        val text = value.text
        val start = value.selection.min
        val insertion = newlineIndentation(text.substring(lineStart(text, start), start), tabSize, insertSpaces)
        val updated = text.substring(0, start) + insertion + text.substring(value.selection.max)
        return TextFieldValue(updated, TextRange(start + insertion.length))
    }

    /** Backspace in leading spaces: back to the previous tab stop; null when it is a plain Backspace. */
    fun backspace(value: TextFieldValue, tabSize: Int): TextFieldValue? {
        if (!value.selection.collapsed) return null
        val caret = value.selection.start
        val start = lineStart(value.text, caret)
        val stop = backspaceTabStop(value.text.substring(start, caret), tabSize) ?: return null
        return TextFieldValue(value.text.removeRange(start + stop, caret), TextRange(start + stop))
    }

    private fun spansLines(text: String, selection: TextRange): Boolean =
        text.substring(selection.min, selection.max).contains('\n')

    private fun lineStart(text: String, offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1

    /** Applies [transform] to every line the selection touches and keeps the selection on the same text. */
    private fun editLines(value: TextFieldValue, transform: (String) -> String): TextFieldValue {
        val text = value.text
        val selection = value.selection
        val first = lineStart(text, selection.min)
        // A selection ending at the start of a line doesn't include that line (as in VS Code).
        val endOffset = if (selection.max > selection.min && lineStart(text, selection.max) == selection.max) selection.max - 1 else selection.max
        val lastEnd = text.indexOf('\n', endOffset).let { if (it < 0) text.length else it }
        val lines = text.substring(first, lastEnd).split('\n')
        val edited = lines.map(transform)
        val totalShift = edited.sumOf { it.length } - lines.sumOf { it.length }
        // Each selection end keeps its place in its line's text; one at column 0 of the selection's
        // first line stays there so the selection grows to cover the new indentation.
        fun mapOffset(offset: Int, keepLineStart: Boolean): Int {
            var oldStart = first
            var newStart = first
            lines.forEachIndexed { index, line ->
                if (offset <= oldStart + line.length) {
                    val column = offset - oldStart
                    val delta = edited[index].length - line.length
                    val newColumn = when {
                        delta > 0 && column == 0 && keepLineStart -> 0
                        else -> (column + delta).coerceAtLeast(0)
                    }
                    return newStart + newColumn
                }
                oldStart += line.length + 1
                newStart += edited[index].length + 1
            }
            return offset + totalShift
        }
        val updated = text.substring(0, first) + edited.joinToString("\n") + text.substring(lastEnd)
        val start = mapOffset(selection.start, keepLineStart = selection.start == selection.min && !selection.collapsed)
        val end = mapOffset(selection.end, keepLineStart = selection.end == selection.min && !selection.collapsed)
        return TextFieldValue(updated, TextRange(start, end))
    }
}
