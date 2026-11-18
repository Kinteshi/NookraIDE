package io.github.kinteshi.quirecode.workbench

/**
 * A cell of a Python file, as the Jupyter extension finds them: from a `# %%` (or `# In[ ]`,
 * `# <codecell>`) line to the line before the next one. [marker] is the marker's line, [end]
 * the cell's last line (0-based); `# %% [markdown]` cells are [markdown] and never run.
 */
data class PythonCell(val marker: Int, val end: Int, val title: String, val markdown: Boolean) {
    val firstCodeLine: Int get() = marker + 1
}

private val codeMarker = Regex("""^#\s*(%%|<codecell>|In\[\s*\d*\s*]:?)(.*)$""")
private val markdownMarker = Regex("""^#\s*(%%\s*\[markdown]|<markdowncell>)""")

fun pythonCells(text: String): List<PythonCell> {
    if ('#' !in text) return emptyList()
    if ("%%" !in text && "<codecell>" !in text && "<markdowncell>" !in text && "In[" !in text) return emptyList()
    val hits = ArrayList<Triple<Int, String, Boolean>>()
    var lineIndex = 0
    var pos = 0
    val len = text.length
    while (pos <= len) {
        val nl = text.indexOf('\n', pos)
        val rawEnd = if (nl < 0) len else nl
        val trimmedEnd = if (rawEnd > pos && text[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
        // Only slice/regex lines whose trimmed start looks like a comment (`#`).
        var firstNonWs = pos
        while (firstNonWs < trimmedEnd && text[firstNonWs] == ' ') firstNonWs++
        if (firstNonWs < trimmedEnd && text[firstNonWs] == '#') {
            val raw = text.substring(pos, trimmedEnd)
            val match = codeMarker.find(raw)
            if (match != null) {
                val title = match.groupValues[2].trim().removePrefix("[markdown]").trim()
                hits.add(Triple(lineIndex, title, markdownMarker.containsMatchIn(raw)))
            }
        }
        lineIndex++
        if (nl < 0) break
        pos = nl + 1
    }
    if (hits.isEmpty()) return emptyList()
    return hits.mapIndexed { idx, (markerLine, title, markdown) ->
        val endLine = (hits.getOrNull(idx + 1)?.first ?: lineIndex) - 1
        PythonCell(markerLine, endLine, title, markdown)
    }
}

/** The cell [line] is in; lines above the first marker are in none. */
fun List<PythonCell>.cellAt(line: Int): PythonCell? = lastOrNull { it.marker <= line }?.takeIf { line <= it.end }

/** The code a cell runs: its lines after the marker, without the blank lines at its end. */
fun pythonCellCode(text: String, cell: PythonCell): String {
    val lines = text.split('\n').map { it.trimEnd('\r') }
    if (cell.firstCodeLine > cell.end) return ""
    return lines.subList(cell.firstCodeLine, (cell.end + 1).coerceAtMost(lines.size)).dropLastWhile { it.isBlank() }.joinToString("\n")
}

/** Which cells "Run Above", "Run Below" and "Run All" take, from the cell at [line]. */
enum class CellRange { Current, Above, CurrentAndBelow, All }

fun cellsToRun(cells: List<PythonCell>, line: Int, range: CellRange): List<PythonCell> {
    val current = cells.cellAt(line)
    return when (range) {
        CellRange.Current -> listOfNotNull(current)
        CellRange.Above -> cells.filter { it.end < (current?.marker ?: (line + 1)) }
        CellRange.CurrentAndBelow -> cells.filter { it.marker >= (current?.marker ?: line) }
        CellRange.All -> cells
    }.filterNot { it.markdown }
}

/**
 * The last result computed from a text, recomputed only when another text comes: an edit
 * replaces the document's String, so the same instance means the same content.
 */
internal class TextCache<T> {
    private var text: String? = null
    private var value: T? = null

    @Synchronized
    fun of(current: String, compute: (String) -> T): T {
        @Suppress("UNCHECKED_CAST")
        if (current === text) return value as T
        return compute(current).also {
            text = current
            value = it
        }
    }

    @Synchronized
    fun clear() {
        text = null
        value = null
    }
}
