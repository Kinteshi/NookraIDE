package com.avfstudio.nativeide.workbench

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
    val lines = text.split('\n')
    val markers = lines.indices.filter { codeMarker.containsMatchIn(lines[it].trimEnd('\r')) }
    return markers.mapIndexed { index, line ->
        val raw = lines[line].trimEnd('\r')
        val markdown = markdownMarker.containsMatchIn(raw)
        val title = codeMarker.find(raw)?.groupValues?.get(2)?.trim()?.removePrefix("[markdown]")?.trim().orEmpty()
        PythonCell(line, (markers.getOrNull(index + 1) ?: lines.size) - 1, title, markdown)
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
}
