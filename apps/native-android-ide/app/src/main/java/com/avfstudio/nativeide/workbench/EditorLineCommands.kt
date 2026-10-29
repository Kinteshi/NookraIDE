package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.document.DocumentSession
import com.avfstudio.nativeide.editor.EditorSelection
import com.avfstudio.nativeide.editor.EditorSurface
import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.editor.LspTextEdit
import com.avfstudio.nativeide.editor.selectionEnd
import com.avfstudio.nativeide.editor.selectionStart

internal fun editCurrentLine(
    document: DocumentSession,
    editor: EditorSurface,
    commandId: String,
): Boolean {
    val lines = document.text.split('\n').toMutableList()
    val start = editor.selectionStart()
    val row = start.line.coerceIn(lines.indices)
    val column = start.character
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
    editor.moveCursor(LspPosition(targetRow, column.coerceAtMost(lines[targetRow].length)))
    return true
}

internal fun jumpToMatchingBracket(document: DocumentSession, editor: EditorSurface): Boolean {
    val text = document.text
    val cursor = editor.selectionOffsets.last.coerceIn(0, text.length)
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
            editor.moveCursor(LspPosition(line, column))
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
internal fun indentOrInsertTab(editor: EditorSurface, tabSize: Int, insertSpaces: Boolean) {
    val start = editor.selectionStart()
    val end = editor.selectionEnd()
    if (start.line == end.line) {
        val prefix = editor.lineText(start.line).substring(0, start.character)
        editor.insertAtCursor(tabInsertion(prefix, tabSize, insertSpaces))
        return
    }
    val unit = if (insertSpaces) " ".repeat(tabSize) else "\t"
    val lastLine = if (end.character == 0) end.line - 1 else end.line
    val indented = (start.line..lastLine).filter { editor.lineText(it).isNotEmpty() }
    editor.applyEdits(indented.map { line -> LspTextEdit(LspRange(LspPosition(line, 0), LspPosition(line, 0)), unit) })
    editor.setSelection(
        EditorSelection(
            LspPosition(start.line, start.character + if (start.line in indented && start.character > 0) unit.length else 0),
            LspPosition(end.line, end.character + if (end.line in indented) unit.length else 0),
        ),
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
 * Where Backspace deletes back to when [linePrefix] (the line before the cursor) is only spaces:
 * the previous tab stop, so one press removes one indentation level (VS Code's
 * `editor.useTabStops`). Null when there is other text or a single space: a plain Backspace.
 */
internal fun backspaceTabStop(linePrefix: String, tabSize: Int): Int? {
    if (linePrefix.length < 2 || tabSize < 2 || linePrefix.any { it != ' ' }) return null
    return (linePrefix.length - 1) / tabSize * tabSize
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
