package com.avfstudio.nativeide.workbench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlOutputTest {
    @Test
    fun pandasTablesRenderAndScriptOutputsFallBack() {
        val table = "<div><table class=\"dataframe\"><thead><tr><th>a</th></tr></thead><tbody><tr><td>1</td></tr></tbody></table></div>"
        assertFalse(htmlNeedsScript(table))
        assertTrue(htmlNeedsScript("<div id=\"w\"></div><SCRIPT>render()</SCRIPT>"))
        val page = htmlOutputPage(table, dark = true)
        assertTrue(page.contains(table))
        assertTrue(page.contains("#d4d4d4"))
    }
}
