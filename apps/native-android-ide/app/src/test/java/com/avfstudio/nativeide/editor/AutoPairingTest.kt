package com.avfstudio.nativeide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoPairingTest {
    @Test
    fun insertsClosingPairAndLeavesCursorInside() {
        val result = requireNotNull(
            autoPairAfterInsertion("abc", "a(bc", 1, "(", null),
        )

        assertEquals("a()bc", result.text)
        assertEquals(2, result.selectionStart)
        assertEquals(2, result.selectionEnd)
    }

    @Test
    fun surroundsThePreviouslySelectedText() {
        val result = requireNotNull(
            autoPairAfterInsertion("name", "(", 0, "(", 0..3),
        )

        assertEquals("(name)", result.text)
        assertEquals(1, result.selectionStart)
        assertEquals(5, result.selectionEnd)
    }

    @Test
    fun nestsAnOpenerBeforeAnExistingCloser() {
        val result = requireNotNull(
            autoPairAfterInsertion(")", "()", 0, "(", null),
        )

        assertEquals("())", result.text)
        assertEquals(")", result.insertion)
        assertEquals(1, result.selectionStart)
    }

    @Test
    fun movesPastAnExistingQuoteInsteadOfDuplicatingIt() {
        val result = requireNotNull(
            autoPairAfterInsertion("\"", "\"\"", 0, "\"", null),
        )

        assertEquals("\"\"", result.text)
        assertEquals("", result.insertion)
        assertEquals(1, result.selectionStart)
    }

    @Test
    fun backspaceOnAnEmptyPairRemovesTheCloserToo() {
        assertEquals(1, pairedCloserOffsetAfterDeletion("a()", "a)", 1, "("))
        assertEquals(0, pairedCloserOffsetAfterDeletion("\"\"", "\"", 0, "\""))
        assertNull(pairedCloserOffsetAfterDeletion("(x)", "x)", 0, "("))
        assertNull(pairedCloserOffsetAfterDeletion("a()", "a)", 2, ")"))
    }

    @Test
    fun removesTypedCloserWhenTheMatchingOneIsAlreadyUnderTheCursor() {
        val result = requireNotNull(
            skipExistingCloserAfterInsertion("()", "())", 1, ")"),
        )

        assertEquals("()", result.text)
        assertEquals(1, result.deleteOffset)
        assertEquals(2, result.selectionStart)
    }

    @Test
    fun ignoresNonPairCharactersAndUnmatchedTextChanges() {
        assertNull(autoPairAfterInsertion("", "x", 0, "x", null))
        assertNull(autoPairAfterInsertion("abc", "ab(", 1, "(", null))
    }

    @Test
    fun enterBeforeALineBreakIsNotTypedOver() {
        // Enter at the end of an unindented line inserts exactly "\n", the character already there.
        assertNull(skipExistingCloserAfterInsertion("a = 0\nb\n", "a = 0\n\nb\n", 5, "\n"))
        assertNull(skipExistingCloserAfterInsertion("ab", "aab", 0, "a"))
    }
}
