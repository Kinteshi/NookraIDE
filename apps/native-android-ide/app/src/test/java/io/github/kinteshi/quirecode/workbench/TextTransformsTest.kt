package io.github.kinteshi.quirecode.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTransformsTest {
    @Test
    fun joinsTheLineWithTheNextOrTheSelectedLines() {
        val text = "a = (1,\n     2)\nb"
        val joined = joinLines(text, 2, 2)!!
        assertEquals("a = (1, 2)\nb", joined.text)
        assertEquals(7, joined.selectionStart)
        val all = joinLines("x  \n  y\n\n  z", 0, 11)!!
        assertEquals("x y z", all.text)
        assertNull(joinLines("last", 2, 2))
    }

    @Test
    fun transposesAroundTheCursorAndAtTheLineEnd() {
        assertEquals(TextEdit("bac", 2, 2), transposeCharacters("abc", 1))
        assertEquals(TextEdit("acb\n", 3, 3), transposeCharacters("abc\n", 3))
        assertNull(transposeCharacters("abc", 0))
    }

    @Test
    fun sortsSelectedLinesOrTheWholeFile() {
        assertEquals("a\nb\nc\n", sortLines("c\na\nb\n", 0, 0, descending = false)!!.text)
        assertEquals("c\nb\na\n", sortLines("c\na\nb\n", 0, 0, descending = true)!!.text)
        // Only lines 2-3 (the selection ends at column 0 of line 4).
        assertEquals("z\na\nb\ny", sortLines("z\nb\na\ny", 2, 6, descending = false)!!.text)
        assertNull(sortLines("a\nb", 0, 0, descending = false))
    }

    @Test
    fun deletesDuplicateLinesKeepingTheFirst() {
        assertEquals("a\nb\nc", deleteDuplicateLines("a\nb\na\nc\nb", 0, 0)!!.text)
        assertNull(deleteDuplicateLines("a\nb", 0, 0))
    }

    @Test
    fun trimsTrailingWhitespaceKeepingTheCursor() {
        val edit = trimTrailingWhitespace("a  \nbc \nd", 6)!!
        assertEquals("a\nbc\nd", edit.text)
        assertEquals(4, edit.selectionStart)
    }

    @Test
    fun transformsTheSelectionOrTheWordAtTheCursor() {
        assertEquals("HELLO world", transformCase("hello world", 2, 2, CaseTransform.Upper)!!.text)
        assertEquals("Hello World", transformCase("hello WORLD", 0, 11, CaseTransform.Title)!!.text)
        assertEquals("parse_http_request", transformCase("parseHTTPRequest", 0, 16, CaseTransform.Snake)!!.text)
        assertEquals("my-var-name", transformCase("my_var_name", 0, 11, CaseTransform.Kebab)!!.text)
        assertNull(transformCase("abc", 0, 3, CaseTransform.Lower))
    }

    @Test
    fun expandsThroughWordStringBracketsLineAndBlock() {
        val text = "def f():\n    call(\"one two\", x)\n    y = 1\nz"
        val cursor = text.indexOf("one") + 1
        val ranges = smartSelectRanges(text, cursor, cursor).map { text.substring(it.first, it.last) }
        assertEquals("one", ranges[0])
        assertEquals("one two", ranges[1])
        assertEquals("\"one two\"", ranges[2])
        assertTrue("\"one two\", x" in ranges)
        assertTrue("call(\"one two\", x)" in ranges)
        assertTrue("def f():\n    call(\"one two\", x)\n    y = 1" in ranges)
        assertEquals(text, ranges.last())
    }
}
