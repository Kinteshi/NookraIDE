package com.avfstudio.nativeide.workbench

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
}
