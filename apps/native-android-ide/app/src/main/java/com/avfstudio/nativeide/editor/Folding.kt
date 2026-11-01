package com.avfstudio.nativeide.editor

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

/**
 * The folded regions of one editor and the rows they leave: which lines are hidden, and the row a
 * line is drawn on (folded lines take their header's row). Regions may nest; an edit inside a
 * folded region, or one that splits or joins its first line, unfolds it.
 */
class FoldState {
    private val folds = mutableListOf<FoldRange>()
    /** Hidden line runs, sorted and merged: first and last line of each. */
    private var hidden: IntArray = IntArray(0)

    val folded: List<FoldRange> get() = folds.toList()
    val isEmpty: Boolean get() = folds.isEmpty()
    val hiddenLineCount: Int get() = (hidden.indices step 2).sumOf { hidden[it + 1] - hidden[it] + 1 }

    fun isFolded(startLine: Int): Boolean = folds.any { it.startLine == startLine }

    fun isHidden(line: Int): Boolean {
        for (i in hidden.indices step 2) {
            if (line < hidden[i]) return false
            if (line <= hidden[i + 1]) return true
        }
        return false
    }

    /** The row [line] is drawn on; a hidden line gives its fold's first line's row. */
    fun rowForLine(line: Int): Int {
        var before = 0
        for (i in hidden.indices step 2) {
            val first = hidden[i]
            val last = hidden[i + 1]
            if (line < first) break
            if (line <= last) return first - 1 - before
            before += last - first + 1
        }
        return line - before
    }

    fun lineForRow(row: Int): Int {
        var line = row
        for (i in hidden.indices step 2) {
            if (hidden[i] <= line) line += hidden[i + 1] - hidden[i] + 1 else break
        }
        return line
    }

    /** The nearest visible line at or before [line]. */
    fun visibleLineAtOrBefore(line: Int): Int = lineForRow(rowForLine(line))

    fun fold(range: FoldRange): Boolean {
        if (range.endLine <= range.startLine || folds.any { it.startLine == range.startLine }) return false
        folds += range
        rebuild()
        return true
    }

    fun unfold(startLine: Int): Boolean = folds.removeAll { it.startLine == startLine }.also { if (it) rebuild() }

    /** Unfolds every region hiding [line] (the cursor went there). */
    fun unfoldAround(line: Int): Boolean =
        folds.removeAll { line in it.startLine + 1..it.endLine }.also { if (it) rebuild() }

    fun clear(): Boolean = folds.isNotEmpty().also {
        folds.clear()
        rebuild()
    }

    /**
     * After an edit on [line] that added [lineDelta] lines (negative: removed, the old lines
     * [line]..[line] - lineDelta): folds below it move, folds it touches open.
     */
    fun afterEdit(line: Int, lineDelta: Int): Boolean {
        val removedEnd = line + maxOf(0, -lineDelta)
        var changed = false
        val updated = folds.mapNotNull { fold ->
            when {
                fold.endLine < line -> fold
                fold.startLine > removedEnd -> {
                    if (lineDelta != 0) changed = true
                    FoldRange(fold.startLine + lineDelta, fold.endLine + lineDelta)
                }
                fold.startLine == line && lineDelta == 0 -> fold
                else -> {
                    changed = true
                    null
                }
            }
        }
        if (changed) {
            folds.clear()
            folds += updated
            rebuild()
        }
        return changed
    }

    private fun rebuild() {
        val runs = folds.map { it.startLine + 1 to it.endLine }.sortedBy { it.first }
        val merged = mutableListOf<IntArray>()
        for ((first, last) in runs) {
            val previous = merged.lastOrNull()
            if (previous != null && first <= previous[1] + 1) {
                previous[1] = maxOf(previous[1], last)
            } else {
                merged += intArrayOf(first, last)
            }
        }
        hidden = merged.flatMap { listOf(it[0], it[1]) }.toIntArray()
    }
}
