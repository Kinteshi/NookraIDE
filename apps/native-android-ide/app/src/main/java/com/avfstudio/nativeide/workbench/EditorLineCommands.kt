package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.document.DocumentSession
import io.github.rosemoe.sora.widget.CodeEditor

internal fun editCurrentLine(
    document: DocumentSession,
    editor: CodeEditor,
    commandId: String,
): Boolean {
    val lines = document.text.split('\n').toMutableList()
    val row = editor.cursor.leftLine.coerceIn(lines.indices)
    val column = editor.cursor.leftColumn
    var targetRow = row
    when (commandId) {
        "editor.action.moveLinesUpAction" -> {
            if (row == 0) return false
            lines.add(row - 1, lines.removeAt(row))
            targetRow = row - 1
        }
        "editor.action.moveLinesDownAction" -> {
            if (row == lines.lastIndex) return false
            lines.add(row + 1, lines.removeAt(row))
            targetRow = row + 1
        }
        "editor.action.copyLinesUpAction" -> lines.add(row, lines[row])
        "editor.action.copyLinesDownAction" -> {
            lines.add(row + 1, lines[row])
            targetRow = row + 1
        }
        "editor.action.deleteLines" -> {
            lines.removeAt(row)
            if (lines.isEmpty()) lines.add("")
            targetRow = row.coerceAtMost(lines.lastIndex)
        }
        "editor.action.insertLineAfter" -> {
            lines.add(row + 1, "")
            targetRow = row + 1
        }
        "editor.action.insertLineBefore" -> {
            lines.add(row, "")
            targetRow = row
        }
        else -> return false
    }
    val updated = lines.joinToString("\n")
    document.updateText(updated)
    editor.setText(updated)
    editor.setSelection(targetRow, column.coerceAtMost(lines[targetRow].length))
    return true
}

internal fun jumpToMatchingBracket(document: DocumentSession, editor: CodeEditor): Boolean {
    val text = document.text
    val cursor = editor.cursor.right.coerceIn(0, text.length)
    val pairs = mapOf('(' to ')', '[' to ']', '{' to '}')
    val bracketIndex = when {
        cursor < text.length && (text[cursor] in pairs || text[cursor] in pairs.values) -> cursor
        cursor > 0 && (text[cursor - 1] in pairs || text[cursor - 1] in pairs.values) -> cursor - 1
        else -> return false
    }
    val bracket = text[bracketIndex]
    val opening = bracket in pairs
    val openChar = if (opening) bracket else pairs.entries.first { it.value == bracket }.key
    val closeChar = pairs.getValue(openChar)
    val step = if (opening) 1 else -1
    var depth = 0
    var index = bracketIndex
    while (index in text.indices) {
        if (opening) {
            when (text[index]) {
                openChar -> depth++
                closeChar -> depth--
            }
        } else {
            when (text[index]) {
                closeChar -> depth++
                openChar -> depth--
            }
        }
        if (depth == 0) {
            val prefix = text.substring(0, index)
            val line = prefix.count { it == '\n' }
            val column = prefix.length - (prefix.lastIndexOf('\n') + 1)
            editor.setSelection(line, column)
            editor.ensureSelectionVisible()
            return true
        }
        index += step
    }
    return false
}


/** What Tab inserts at the end of [linePrefix]: spaces up to the next tab stop, or a tab. */
internal fun tabInsertion(linePrefix: String, tabSize: Int, insertSpaces: Boolean): String {
    if (!insertSpaces) return "\t"
    val visualColumn = linePrefix.fold(0) { column, char -> if (char == '\t') column + tabSize - column % tabSize else column + 1 }
    return " ".repeat(tabSize - visualColumn % tabSize)
}

/**
 * Tab as in VS Code: a selection across lines indents those lines (not a last line the selection
 * only touches at column 0, and not blank lines); otherwise the selection is replaced by
 * [tabInsertion].
 */
internal fun indentOrInsertTab(editor: CodeEditor, tabSize: Int, insertSpaces: Boolean) {
    val cursor = editor.cursor
    val text = editor.text
    if (!cursor.isSelected || cursor.leftLine == cursor.rightLine) {
        val prefix = text.getLineString(cursor.leftLine).substring(0, cursor.leftColumn)
        editor.commitText(tabInsertion(prefix, tabSize, insertSpaces))
        return
    }
    val unit = if (insertSpaces) " ".repeat(tabSize) else "\t"
    val (startLine, startColumn, endLine, endColumn) = listOf(cursor.leftLine, cursor.leftColumn, cursor.rightLine, cursor.rightColumn)
    val lastLine = if (endColumn == 0) endLine - 1 else endLine
    val indented = (startLine..lastLine).filter { text.getColumnCount(it) > 0 }
    text.beginBatchEdit()
    try {
        indented.forEach { text.insert(it, 0, unit) }
    } finally {
        text.endBatchEdit()
    }
    editor.setSelectionRegion(
        startLine,
        startColumn + if (startLine in indented && startColumn > 0) unit.length else 0,
        endLine,
        endColumn + if (endLine in indented) unit.length else 0,
    )
}

/**
 * What Enter inserts after [linePrefix] (the line up to the cursor): a newline with the line's
 * indentation, one level deeper after a block opener (`:` in Python, or an open bracket).
 */
internal fun newlineIndentation(linePrefix: String, tabSize: Int, insertSpaces: Boolean): String {
    var indent = linePrefix.takeWhile { it == ' ' || it == '\t' }
    val last = linePrefix.trimEnd().lastOrNull()
    if (last == ':' || last == '{' || last == '[' || last == '(') indent += if (insertSpaces) " ".repeat(tabSize) else "\t"
    return "\n" + indent
}

/**
 * The ghost text for a suggestion that replaces [typed] (the line from the suggestion's start to
 * the cursor) with [insertText]: what follows what is already typed. When only indentation is
 * typed, Copilot may indent differently (accepting replaces the whole range); show the code part.
 */
internal fun ghostAfterTyped(typed: String, insertText: String): String? = when {
    insertText.startsWith(typed) -> insertText.substring(typed.length)
    typed.isBlank() -> insertText.trimStart(' ', '\t')
    else -> null
}
