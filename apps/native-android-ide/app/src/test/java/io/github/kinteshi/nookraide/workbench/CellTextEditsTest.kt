package io.github.kinteshi.nookraide.workbench

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class CellTextEditsTest {
    private fun at(text: String, start: Int, end: Int = start) = TextFieldValue(text, TextRange(start, end))

    @Test
    fun tabInsertsSpacesToTheNextStop() {
        val result = CellTextEdits.indent(at("ab", 2), 4, insertSpaces = true)
        assertEquals("ab  ", result.text)
        assertEquals(TextRange(4), result.selection)
    }

    @Test
    fun tabIndentsSelectedLinesButNotBlankOnesOrALineTouchedAtColumnZero() {
        val text = "a\n\nb\nc"
        // Select from the start of "a" to the start of "c".
        val result = CellTextEdits.indent(at(text, 0, text.indexOf('c')), 4, insertSpaces = true)
        assertEquals("    a\n\n    b\nc", result.text)
        assertEquals(TextRange(0, result.text.indexOf('c')), result.selection)
    }

    @Test
    fun shiftTabOutdentsAndClampsTheCursor() {
        val result = CellTextEdits.outdent(at("    x = 1", 2), 4)
        assertEquals("x = 1", result.text)
        assertEquals(TextRange(0), result.selection)
        val tabs = CellTextEdits.outdent(at("\tif a:\n\t\tpass", 0, 13), 4)
        assertEquals("if a:\n\tpass", tabs.text)
    }

    @Test
    fun enterKeepsIndentationAndIndentsAfterAColon() {
        val result = CellTextEdits.newline(at("    if x:", 9), 4, insertSpaces = true)
        assertEquals("    if x:\n        ", result.text)
        assertEquals(TextRange(result.text.length), result.selection)
        assertEquals("  y\n  ", CellTextEdits.newline(at("  y", 3), 4, insertSpaces = true).text)
    }
}
