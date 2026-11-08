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

    @Test
    fun breakpointsAreGutterBarsByKindAndThePausedLineIsTintedAcross() {
        val surface = surface("a = 1\nb = 2\nc = 3\n")
        surface.setLineMarks(
            com.avfstudio.nativeide.editor.LineMarks(
                breakpoints = setOf(0, 1, 2, 9),
                conditionalBreakpoints = setOf(1),
                logpoints = setOf(2),
                pausedLine = 1,
            ),
        )
        val marks = surface.state.decorations("line-marks")
        // The line past the end is left out.
        assertEquals(4, marks.size)
        val bars = marks.filter { !it.style.wholeLine }
        assertEquals(listOf(0 until 0, 6 until 6, 12 until 12), bars.map { it.range })
        assertEquals(listOf(BREAKPOINT_COLOR, CONDITIONAL_BREAKPOINT_COLOR, LOGPOINT_COLOR), bars.map { it.style.gutterColor })
        val paused = marks.single { it.style.wholeLine }
        assertEquals(6 until 11, paused.range)
        surface.setLineMarks(com.avfstudio.nativeide.editor.LineMarks())
        assertTrue(surface.state.decorations("line-marks").isEmpty())
    }

    @Test
    fun conflictSidesAreTintedAndTheirMarkersShowInTheRuler() {
        val text = "<<<<<<< HEAD\nmine\n=======\ntheirs\n>>>>>>> branch\n"
        val surface = surface(text)
        val conflict = MergeConflict(start = 0, base = null, separator = 2, end = 4, currentLabel = "HEAD", incomingLabel = "branch")
        surface.setLineTints("conflicts", conflictTints(listOf(conflict)))
        val tints = surface.state.decorations("conflicts")
        assertEquals(listOf(0 until 12, 13 until 17, 26 until 32, 33 until 47), tints.map { it.range })
        assertTrue(tints.all { it.style.wholeLine })
        assertEquals(2, tints.count { it.style.overviewRulerColor != androidx.compose.ui.graphics.Color.Unspecified })
    }

    @Test
    fun findScopeIsShadedAndGoesWhenCleared() {
        val surface = surface("abcdef")
        val shade = androidx.compose.ui.graphics.Color.Gray
        surface.setFindScope(1..3, shade)
        assertEquals(listOf(1 until 4), surface.state.decorations("find-scope").map { it.range })
        surface.setFindScope(null, shade)
        assertTrue(surface.state.decorations("find-scope").isEmpty())
    }

    @Test
    fun aNextEditMarksWhatGoesAndShowsWhatComesAfterIt() {
        val surface = surface("x = 1\ny = old(2)\n")
        val preview = nextEditPreview(surface.text.split('\n'), LspRange(LspPosition(1, 4), LspPosition(1, 7)), "new")!!
        surface.setNextEdit(preview)
        val marks = surface.state.decorations("next-edit")
        assertEquals(listOf(10 until 13, 13 until 13), marks.map { it.range })
        assertEquals("new", marks[1].style.after)
        // An insertion across lines shows on one row.
        surface.setNextEdit(nextEditPreview(surface.text.split('\n'), LspRange(LspPosition(0, 5), LspPosition(0, 5)), "\nz = 3"))
        assertEquals(listOf(" ⏎ z = 3"), surface.state.decorations("next-edit").mapNotNull { it.style.after })
        surface.setNextEdit(null)
        assertTrue(surface.state.decorations("next-edit").isEmpty())
    }

    @Test
    fun aTypedElseColonMovesTheLineBackInOneUndo() {
        val surface = surface("if x:\n    y = 1\n    else")
        surface.rules = com.avfstudio.nativeide.editor.LanguageRules.parse(
            java.io.File("src/main/assets/textmate/python.language-configuration.json").readText(),
        )
        surface.state.selection = TextRange(surface.text.length)
        assertTrue(surface.typeOutdentKey(':'))
        assertEquals("if x:\n    y = 1\nelse:", surface.text)
        assertEquals(surface.text.length..surface.text.length, surface.selectionOffsets)
        // Any other key goes on as usual.
        org.junit.Assert.assertFalse(surface.typeOutdentKey(' '))
        surface.undo()
        assertEquals("if x:\n    y = 1\n    else", surface.text)
    }
}
