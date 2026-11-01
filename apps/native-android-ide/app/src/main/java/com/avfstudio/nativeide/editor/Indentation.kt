package com.avfstudio.nativeide.editor

/** How a file is indented: with spaces ([tabSize] of them per level) or with tabs [tabSize] wide. */
data class Indentation(val insertSpaces: Boolean, val tabSize: Int) {
    /** The status bar's label, as VS Code shows it. */
    val label: String get() = if (insertSpaces) "Spaces: $tabSize" else "Tab Size: $tabSize"
}

/**
 * Guesses [text]'s indentation as VS Code's `editor.detectIndentation` does: tabs or spaces by
 * which more lines start with, and the tab size from the most common step in leading spaces
 * between neighbouring lines. Null when nothing is indented, so the settings apply.
 */
fun detectIndentation(text: String, defaultTabSize: Int, maxLines: Int = 10_000): Indentation? {
    var tabLines = 0
    var spaceLines = 0
    val steps = IntArray(9)
    var previousSpaces = 0
    var previousTabbed = false
    var inBlockString = false
    var count = 0
    for (rawLine in text.lineSequence()) {
        if (++count > maxLines) break
        val line = rawLine.removeSuffix("\r")
        // Lines inside a Python docstring often keep any indentation; leave them out.
        val quotes = line.windowed(3).count { it == "\"\"\"" || it == "'''" }
        val skip = inBlockString
        if (quotes % 2 == 1) inBlockString = !inBlockString
        if (skip || line.isBlank()) continue
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        val tabs = leading.count { it == '\t' }
        val spaces = leading.length - tabs
        when {
            tabs > 0 && spaces == 0 -> tabLines++
            spaces > 1 && tabs == 0 -> spaceLines++
        }
        if (tabs == 0 && !previousTabbed) {
            val step = kotlin.math.abs(spaces - previousSpaces)
            if (step in 1..8) steps[step]++
        }
        previousSpaces = if (tabs == 0) spaces else 0
        previousTabbed = tabs > 0
    }
    if (tabLines == 0 && spaceLines == 0) return null
    if (tabLines > spaceLines) return Indentation(insertSpaces = false, tabSize = defaultTabSize)
    // A file indented by 4 also has steps of 8 (closing two levels); count multiples toward the smaller size.
    val candidates = listOf(2, 4, 8, 3, 6, 5, 7)
    val best = candidates.maxByOrNull { size ->
        steps[size] + (if (size * 2 <= 8) steps[size * 2] / 2 else 0)
    }?.takeIf { steps[it] > 0 }
    return Indentation(insertSpaces = true, tabSize = best ?: defaultTabSize)
}

/** [text] re-indented to [target]: each line's leading run, read with [from], written in the new unit. */
fun convertIndentation(text: String, from: Indentation, target: Indentation): String = text.split('\n').joinToString("\n") { line ->
    val leading = line.takeWhile { it == ' ' || it == '\t' }
    if (leading.isEmpty()) return@joinToString line
    val width = leading.fold(0) { column, char -> if (char == '\t') column + from.tabSize - column % from.tabSize else column + 1 }
    val indent = if (target.insertSpaces) {
        " ".repeat(width / from.tabSize * target.tabSize + width % from.tabSize)
    } else {
        "\t".repeat(width / from.tabSize) + " ".repeat(width % from.tabSize)
    }
    indent + line.substring(leading.length)
}
