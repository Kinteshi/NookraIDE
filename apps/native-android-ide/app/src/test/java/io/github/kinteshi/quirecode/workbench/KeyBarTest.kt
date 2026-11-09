package io.github.kinteshi.quirecode.workbench

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyBarTest {
    @Test
    fun ctrlAndAltChangeTerminalInput() {
        assertArrayEquals(byteArrayOf(3), modifiedTerminalByte('c'.code, ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(3), modifiedTerminalByte('C'.code, ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x1B), modifiedTerminalByte('['.code, ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x1B, 'b'.code.toByte()), modifiedTerminalByte('b'.code, ctrl = false, alt = true))
        assertArrayEquals(byteArrayOf(0x1B, 4), modifiedTerminalByte('d'.code, ctrl = true, alt = true))
        assertNull(modifiedTerminalByte('1'.code, ctrl = true, alt = false))
    }

    @Test
    fun onlyOneTypedCharacterAtTheCaretCounts() {
        val old = TextFieldValue("ab", TextRange(1))
        assertEquals('s', typedCharacter(old, TextFieldValue("asb", TextRange(2))))
        assertNull(typedCharacter(old, TextFieldValue("asxb", TextRange(3))))
        assertNull(typedCharacter(old, TextFieldValue("abs", TextRange(3))))
        assertNull(typedCharacter(TextFieldValue("ab", TextRange(0, 2)), TextFieldValue("s", TextRange(1))))
    }

    @Test
    fun latchesGoOnceLockedOff() {
        assertEquals(Latch.Once, KeyBar.toggle(Latch.Off))
        assertEquals(Latch.Locked, KeyBar.toggle(Latch.Once))
        assertEquals(Latch.Off, KeyBar.toggle(Latch.Locked))
    }

    @Test
    fun aComposedWordGivesUpItsNewLetter() {
        assertEquals('a', addedCharacter("math", "matha"))
        assertEquals('s', addedCharacter("", "s"))
        assertNull(addedCharacter("math", "mat"))
        assertNull(addedCharacter("math", "maths!"))
    }
}
