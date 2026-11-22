package io.github.kinteshi.nookraide.editor

/** VS Code's save-time clean-ups: `files.trimTrailingWhitespace`, `insertFinalNewline`, `trimFinalNewlines`. */
data class SaveCleanups(
    val trimTrailingWhitespace: Boolean = false,
    val insertFinalNewline: Boolean = false,
    val trimFinalNewlines: Boolean = false,
) {
    val any: Boolean get() = trimTrailingWhitespace || insertFinalNewline || trimFinalNewlines
}

/**
 * The edits [cleanups] make to [text], whose lines end with [eol]. [keepLine] keeps its trailing
 * whitespace: VS Code leaves the cursor's line alone when it saves automatically, so typing on
 * isn't undone under the cursor. Edits don't overlap and use UTF-16 columns.
 */
fun saveCleanupEdits(text: String, cleanups: SaveCleanups, eol: String = "\n", keepLine: Int? = null): List<LspTextEdit> {
    if (!cleanups.any || text.isEmpty()) return emptyList()
    // Line contents without their line break ("\r" belongs to a CRLF break, not to the line).
    val lines = text.split('\n').map { it.removeSuffix("\r") }
    val endsWithNewline = text.endsWith('\n')
    fun contentEnd(line: String) = line.trimEnd(' ', '\t').length
    val lastContent = lines.indexOfLast { it.isNotBlank() }
    val edits = mutableListOf<LspTextEdit>()

    // Everything after the last line with content collapses to one line break.
    val collapseTail = cleanups.trimFinalNewlines && lastContent >= 0 &&
        lines.size - lastContent > (if (endsWithNewline) 2 else 1)
    val trimUntil = if (collapseTail) lastContent - 1 else lines.lastIndex
    if (cleanups.trimTrailingWhitespace) {
        for (index in 0..trimUntil) {
            if (index == keepLine) continue
            val line = lines[index]
            val end = contentEnd(line)
            if (end < line.length) edits += LspTextEdit(LspRange(LspPosition(index, end), LspPosition(index, line.length)), "")
        }
    }
    val lastLine = lines.lastIndex
    val documentEnd = LspPosition(lastLine, lines[lastLine].length)
    if (collapseTail) {
        val line = lines[lastContent]
        val start = if (cleanups.trimTrailingWhitespace && lastContent != keepLine) contentEnd(line) else line.length
        val keepBreak = endsWithNewline || cleanups.insertFinalNewline
        edits += LspTextEdit(LspRange(LspPosition(lastContent, start), documentEnd), if (keepBreak) eol else "")
    } else if (cleanups.insertFinalNewline && !endsWithNewline && lastContent >= 0) {
        edits += LspTextEdit(LspRange(documentEnd, documentEnd), eol)
    }
    return edits
}
