package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.notebook.CellType
import com.avfstudio.nativeide.notebook.NotebookCell
import com.avfstudio.nativeide.notebook.OutputPresentation
import com.avfstudio.nativeide.notebook.OutputView
import com.avfstudio.nativeide.notebook.SortDirection
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotebookViewTest {
    @Test
    fun aTallCellsHeaderSticksUntilTheNextCellPushesItOut() {
        val items = listOf(StickyItem(0, -500, 800), StickyItem(1, 300, 200))
        assertEquals(StickyCell(0, 0), stickyCell(items, viewportStart = 0, cellCount = 2, headerHeight = 100))
        // 60 px of the cell left: the header moves up by 40.
        assertEquals(StickyCell(0, -40), stickyCell(listOf(StickyItem(0, -740, 800)), 0, 2, 100))
        // The top still in view, a short cell, or the add-cell row: nothing sticks.
        assertNull(stickyCell(listOf(StickyItem(0, 0, 800)), 0, 2, 100))
        assertNull(stickyCell(listOf(StickyItem(0, -100, 250)), 0, 2, 100))
        assertNull(stickyCell(listOf(StickyItem(2, -100, 800)), 0, 2, 100))
    }

    @Test
    fun outputsPickTheirViewFromSettingsOrTheirMenu() {
        val output = JsonParser.parseString(
            """{"output_type":"execute_result","data":{"text/plain":"   a\n0  1","text/html":"<table class=\"dataframe\"><tr><th>a</th></tr><tr><td>1</td></tr></table>"}}""",
        ).asJsonObject
        val views = outputViews(output)
        assertEquals(listOf(OutputView.Table, OutputView.Html, OutputView.Text), views)
        assertEquals(OutputView.Table, shownView(views, OutputPresentation(), WorkbenchSettings()))
        assertEquals(OutputView.Html, shownView(views, OutputPresentation(), WorkbenchSettings(nativeTables = false)))
        assertEquals(OutputView.Text, shownView(views, OutputPresentation(), WorkbenchSettings(nativeTables = false, renderHtmlOutputs = false)))
        assertEquals(OutputView.Text, shownView(views, OutputPresentation(view = OutputView.Text), WorkbenchSettings()))
        assertEquals("a\n1", outputCopyText(output, OutputView.Table, OutputPresentation()))
        assertEquals("   a\n0  1", outputCopyText(output, OutputView.Text, OutputPresentation(sortColumn = 0, sortDirection = SortDirection.Descending)))
    }

    @Test
    fun stickyHeadersNameTheCellByItsFirstLine() {
        assertEquals("import numpy as np", cellHeadline(NotebookCell("a", CellType.Code, "\n  import numpy as np\nx = 1")))
        assertEquals("Results", cellHeadline(NotebookCell("b", CellType.Markdown, "## Results\ntext")))
        assertEquals("Empty cell", cellHeadline(NotebookCell("c", CellType.Code, "")))
    }
}
