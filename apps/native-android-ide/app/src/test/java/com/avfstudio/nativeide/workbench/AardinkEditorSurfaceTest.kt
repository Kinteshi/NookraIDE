package com.avfstudio.nativeide.workbench

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.PlainTextTokenizer
import com.avfstudio.nativeide.editor.EditorConfig
import com.avfstudio.nativeide.editor.EditorEvent
import com.avfstudio.nativeide.editor.EditorSelection
import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.editor.LspTextEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AardinkEditorSurfaceTest {
    private fun surface(text: String) =
        AardinkEditorSurface(CodeEditorState(text, PlainTextTokenizer, 0L, CoroutineScope(Dispatchers.Unconfined)))

    @Test
    fun positionsAndOffsetsAgree() {
        val surface = surface("ab\ncde\n")
        assertEquals(3, surface.lineCount)
        assertEquals("cde", surface.lineText(1))
        assertEquals(LspPosition(1, 2), surface.positionAt(5))
        assertEquals(LspPosition(2, 0), surface.positionAt(99))
    }

    @Test
    fun selectionIsStartFirstWhicheverWayItWasMade() {
        val surface = surface("ab\ncde")
        surface.setSelection(EditorSelection(LspPosition(1, 2), LspPosition(0, 1)))
        assertEquals(1..5, surface.selectionOffsets)
        assertEquals(EditorSelection(LspPosition(0, 1), LspPosition(1, 2)), surface.selection)
        assertEquals("b\ncd", surface.selectedText)
    }

    @Test
    fun aPositionPastTheTextGoesToTheNearestPlace() {
        val surface = surface("ab\ncde")
        surface.moveCursor(LspPosition(9, 9), reveal = false)
        assertEquals(6..6, surface.selectionOffsets)
        surface.moveCursor(LspPosition(0, 9), reveal = false)
        assertEquals(2..2, surface.selectionOffsets)
    }

    @Test
    fun insertReplacesTheSelectionAndLeavesTheCursorAfterIt() {
        val surface = surface("abcd")
        surface.state.selection = TextRange(1, 3)
        surface.insertAtCursor("XY")
        assertEquals("aXYd", surface.text)
        assertEquals(3..3, surface.selectionOffsets)
    }

    @Test
    fun editsApplyTogetherAndUndoAsOne() {
        val surface = surface("one\ntwo\n")
        surface.applyEdits(
            listOf(
                LspTextEdit(LspRange(LspPosition(0, 0), LspPosition(0, 3)), "1"),
                LspTextEdit(LspRange(LspPosition(1, 0), LspPosition(1, 3)), "2"),
            ),
        )
        assertEquals("1\n2\n", surface.text)
        surface.undo()
        assertEquals("one\ntwo\n", surface.text)
        surface.redo()
        assertEquals("1\n2\n", surface.text)
    }

    @Test
    fun anEditIsReportedButSetTextIsNot() {
        val surface = surface("a")
        val events = ArrayList<EditorEvent>()
        val subscription = surface.observe { events += it }
        surface.setText("b")
        surface.state.scheduleTokenization()
        assertTrue(events.filterIsInstance<EditorEvent.ContentChanged>().isEmpty())
        surface.insertAtCursor("c")
        val changed = events.filterIsInstance<EditorEvent.ContentChanged>()
        assertEquals(surface.text, changed.single().text)
        subscription.close()
        surface.insertAtCursor("d")
        assertEquals(1, events.filterIsInstance<EditorEvent.ContentChanged>().size)
    }

    @Test
    fun onlyTheSameRangeTurnedRoundCountsAsAnEcho() {
        assertEquals(TextRange(6, 5), turnedRound(TextRange(6, 5), TextRange(5, 6)))
        assertEquals(null, turnedRound(TextRange(6, 5), TextRange(4, 6)))
        assertEquals(null, turnedRound(TextRange(6, 5), TextRange(6, 4)))
        assertEquals(null, turnedRound(TextRange(5), TextRange(5)))
        assertEquals(null, turnedRound(null, TextRange(5, 6)))
    }

    @Test
    fun theIndentUnitFollowsTheFile() {
        val surface = surface("")
        surface.applyConfig(EditorConfig(tabSize = 2, insertSpaces = true))
        assertEquals("  ", surface.indentUnit)
        surface.applyConfig(EditorConfig(tabSize = 2, insertSpaces = false))
        assertEquals("\t", surface.indentUnit)
    }
}
