package com.avfstudio.nativeide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotebookDragTest {
    @Test
    fun aCellMovesOncePastItsNeighboursMiddle() {
        // A 100 px cell at 0 above a 1000 px cell: down once its bottom passes 600.
        assertNull(dragStep(0, 100, 400f, previous = null, next = 100 to 1000))
        assertEquals(DragStep.Down, dragStep(0, 100, 520f, previous = null, next = 100 to 1000))
        // After the swap it lies at 1000 with offset -480: it must not swap straight back.
        assertNull(dragStep(1000, 100, -480f, previous = 0 to 1000, next = null))
        assertEquals(DragStep.Up, dragStep(1000, 100, -520f, previous = 0 to 1000, next = null))
    }
}
