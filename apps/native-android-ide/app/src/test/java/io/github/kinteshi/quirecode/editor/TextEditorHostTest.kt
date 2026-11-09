package io.github.kinteshi.quirecode.editor

import io.github.kinteshi.quirecode.document.DocumentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class TextEditorHostTest {
    @Test
    fun lspEditsUseUtf16PositionsAndApplyAgainstOriginalDocument() {
        val updated = applyLspTextEdits(
            "a😀b\r\nc",
            listOf(
                LspTextEdit(LspRange(LspPosition(0, 3), LspPosition(0, 4)), "B"),
                LspTextEdit(LspRange(LspPosition(1, 0), LspPosition(1, 1)), "C"),
                LspTextEdit(LspRange(LspPosition(0, 0), LspPosition(0, 1)), "A"),
            ),
        )

        assertEquals("A😀B\r\nC", updated)
    }

    @Test
    fun hostKeepsDocumentAndSurfaceInSyncAndPublishesEvents() {
        val document = DocumentSession("/workspace/a.py", "one", 1, dirty = false)
        val surface = FakeEditorSurface("one")
        val host = EditorSurfaceHost()
        val events = mutableListOf<EditorEvent>()
        val subscription = host.observe(events::add)
        host.open(document, surface)

        host.applyEdits(
            document.path,
            listOf(LspTextEdit(LspRange(LspPosition(0, 0), LspPosition(0, 3)), "two")),
        )

        assertEquals("two", document.text)
        assertEquals("two", surface.text)
        assertTrue(document.dirty)
        assertEquals(document.path, (events.single() as EditorEvent.ContentChanged).uri)
        subscription.close()
        host.close(document.path)
    }

    @Test
    fun hostForwardsDecorationsToTheOpenSurface() {
        val document = DocumentSession("/workspace/a.py", "value", 1, dirty = false)
        val surface = FakeEditorSurface("value")
        val host = EditorSurfaceHost()
        val decorations = listOf(
            EditorDecoration(
                LspRange(LspPosition(0, 0), LspPosition(0, 5)),
                kind = "variable",
                value = "",
            ),
        )
        host.open(document, surface)

        host.setDecorations(document.path, decorations)

        assertEquals(decorations, surface.decorations)
        host.close(document.path)
    }
}

private class FakeEditorSurface(initialText: String) : EditorSurface {
    private var currentText = initialText
    var decorations: List<EditorDecoration> = emptyList()
        private set
    private var currentSelection = EditorSelection(LspPosition(0, 0), LspPosition(0, 0))
    override val text: String get() = currentText
    override val selection: EditorSelection get() = currentSelection
    override val visibleLines = VisibleLines(0, 0)
    private val listeners = mutableListOf<(EditorEvent) -> Unit>()

    override fun setText(value: String) {
        currentText = value
        listeners.forEach { it(EditorEvent.ContentChanged("", 0, value)) }
    }

    override fun setSelection(selection: EditorSelection) {
        currentSelection = selection
    }

    override fun setDecorations(decorations: List<EditorDecoration>) {
        this.decorations = decorations
    }

    override fun observe(listener: (EditorEvent) -> Unit): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }
}
