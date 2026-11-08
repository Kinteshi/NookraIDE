package com.avfstudio.nativeide.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosingOvertypeTest {
    @Test
    fun aCloserInsertedWithItsOpenerIsSteppedOverOnce() {
        val overtype = ClosingOvertype()
        overtype.expect(1, "()", closerAt = 2, closer = ")") // "f" + "()"
        overtype.edited(1, 0, "()")
        overtype.edited(2, 0, "1") // "f(1)"
        assertTrue(overtype.stepsOver(3, ')', "f(1)"))
        assertFalse(overtype.stepsOver(3, ')', "f(1)"))
    }

    @Test
    fun aCloserInsertedAfterTheTypedOpenerIsPlacedWhereItWent() {
        val overtype = ClosingOvertype()
        overtype.edited(1, 0, "(") // typed "(" (the opener comes first)
        overtype.expect(2, ")", closerAt = 2, closer = ")")
        overtype.edited(2, 0, ")")
        assertTrue(overtype.stepsOver(2, ')', "f()"))
    }

    @Test
    fun aCloserTheUserTypedIsNotSteppedOver() {
        assertFalse(ClosingOvertype().stepsOver(3, ')', "f(1)"))
    }

    @Test
    fun anEditOverTheCloserForgetsIt() {
        val overtype = ClosingOvertype()
        overtype.expect(1, "()", 2, ")")
        overtype.edited(1, 0, "()")
        overtype.edited(2, 1, "")
        assertFalse(overtype.stepsOver(2, ')', "f("))
    }

    @Test
    fun anExpectedEditThatNeverCameIsForgotten() {
        val overtype = ClosingOvertype()
        overtype.expect(1, "()", 2, ")")
        overtype.edited(0, 0, "x")
        overtype.edited(1, 0, "()")
        assertFalse(overtype.stepsOver(2, ')', "x()"))
    }
}
