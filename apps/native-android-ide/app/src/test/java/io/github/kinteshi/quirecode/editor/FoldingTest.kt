package io.github.kinteshi.quirecode.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldingTest {
    @Test
    fun indentationRangesLeaveTrailingBlankLinesOut() {
        val lines = listOf("def f():", "  if x:", "    y", "  return", "", "z", "class A:", "\tpass")
        assertEquals(listOf(FoldRange(0, 3), FoldRange(1, 2), FoldRange(6, 7)), indentationFoldingRanges(lines, tabSize = 4))
        // The closing brace stays out, as VS Code's indentation folding does.
        assertEquals(listOf(FoldRange(0, 1)), indentationFoldingRanges(listOf("f() {", "  a;", "}"), 2))
    }
}
