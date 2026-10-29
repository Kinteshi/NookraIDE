package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.notebook.CellType
import com.avfstudio.nativeide.notebook.NotebookCell
import com.avfstudio.nativeide.notebook.OutputPresentation
import com.avfstudio.nativeide.notebook.OutputView
import com.avfstudio.nativeide.notebook.SortDirection
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun scriptOutputsFindTheirLibraries() {
        val altair = JsonParser.parseString(
            """{"output_type":"display_data","data":{"application/vnd.vegalite.v5+json":{"mark":"bar"},"text/plain":"alt.Chart(...)"}}""",
        ).asJsonObject
        assertEquals(listOf(OutputView.Vega, OutputView.Text), outputViews(altair))
        val (kind, version, _) = vegaSpec(altair.getAsJsonObject("data"))!!
        assertEquals("vegalite" to 5, kind to version)
        assertEquals(OutputView.Text, shownView(outputViews(altair), OutputPresentation(), WorkbenchSettings(scriptOutputs = false)))
        assertTrue(vegaPage(kind, version, JsonParser.parseString("""{"title":"</script>"}""").asJsonObject, dark = true).contains("<\\/script>"))

        // Bokeh without a loader in the notebook: BokehJS from its CDN at the output's version.
        val bokeh = """<div id="p1"></div><script type="application/json" id="p2">{"version":"3.6.2"}</script><script>Bokeh.embed</script>"""
        assertTrue(scriptHtmlPage(bokeh, dark = false).contains("https://cdn.bokeh.org/bokeh/release/bokeh-3.6.2.min.js"))
        assertTrue(scriptHtmlPage(bokeh, dark = false, preamble = "load()").contains("<script>load()</script>"))

        val scriptedHtml = JsonParser.parseString("""{"output_type":"display_data","data":{"text/html":"<script>x()</script>","text/plain":"m"}}""").asJsonObject
        assertTrue(outputHtmlScripted(scriptedHtml))
        assertEquals(OutputView.Html, shownView(outputViews(scriptedHtml), OutputPresentation(), WorkbenchSettings(), scripted = true))
        assertEquals(OutputView.Text, shownView(outputViews(scriptedHtml), OutputPresentation(), WorkbenchSettings(scriptOutputs = false), scripted = true))
    }

    @Test
    fun workspaceSessionsRoundTripThroughGson() {
        val gson = com.google.gson.Gson()
        val session = WorkspaceSession(open = listOf("/w/a.py"), active = "/w/a.py", directory = null, expanded = listOf("/w/src"))
        assertEquals(session, gson.fromJson(gson.toJson(session), WorkspaceSession::class.java))
        assertEquals(WorkspaceSession(open = listOf("/w/b.py")), gson.fromJson("""{"open":["/w/b.py"]}""", WorkspaceSession::class.java))
        assertEquals("session:/home/u/Projects", workspaceSessionKey("/home/u/Projects"))
    }

    @Test
    fun bokehEmbedCodeRunsWithTheDivBeforeIt() {
        val div = JsonParser.parseString("""{"output_type":"display_data","data":{"text/html":"<div id=p1></div>"}}""").asJsonObject
        val embed = JsonParser.parseString(
            """{"output_type":"display_data","data":{"application/javascript":"embed()","application/vnd.bokehjs_exec.v0+json":""}}""",
        ).asJsonObject
        val outputs = listOf(div, embed)
        assertEquals("embed()", attachedScript(outputs, 0))
        assertTrue(attachedToPrevious(outputs, 1))
        assertEquals(null, attachedScript(listOf(embed), 0))
        assertTrue(!attachedToPrevious(listOf(embed), 0))
    }
}
