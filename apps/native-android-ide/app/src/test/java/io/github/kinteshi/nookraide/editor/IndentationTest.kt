package io.github.kinteshi.nookraide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndentationTest {
    @Test
    fun detectsSpacesAndTheirStep() {
        val two = "function f() {\n  if (x) {\n    y()\n  }\n}\n"
        assertEquals(Indentation(insertSpaces = true, tabSize = 2), detectIndentation(two, 4))
        val four = "def f():\n    if x:\n        y()\n    return 1\n\nclass A:\n    pass\n"
        assertEquals(Indentation(insertSpaces = true, tabSize = 4), detectIndentation(four, 2))
    }

    @Test
    fun detectsTabsAndNothing() {
        assertEquals(Indentation(insertSpaces = false, tabSize = 8), detectIndentation("a:\n\tb\n\t\tc\n", 8))
        assertNull(detectIndentation("a\nb\n", 4))
    }

    @Test
    fun convertsLeadingWhitespace() {
        val spaces = Indentation(true, 4)
        val tabs = Indentation(false, 4)
        assertEquals("a\n\tb\n\t\tc  d", convertIndentation("a\n    b\n        c  d", spaces, tabs))
        assertEquals("a\n  b\n    c", convertIndentation("a\n\tb\n\t\tc", tabs, Indentation(true, 2)))
    }
}
