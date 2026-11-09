package io.github.kinteshi.quirecode.editor

import io.github.kinteshi.quirecode.workbench.indentOrInsertTab
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.Closeable

/** A surface with only text and a selection: the defaults any editor engine gets for free. */
private class TextSurface(initialText: String) : EditorSurface {
    private var current = initialText
    private var selected = EditorSelection(LspPosition(0, 0), LspPosition(0, 0))
    override val text: String get() = current
    override val selection: EditorSelection get() = selected
    override val visibleLines = VisibleLines(0, 0)
    override fun setText(value: String) {
        current = value
    }
    override fun setSelection(selection: EditorSelection) {
        selected = selection
    }
    override fun setDecorations(decorations: List<EditorDecoration>) {}
    override fun observe(listener: (EditorEvent) -> Unit) = Closeable {}
    fun select(fromLine: Int, fromColumn: Int, toLine: Int, toColumn: Int) =
        setSelection(EditorSelection(LspPosition(fromLine, fromColumn), LspPosition(toLine, toColumn)))
}

class EditorSurfaceDefaultsTest {
    @Test
    fun selectedTextIsWhatTheSelectionCovers() {
        val surface = TextSurface("ab\ncde\n")
        surface.select(0, 1, 1, 2)
        assertEquals("b\ncd", surface.selectedText)
        surface.select(1, 1, 1, 1)
        assertEquals("", surface.selectedText)
    }

    @Test
    fun offsetsAndPositionsRoundTrip() {
        val surface = TextSurface("ab\ncde\n")
        assertEquals(LspPosition(1, 1), surface.positionAt(4))
        assertEquals(LspPosition(2, 0), surface.positionAt(99))
        assertEquals(3, surface.lineCount)
        assertEquals("cde", surface.lineText(1))
        surface.select(1, 2, 0, 1)
        assertEquals(1..5, surface.selectionOffsets)
        assertEquals(LspPosition(0, 1), surface.selectionStart())
    }

    @Test
    fun insertingReplacesTheSelectionAndLeavesTheCursorAfterIt() {
        val surface = TextSurface("hello world")
        surface.select(0, 6, 0, 11)
        surface.insertAtCursor("there\nfriend")
        assertEquals("hello there\nfriend", surface.text)
        assertEquals(LspPosition(1, 6), surface.selection.active)
        surface.select(0, 0, 0, 6)
        surface.deleteSelection()
        assertEquals("there\nfriend", surface.text)
    }

    @Test
    fun outdentAndSelectAllWorkFromTextAlone() {
        val surface = TextSurface("        a\n\tb\n  c")
        surface.select(0, 0, 2, 1)
        surface.outdentSelection(4)
        assertEquals("    a\nb\nc", surface.text)
        surface.selectAll()
        assertEquals(EditorSelection(LspPosition(0, 0), LspPosition(2, 1)), surface.selection)
    }

    @Test
    fun tabIndentsSelectedLinesButNotBlankOnesOrAnUntouchedLastLine() {
        val surface = TextSurface("a\n\nb\nc")
        surface.select(0, 1, 3, 0)
        indentOrInsertTab(surface, 4, insertSpaces = true)
        assertEquals("    a\n\n    b\nc", surface.text)
        assertEquals(EditorSelection(LspPosition(0, 5), LspPosition(3, 0)), surface.selection)
        surface.select(3, 1, 3, 1)
        indentOrInsertTab(surface, 4, insertSpaces = true)
        assertEquals("    a\n\n    b\nc   ", surface.text)
    }
}
