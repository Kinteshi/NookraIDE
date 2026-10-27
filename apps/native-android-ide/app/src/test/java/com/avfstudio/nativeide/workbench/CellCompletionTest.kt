package com.avfstudio.nativeide.workbench

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellCompletionTest {
    private fun at(text: String, caret: Int = text.length) = TextFieldValue(text, TextRange(caret))
    // Completing "np.ar" offers names that replace "ar" (cursor_start = 3).
    private val completion = CellCompletion("c", start = 3, items = listOf("arange", "array", "argmax"))

    @Test
    fun typingNarrowsAndClosesTheList() {
        assertEquals(listOf("arange", "array", "argmax"), completion.visibleItems(at("np.ar")))
        assertEquals(listOf("arange"), completion.visibleItems(at("np.ara")))
        assertEquals(listOf("array"), completion.visibleItems(at("np.arr")))
        assertNull(completion.visibleItems(at("np.x")))
        assertNull(completion.visibleItems(at("np", 2)))
    }

    @Test
    fun acceptingReplacesTheTypedPart() {
        val accepted = completion.accept(at("np.ar(1)", 5), index = 1)!!
        assertEquals("np.array(1)", accepted.text)
        assertEquals(TextRange(8), accepted.selection)
    }

    @Test
    fun tabCompletesAfterNamesAndDotsOnly() {
        assertTrue(tabCompletes(at("np.")))
        assertTrue(tabCompletes(at("pri")))
        assertFalse(tabCompletes(at("    ")))
        assertFalse(tabCompletes(at("")))
        assertFalse(tabCompletes(at("x = ")))
    }
}
