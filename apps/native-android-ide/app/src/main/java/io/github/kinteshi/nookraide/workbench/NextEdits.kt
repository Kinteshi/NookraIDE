package io.github.kinteshi.nookraide.workbench

import io.github.kinteshi.nookraide.editor.LspRange

/**
 * A next edit as whole lines: the lines it touches before and after, and the changed part of
 * each (in the lines joined by `\n`), for the struck-through old text and the new text beside it.
 */
data class NextEditPreview(
    val firstLine: Int,
    val oldText: String,
    val newText: String,
    val oldChange: IntRange,
    val newChange: IntRange,
) {
    val lastLine: Int get() = firstLine + oldText.count { it == '\n' }
}

/** The preview of replacing [range] of [lines] with [text]; null when it changes nothing or the range is gone. */
fun nextEditPreview(lines: List<String>, range: LspRange, text: String): NextEditPreview? {
    val first = range.start.line
    val last = range.end.line
    if (first < 0 || last >= lines.size || last < first) return null
    val startColumn = range.start.character.coerceIn(0, lines[first].length)
    val endColumn = range.end.character.coerceIn(0, lines[last].length)
    val old = lines.subList(first, last + 1).joinToString("\n")
    val new = lines[first].substring(0, startColumn) + text + lines[last].substring(endColumn)
    if (old == new) return null
    val (oldChange, newChange) = changedSpans(old, new)
    return NextEditPreview(first, old, new, oldChange, newChange)
}

/** What Tab does with a next edit: go to it when the cursor is elsewhere, then accept it there (VS Code's). */
enum class NextEditTab { Jump, Accept }

fun nextEditTab(cursorLine: Int, range: LspRange): NextEditTab =
    if (cursorLine in range.start.line - 1..range.end.line + 1) NextEditTab.Accept else NextEditTab.Jump
