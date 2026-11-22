package io.github.kinteshi.nookraide.workbench

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownCellsTest {
    @Test
    fun mathIsKeptOutOfMarkdown() {
        val protected = protectMath("Energy \$E = mc^2\$ and \$\$\\sum_{i=1}^n x_i * y_i\$\$ end")
        assertEquals("Energy @@0@@ and @@1@@ end", protected.text)
        assertEquals(listOf("\$E = mc^2\$", "\$\$\\sum_{i=1}^n x_i * y_i\$\$"), protected.math)
        assertEquals(listOf("\\(a_b\\)", "\\begin{align}x &= 1\\end{align}"), protectMath("\\(a_b\\) then \\begin{align}x &= 1\\end{align}").math)
    }

    @Test
    fun pricesAndCodeAreNotMath() {
        assertTrue(protectMath("It costs \$5 and \$10 today").math.isEmpty())
        assertTrue(protectMath("Use `\$x\$` literally").math.isEmpty())
        assertTrue(protectMath("```\nprint('\$a\$')\n```\n").math.isEmpty())
        assertEquals(1, protectMath("```\ncode \$x\$\n```\nthen \$y\$").math.size)
    }

    @Test
    fun mathComesBackEscapedForKatex() {
        assertEquals(
            "<p><span class=\"math\" data-display=\"false\" data-tex=\"a &lt; b\">\$a &lt; b\$</span></p>",
            restoreMath("<p>@@0@@</p>", listOf("\$a < b\$")),
        )
        assertEquals("x_i" to true, mathBody("\$\$x_i\$\$"))
        assertEquals("\\begin{align}x\\end{align}" to true, mathBody("\\begin{align}x\\end{align}"))
    }

    @Test
    fun rawHtmlIsDetected() {
        assertTrue(hasRawHtml(MarkdownRenderer.parse("Text <br> more")))
        assertTrue(hasRawHtml(MarkdownRenderer.parse("<div align=\"center\">x</div>")))
        assertFalse(hasRawHtml(MarkdownRenderer.parse("# Title\n\n- a **b**\n")))
    }

    @Test
    fun attachmentsBecomeDataUris() {
        val attachments = JsonParser.parseString("""{"plot.png": {"image/png": "iVBORw0K"}}""").asJsonObject
        assertEquals("![x](data:image/png;base64,iVBORw0K)", inlineAttachments("![x](attachment:plot.png)", attachments))
        assertEquals("![x](attachment:other.png)", inlineAttachments("![x](attachment:other.png)", attachments))
    }

    @Test
    fun inlineTextKeepsStylesAndLinks() {
        val document = MarkdownRenderer.parse("Some **bold**, `code` and [docs](https://example.com) ~~old~~")
        val links = mutableListOf<String>()
        val text = inlineMarkdown(document.firstChild, InlineColors(Color.Blue, Color.Black, Color.Gray, Color.Gray)) { links += it }
        assertEquals("Some bold,  code  and docs old", text.text)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("https://example.com", (link.item as LinkAnnotation.Clickable).tag)
    }
}
