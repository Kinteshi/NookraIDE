package com.avfstudio.nativeide.editor

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

    @Test
    fun mapsRowsAndLinesAroundFolds() {
        val folds = FoldState()
        assertTrue(folds.fold(FoldRange(2, 4)))
        assertTrue(folds.fold(FoldRange(7, 9)))
        assertEquals(4, folds.hiddenLineCount)
        assertEquals(listOf(0, 1, 2, 5, 6, 7, 10), (0..6).map(folds::lineForRow))
        assertEquals(2, folds.rowForLine(3))
        assertEquals(3, folds.rowForLine(5))
        assertTrue(folds.isHidden(9))
        assertFalse(folds.isHidden(7))
        assertEquals(7, folds.visibleLineAtOrBefore(8))
    }

    @Test
    fun nestedFoldsMergeAndOpenSeparately() {
        val folds = FoldState()
        folds.fold(FoldRange(1, 2))
        folds.fold(FoldRange(0, 5))
        assertEquals(5, folds.hiddenLineCount)
        folds.unfold(0)
        assertEquals(1, folds.hiddenLineCount)
        assertTrue(folds.unfoldAround(2))
        assertTrue(folds.isEmpty)
    }

    @Test
    fun editsMoveFoldsBelowAndOpenTouchedOnes() {
        val folds = FoldState()
        folds.fold(FoldRange(5, 8))
        folds.fold(FoldRange(10, 12))
        // Two lines added above both.
        assertTrue(folds.afterEdit(1, 2))
        assertEquals(listOf(FoldRange(7, 10), FoldRange(12, 14)), folds.folded)
        // Typing on a fold's first line keeps it; an edit inside opens it.
        assertFalse(folds.afterEdit(7, 0))
        assertTrue(folds.afterEdit(13, 0))
        assertEquals(listOf(FoldRange(7, 10)), folds.folded)
        // Deleting lines 2-3 moves the rest up.
        folds.afterEdit(2, -2)
        assertEquals(listOf(FoldRange(5, 8)), folds.folded)
    }
}
