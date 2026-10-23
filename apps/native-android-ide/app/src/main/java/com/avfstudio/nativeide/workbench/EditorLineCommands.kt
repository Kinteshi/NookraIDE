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

