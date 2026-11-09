package io.github.kinteshi.quirecode.workbench

import io.github.kinteshi.quirecode.editor.LspPosition
import io.github.kinteshi.quirecode.editor.LspRange
import io.github.kinteshi.quirecode.language.CopilotSuggestion
import io.github.kinteshi.quirecode.notebook.CellType
import io.github.kinteshi.quirecode.notebook.NotebookCell
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotebookCopilotTest {
    private val code = NotebookCell("a", CellType.Code, "import math\nx = ")
    private val prose = NotebookCell("b", CellType.Markdown, "# Title\nText")
    private val last = NotebookCell("c", CellType.Code, "print(x)")

    @Test
    fun mirrorIsPercentFormatWithCellStartLines() {
        val mirror = notebookMirror(listOf(code, prose, last))
        assertEquals("# %%\nimport math\nx = \n\n# %% [markdown]\n# # Title\n# Text\n\n# %%\nprint(x)\n\n", mirror.text)
        assertEquals(mapOf("a" to 1, "b" to 5, "c" to 9), mirror.startLines)
        assertEquals("print(x)", mirror.text.lines()[9])
    }

    @Test
    fun suggestionsMapBackIntoTheCell() {
        // Copilot replaces line 2 of the mirror ("x = ") with a completed line.
        val suggestion = CopilotSuggestion("x = math.pi", LspRange(LspPosition(2, 0), LspPosition(2, 4)), null, JsonObject())
        val mapped = cellSuggestion(code, startLine = 1, suggestion)!!
        assertEquals(12 to 16, mapped.start to mapped.end)
        assertEquals("math.pi", cellGhostText(code.source, code.source.length, mapped))
        assertNull(cellGhostText("import math\nx = 2", 17, mapped))
        assertNull(cellSuggestion(code, startLine = 5, suggestion))
    }

    @Test
    fun positionsAndOffsetsRoundTrip() {
        assertEquals(1 to 2, cellPosition(code.source, 14))
        assertEquals(14, cellOffset(code.source, 1, 2))
        assertEquals(16, cellOffset(code.source, 1, 99))
        assertNull(cellOffset(code.source, 2, 0))
    }
}
