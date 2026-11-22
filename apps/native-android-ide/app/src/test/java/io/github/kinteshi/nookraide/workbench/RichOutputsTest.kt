package io.github.kinteshi.nookraide.workbench

import io.github.kinteshi.nookraide.notebook.OutputPresentation
import io.github.kinteshi.nookraide.notebook.OutputView
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichOutputsTest {
    @Test
    fun svgLatexAndJsonOutputsGetTheirViews() {
        val output = JsonParser.parseString(
            """{"output_type":"display_data","data":{"text/plain":"<Figure>","image/svg+xml":"<svg/>","text/latex":"\\(x^2\\)","application/json":{"a":1}}}""",
        ).asJsonObject
        val views = outputViews(output)
        assertEquals(listOf(OutputView.Svg, OutputView.Latex, OutputView.Json, OutputView.Text), views)
        assertEquals(OutputView.Latex, shownView(listOf(OutputView.Latex, OutputView.Text), OutputPresentation(), WorkbenchSettings()))
        assertEquals(OutputView.Text, shownView(listOf(OutputView.Latex, OutputView.Text), OutputPresentation(), WorkbenchSettings(renderLatex = false)))
        assertEquals("\\(x^2\\)", outputCopyText(output, OutputView.Latex, OutputPresentation()))
        assertEquals("{\n  \"a\": 1\n}", outputCopyText(output, OutputView.Json, OutputPresentation()))
    }

    @Test
    fun jsonTreeOpensByPath() {
        val root = JsonParser.parseString("""{"name":"x","list":[1,{"b":true}],"empty":{}}""")
        val closed = jsonTreeRows(root, setOf("$"))
        assertEquals(listOf("$", "$.name", "$.list", "$.empty"), closed.map { it.path })
        assertEquals("[2 items]", jsonPreview(closed[2].value!!))
        assertEquals("\"x\"", jsonPreview(closed[1].value!!))
        val open = jsonTreeRows(root, setOf("$", "$.list", "$.list[1]"))
        assertEquals(listOf("$", "$.name", "$.list", "$.list[0]", "$.list[1]", "$.list[1].b", "$.empty"), open.map { it.path })
        assertEquals(3, open[5].depth)
        assertFalse(open[6].expanded)
    }

    @Test
    fun longArraysEndWithACount() {
        val root = JsonParser.parseString((0 until JSON_CHILD_LIMIT + 5).joinToString(",", "[", "]"))
        val rows = jsonTreeRows(root, setOf("$"))
        assertEquals(5, rows.last().moreCount)
        assertEquals(JSON_CHILD_LIMIT + 2, rows.size)
    }

    @Test
    fun latexPageKeepsTheSourceAsData() {
        val page = latexPage("\\frac{a}{b} </script>", dark = true)
        assertTrue("\"\\\\frac{a}{b} <\\/script>\"" in page)
        assertEquals("{\n  \"k\": [\n    1\n  ]\n}", prettyJson(JsonPrimitive("{\"k\":[1]}")))
    }
}
