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
}
