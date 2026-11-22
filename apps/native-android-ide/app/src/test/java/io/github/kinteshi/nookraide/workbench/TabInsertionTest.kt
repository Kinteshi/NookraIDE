package io.github.kinteshi.nookraide.workbench

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class TabInsertionTest {
    @Test
    fun spacesReachTheNextTabStop() {
        assertEquals("    ", tabInsertion("", 4, insertSpaces = true))
        assertEquals("  ", tabInsertion("if", 4, insertSpaces = true))
        assertEquals("    ", tabInsertion("    ", 4, insertSpaces = true))
        assertEquals("   ", tabInsertion("\tx", 4, insertSpaces = true))
    }

    @Test
    fun tabsWhenSpacesAreOff() {
        assertEquals("\t", tabInsertion("abc", 4, insertSpaces = false))
    }

    @Test
    fun enterKeepsIndentationAndOpensBlocks() {
        assertEquals("\n    ", newlineIndentation("    x = 1", 4, insertSpaces = true))
        assertEquals("\n        ", newlineIndentation("    def f(n):", 4, insertSpaces = true))
        assertEquals("\n\t", newlineIndentation("items = [", 4, insertSpaces = false))
        assertEquals("\n", newlineIndentation("", 4, insertSpaces = true))
    }

    @Test
    fun backspaceInLeadingSpacesGoesToThePreviousTabStop() {
        assertEquals(4, backspaceTabStop("        ", 4))
        assertEquals(4, backspaceTabStop("      ", 4))
        assertEquals(0, backspaceTabStop("    ", 4))
        assertEquals(null, backspaceTabStop(" ", 4))
        assertEquals(null, backspaceTabStop("    x ", 4))
        assertEquals(null, backspaceTabStop("\t  ", 4))
    }

    @Test
    fun cellBackspaceRemovesOneIndentationLevel() {
        val field = TextFieldValue("def f():\n        ", TextRange(17))
        assertEquals(TextFieldValue("def f():\n    ", TextRange(13)), CellTextEdits.backspace(field, 4))
        assertEquals(null, CellTextEdits.backspace(TextFieldValue("x = 1", TextRange(5)), 4))
        assertEquals(null, CellTextEdits.backspace(TextFieldValue("        ", TextRange(2, 6)), 4))
    }

    @Test
    fun ghostTextFollowsWhatIsTyped() {
        assertEquals("rn n", ghostAfterTyped("        retu", "        return n"))
        // Seen on the tablet: 12 spaces typed, Copilot re-indents the line to 8.
        assertEquals("return n", ghostAfterTyped(" ".repeat(12), "        return n"))
        assertEquals(null, ghostAfterTyped("x = ", "y = 2"))
    }
}
