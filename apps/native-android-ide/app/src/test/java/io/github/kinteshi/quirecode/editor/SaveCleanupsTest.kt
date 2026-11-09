package io.github.kinteshi.quirecode.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveCleanupsTest {
    private fun cleaned(text: String, cleanups: SaveCleanups, eol: String = "\n", keepLine: Int? = null) =
        applyLspTextEdits(text, saveCleanupEdits(text, cleanups, eol, keepLine))

    private val all = SaveCleanups(trimTrailingWhitespace = true, insertFinalNewline = true, trimFinalNewlines = true)

    @Test
    fun trailingWhitespaceGoesExceptOnTheKeptLine() {
        val trim = SaveCleanups(trimTrailingWhitespace = true)
        assertEquals("a\n\tb\nc\n", cleaned("a  \n\tb\t\nc \n", trim))
        assertEquals("a\n\tb\t\nc\n", cleaned("a  \n\tb\t\nc \n", trim, keepLine = 1))
        // CRLF: the \r stays part of the line break.
        assertEquals("a\r\nb\r\n", cleaned("a \r\nb\t\r\n", trim, eol = "\r\n"))
    }

    @Test
    fun finalNewlinesEndUpAsOne() {
        assertEquals("x = 1\n", cleaned("x = 1", SaveCleanups(insertFinalNewline = true)))
        assertEquals("x = 1\r\n", cleaned("x = 1", SaveCleanups(insertFinalNewline = true), eol = "\r\n"))
        assertEquals("x = 1\n", cleaned("x = 1\n\n\n", SaveCleanups(trimFinalNewlines = true)))
        // Trailing blank lines with spaces collapse too, with the last line's own whitespace.
        assertEquals("x = 1\n", cleaned("x = 1  \n  \n\t\n", all))
        assertEquals("x = 1\n", cleaned("x = 1\n\n", SaveCleanups(trimFinalNewlines = true)))
    }

    @Test
    fun cleanFilesNeedNoEdits() {
        assertEquals(emptyList<LspTextEdit>(), saveCleanupEdits("a\nb\n", all))
        assertEquals(emptyList<LspTextEdit>(), saveCleanupEdits("", all))
        assertEquals(emptyList<LspTextEdit>(), saveCleanupEdits("a  \n", SaveCleanups()))
    }
}
