package io.github.kinteshi.quirecode.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonCellsTest {
    private val source = listOf(
        "import os",            // 0: above the first marker, in no cell
        "# %% Load",            // 1
        "x = 1",                // 2
        "",                     // 3
        "# %% [markdown]",      // 4
        "# Notes",              // 5
        "#%%",                  // 6
        "print(x)",             // 7
        "# In[3]:",             // 8
        "y = x + 1",            // 9
        "",                     // 10
    ).joinToString("\n")

    @Test
    fun markersSplitTheFileIntoCells() {
        val cells = pythonCells(source)
        assertEquals(listOf(1, 4, 6, 8), cells.map { it.marker })
        assertEquals(listOf(3, 5, 7, 10), cells.map { it.end })
        assertEquals(listOf(false, true, false, false), cells.map { it.markdown })
        assertEquals("Load", cells[0].title)
        assertNull(cells.cellAt(0))
        assertEquals(6, cells.cellAt(7)?.marker)
        assertTrue(pythonCells("x = 1  # %% not a marker\n").isEmpty())
    }

    @Test
    fun aCellRunsItsLinesWithoutTheMarkerOrTrailingBlanks() {
        val cells = pythonCells(source)
        assertEquals("x = 1", pythonCellCode(source, cells[0]))
        assertEquals("y = x + 1", pythonCellCode(source, cells[3]))
        assertEquals("", pythonCellCode("# %%", pythonCells("# %%").single()))
    }

    @Test
    fun rangesSkipMarkdownCells() {
        val cells = pythonCells(source)
        assertEquals(listOf(6), cellsToRun(cells, 7, CellRange.Current).map { it.marker })
        assertEquals(listOf(1), cellsToRun(cells, 7, CellRange.Above).map { it.marker })
        assertEquals(listOf(6, 8), cellsToRun(cells, 7, CellRange.CurrentAndBelow).map { it.marker })
        assertEquals(listOf(1, 6, 8), cellsToRun(cells, 0, CellRange.All).map { it.marker })
        assertTrue(cellsToRun(cells, 5, CellRange.Current).isEmpty())
    }

    @Test
    fun fastPathHandlesNormalCommentsCrlfAndXmlCells() {
        assertTrue(pythonCells("# normal comment\nx = 1\n# another comment\n").isEmpty())
        val crlf = "#%% Fast\r\nx = 1\r\n# <codecell>\r\ny = 2\r\n"
        val cells = pythonCells(crlf)
        assertEquals(listOf(0, 2), cells.map { it.marker })
        assertEquals("Fast", cells[0].title)
        assertEquals("x = 1", pythonCellCode(crlf, cells[0]))
        assertEquals("y = 2", pythonCellCode(crlf, cells[1]))
    }
}
