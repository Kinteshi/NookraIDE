package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.notebook.CellType
import com.avfstudio.nativeide.notebook.NotebookCell
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class PlotViewerTest {
    private fun output(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun plotsAreListedInNotebookOrder() {
        val cells = listOf(
            NotebookCell("a", CellType.Code, "plot()", listOf(
                output("""{"output_type": "stream", "name": "stdout", "text": "hi"}"""),
                output("""{"output_type": "display_data", "data": {"image/png": "iVBO", "text/plain": "<Figure>"}}"""),
            ), executionCount = 3),
            NotebookCell("b", CellType.Markdown, "# Notes"),
            NotebookCell("c", CellType.Code, "svg()", listOf(
                output("""{"output_type": "display_data", "data": {"image/svg+xml": ["<svg ", "/>"], "image/png": "AAAA"}}"""),
                output("""{"output_type": "execute_result", "data": {"image/svg+xml": "<svg/>"}}"""),
            )),
        )
        val plots = notebookPlots(cells)
        assertEquals(listOf("a" to 1, "c" to 0, "c" to 1), plots.map { it.cellId to it.outputIndex })
        assertEquals(listOf("png", "png", "svg"), plots.map { it.extension })
        assertEquals("<svg/>", plots[2].data)
        assertEquals(3, plots[0].executionCount)
    }
}
