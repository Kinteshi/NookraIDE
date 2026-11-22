package io.github.kinteshi.nookraide.workbench

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class AnsiTextTest {
    private val palette = IntArray(16) { (0xFF000000 + it).toInt() }

    @Test
    fun turnsSgrCodesIntoStyles() {
        val text = ansiAnnotated("\u001b[31mred\u001b[0m plain \u001b[1;38;5;196mbold\u001b[K\u001b[0m", palette, Color.White)
        assertEquals("red plain bold", text.text)
        val red = text.spanStyles.first { text.text.substring(it.start, it.end) == "red" }
        assertEquals(Color(palette[1]), red.item.color)
        val bold = text.spanStyles.first { text.text.substring(it.start, it.end) == "bold" }
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals(Color(255, 0, 0), bold.item.color)
    }

    @Test
    fun findsTracebackReferences() {
        val line = "Cell In[3], line 5 and File ~/proj/util.py:12, in f() and File \"/x/y.py\", line 7 and File \"<string>\", line 1"
        assertEquals(
            listOf(TracebackTarget.Cell(3, 5), TracebackTarget.File("~/proj/util.py", 12), TracebackTarget.File("/x/y.py", 7)),
            tracebackTargets(line).map { it.second },
        )
    }

    @Test
    fun tracebackReferencesBecomeLinks() {
        val text = ansiAnnotated("\u001b[36mCell \u001b[39m\u001b[32mIn[2]\u001b[39m\u001b[32m, line 4\u001b[39m", palette, Color.White, onTarget = {})
        assertEquals("Cell In[2], line 4", text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals(0 to 18, links.single().start to links.single().end)
    }
}
