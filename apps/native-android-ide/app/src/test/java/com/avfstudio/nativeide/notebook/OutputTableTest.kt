package com.avfstudio.nativeide.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputTableTest {
    /** pandas 2.x `_repr_html_` of a truncated frame with a named index. */
    private val pandas = """
        <div>
        <style scoped>
            .dataframe tbody tr th:only-of-type { vertical-align: middle; }
        </style>
        <table border="1" class="dataframe">
          <thead>
            <tr style="text-align: right;"><th></th><th>name</th><th>score</th></tr>
            <tr><th>id</th><th></th><th></th></tr>
          </thead>
          <tbody>
            <tr><th>0</th><td>Ana &amp; Bo</td><td>10.5</td></tr>
            <tr><th>1</th><td>Cy</td><td>2</td></tr>
            <tr><th>...</th><td>...</td><td>...</td></tr>
            <tr><th>9</th><td>Di</td><td>NaN</td></tr>
          </tbody>
        </table>
        <p>10 rows × 2 columns</p>
        </div>
    """.trimIndent()

    @Test
    fun pandasTablesBecomeAGridWithTheirCaption() {
        val table = parseDataFrameHtml(pandas)!!
        assertEquals(2, table.head.size)
        assertEquals(listOf("", "name", "score"), table.head[0].map { it.text })
        assertEquals(4, table.body.size)
        assertEquals("Ana & Bo", table.body[0][1].text)
        assertTrue(table.body[0][0].header)
        assertEquals("10 rows × 2 columns", table.caption)
        assertTrue(isEllipsisRow(table.body[2]))
    }

    @Test
    fun sortingGoesByNumberAndDropsTheEllipsisRow() {
        val table = parseDataFrameHtml(pandas)!!
        assertEquals(listOf("2", "10.5", "NaN"), table.sortedBody(2, SortDirection.Ascending).map { it[2].text })
        assertEquals(listOf("10.5", "2", "NaN"), table.sortedBody(2, SortDirection.Descending).map { it[2].text })
        assertEquals(listOf("Ana & Bo", "Cy", "Di"), table.sortedBody(1, SortDirection.Ascending).map { it[1].text })
    }

    @Test
    fun multiIndexSpansFillTheGridAndCopyAsTsv() {
        val html = """
            <table class="dataframe"><thead>
            <tr><th></th><th></th><th colspan="2">sales</th></tr>
            <tr><th>region</th><th>year</th><th>q1</th><th>q2</th></tr>
            </thead><tbody>
            <tr><th rowspan="2">north</th><th>2024</th><td>1</td><td>2</td></tr>
            <tr><th>2025</th><td>3</td><td>4</td></tr>
            </tbody></table>
        """.trimIndent()
        val table = parseDataFrameHtml(html)!!
        assertEquals(4, table.columnCount)
        assertEquals(TableCell("sales", header = true, spanned = true), table.head[0][3])
        assertEquals(TableCell("north", header = true, spanned = true), table.body[1][0])
        assertEquals(
            "\t\tsales\t\nregion\tyear\tq1\tq2\nnorth\t2024\t1\t2\nnorth\t2025\t3\t4",
            table.toTsv(),
        )
    }

    @Test
    fun polarsTablesKeepTheirShapeAndDtypeRow() {
        val html = """<div><style>.dataframe > thead > tr > th { text-align: right; }</style>
            <small>shape: (2, 2)</small><table border="1" class="dataframe"><thead><tr><th>a</th><th>b</th></tr>
            <tr><td>i64</td><td>str</td></tr></thead><tbody><tr><td>1</td><td>&quot;x&quot;</td></tr>
            <tr><td>2</td><td>&quot;y&quot;</td></tr></tbody></table></div>"""
        val table = parseDataFrameHtml(html)!!
        assertEquals("shape: (2, 2)", table.caption)
        assertEquals(listOf("i64", "str"), table.head[1].map { it.text })
        assertEquals("\"y\"", table.body[1][1].text)
    }

    @Test
    fun theIndexIsTheLeadingColumnsOfHeadersInEveryRow() {
        fun row(vararg cells: Pair<String, Boolean>) = cells.map { (text, header) -> TableCell(text, header) }
        val head = listOf(row("" to true, "a" to true))
        assertEquals(1, OutputTable(head, listOf(row("0" to true, "1" to false), row("..." to true, "..." to false)), "").indexColumns)
        assertEquals(0, OutputTable(head, listOf(row("x" to false, "1" to false)), "").indexColumns)
        // Nothing but headers has no columns left to scroll.
        assertEquals(0, OutputTable(head, listOf(row("0" to true, "1" to true)), "").indexColumns)
        assertEquals(1, parseDataFrameHtml(pandas)?.indexColumns)
    }

    @Test
    fun otherHtmlKeepsItsHtmlView() {
        assertNull(parseDataFrameHtml("<table id=\"T_abc\"><tr><td>1</td></tr></table>"))
        assertNull(parseDataFrameHtml("<b>dataframe</b>"))
        val two = "<table class=\"dataframe\"><tr><td>1</td></tr></table><table class=\"dataframe\"><tr><td>2</td></tr></table>"
        assertNull(parseDataFrameHtml(two))
    }

    @Test
    fun numbersAreRecognisedForAlignmentAndSorting() {
        assertEquals(1234.0, tableNumber("1,234")!!, 0.0)
        assertEquals(12.0, tableNumber("12%")!!, 0.0)
        assertEquals(0.001, tableNumber("1e-3")!!, 0.0)
        assertNull(tableNumber("NaN"))
        assertNull(tableNumber("abc"))
    }
}
