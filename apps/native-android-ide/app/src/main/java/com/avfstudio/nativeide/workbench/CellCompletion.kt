package com.avfstudio.nativeide.workbench

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Kernel completions for one cell: [items] replace the text from [start] to the caret. */
internal data class CellCompletion(val cellId: String, val start: Int, val items: List<String>, val selected: Int = 0)

/** The items still matching what was typed since the popup opened, or null when it should close. */
internal fun CellCompletion.visibleItems(value: TextFieldValue): List<String>? {
    val caret = value.selection.start
    if (!value.selection.collapsed || caret < start || caret > value.text.length) return null
    val typed = value.text.substring(start, caret)
    return items.filter { it.startsWith(typed) }.takeIf { it.isNotEmpty() }
}

/** The cell text with the [index]-th visible item accepted, caret after it. */
internal fun CellCompletion.accept(value: TextFieldValue, index: Int = selected): TextFieldValue? {
    val visible = visibleItems(value) ?: return null
    val choice = visible[index.coerceIn(visible.indices)]
    val caret = value.selection.start
    return TextFieldValue(value.text.substring(0, start) + choice + value.text.substring(caret), TextRange(start + choice.length))
}

/** Jupyter's rule: Tab completes right after a name or a dot, and indents anywhere else. */
internal fun tabCompletes(value: TextFieldValue): Boolean {
    val caret = value.selection.start
    if (!value.selection.collapsed || caret == 0) return false
    val before = value.text[caret - 1]
    return before.isLetterOrDigit() || before == '_' || before == '.'
}
