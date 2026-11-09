package io.github.kinteshi.quirecode.editor

/** A region that can fold: [startLine] stays visible, the lines after it up to [endLine] hide. */
data class FoldRange(val startLine: Int, val endLine: Int)

/**
 * Folding regions from indentation, VS Code's fallback when no language server provides them:
 * a line followed by more indented lines folds them. Blank lines at the end of a region stay out
 * of it, so the line after a folded function keeps its gap.
 */
fun indentationFoldingRanges(lines: List<String>, tabSize: Int, maxRanges: Int = 5000): List<FoldRange> {
    fun indent(line: String): Int? {
        if (line.isBlank()) return null
        var width = 0
        for (char in line) {
            width += when (char) {
                ' ' -> 1
                '\t' -> tabSize - width % tabSize
                else -> return width
            }
        }
        return null
    }
    val indents = lines.map(::indent)
    val ranges = mutableListOf<FoldRange>()
    // Open headers (line, indent), innermost last; a line at or left of a header's indent ends it.
    val open = ArrayDeque<Pair<Int, Int>>()
    var lastNonBlank = -1
    fun close(upTo: Int) {
        while (open.isNotEmpty() && open.last().second >= upTo) {
            val (header, _) = open.removeLast()
            if (lastNonBlank > header && ranges.size < maxRanges) ranges += FoldRange(header, lastNonBlank)
        }
    }
    indents.forEachIndexed { index, width ->
        if (width == null) return@forEachIndexed
        close(width)
        open.addLast(index to width)
        lastNonBlank = index
    }
    close(Int.MIN_VALUE)
    return ranges.sortedBy { it.startLine }
}
