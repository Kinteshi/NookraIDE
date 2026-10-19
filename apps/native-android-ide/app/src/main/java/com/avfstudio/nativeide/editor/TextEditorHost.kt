package com.avfstudio.nativeide.editor

import com.avfstudio.nativeide.document.DocumentSession
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorFocusChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class LspPosition(val line: Int, val character: Int)
data class LspRange(val start: LspPosition, val end: LspPosition)
data class LspTextEdit(val range: LspRange, val newText: String)
data class EditorSelection(val anchor: LspPosition, val active: LspPosition)
data class VisibleLines(val first: Int, val last: Int)
data class EditorDecoration(val range: LspRange, val kind: String, val value: String)

sealed interface EditorEvent {
    val uri: String

    data class ContentChanged(override val uri: String, val version: Long, val text: String) : EditorEvent
    data class SelectionChanged(override val uri: String, val selection: EditorSelection) : EditorEvent
    data class ViewportChanged(override val uri: String, val visibleLines: VisibleLines) : EditorEvent
    data class FocusChanged(override val uri: String, val focused: Boolean) : EditorEvent
}

interface EditorSurface {
    val text: String
    val selection: EditorSelection
    val visibleLines: VisibleLines
    fun setText(value: String)
    fun setSelection(selection: EditorSelection)
    fun setDecorations(decorations: List<EditorDecoration>)
    fun observe(listener: (EditorEvent) -> Unit): Closeable
}

interface TextEditorHost {
    fun open(document: DocumentSession, surface: EditorSurface)
    fun close(uri: String)
    fun applyEdits(uri: String, edits: List<LspTextEdit>)
    fun setDecorations(uri: String, decorations: List<EditorDecoration>)
    fun viewport(uri: String): VisibleLines?
    fun selection(uri: String): EditorSelection?
    fun observe(listener: (EditorEvent) -> Unit): Closeable
}

class SoraTextEditorHost : TextEditorHost {
    private val bindings = ConcurrentHashMap<String, Binding>()
    private val listeners = CopyOnWriteArraySet<(EditorEvent) -> Unit>()

    override fun open(document: DocumentSession, surface: EditorSurface) {
        close(document.path)
        val binding = Binding(document, surface)
        binding.subscription = surface.observe { event ->
            val normalized = when (event) {
                is EditorEvent.ContentChanged -> event.copy(uri = document.path)
                is EditorEvent.SelectionChanged -> event.copy(uri = document.path)
                is EditorEvent.ViewportChanged -> event.copy(uri = document.path)
                is EditorEvent.FocusChanged -> event.copy(uri = document.path)
            }
            when (normalized) {
                is EditorEvent.ContentChanged -> {
                    if (!binding.suppressContentEvents) {
                        if (normalized.text != document.text) document.updateText(normalized.text)
                        emit(normalized.copy(version = document.version))
                    }
                }
                else -> emit(normalized)
            }
        }
        bindings[document.path] = binding
        if (surface.text != document.text) surface.setText(document.text)
    }

    override fun close(uri: String) {
        bindings.remove(uri)?.subscription?.close()
    }

    override fun applyEdits(uri: String, edits: List<LspTextEdit>) {
        val binding = requireNotNull(bindings[uri]) { "Document is not open: $uri" }
        val updated = applyLspTextEdits(binding.document.text, edits)
        binding.document.updateText(updated)
        binding.suppressContentEvents = true
        try {
            binding.surface.setText(updated)
        } finally {
            binding.suppressContentEvents = false
        }
        emit(EditorEvent.ContentChanged(uri, binding.document.version, updated))
    }

    override fun setDecorations(uri: String, decorations: List<EditorDecoration>) {
        val binding = requireNotNull(bindings[uri]) { "Document is not open: $uri" }
        binding.decorations = decorations
        binding.surface.setDecorations(decorations)
    }

    override fun viewport(uri: String): VisibleLines? = bindings[uri]?.surface?.visibleLines
    override fun selection(uri: String): EditorSelection? = bindings[uri]?.surface?.selection

    override fun observe(listener: (EditorEvent) -> Unit): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }

    private fun emit(event: EditorEvent) {
        listeners.forEach { it(event) }
    }

    private data class Binding(
        val document: DocumentSession,
        val surface: EditorSurface,
        var decorations: List<EditorDecoration> = emptyList(),
        var subscription: Closeable = Closeable {},
        var suppressContentEvents: Boolean = false,
    )
}

class SoraEditorSurface(
    private val editor: CodeEditor,
    private val styleOverlay: SoraBracketColorizer,
) : EditorSurface {
    override val text: String get() = editor.text.toString()
    override val selection: EditorSelection
        get() {
            val cursor = editor.cursor
            return EditorSelection(
                LspPosition(cursor.leftLine, cursor.leftColumn),
                LspPosition(cursor.rightLine, cursor.rightColumn),
            )
        }
    override val visibleLines: VisibleLines
        get() = VisibleLines(editor.firstVisibleLine, editor.lastVisibleLine)

    override fun setText(value: String) {
        editor.setText(value)
    }

    override fun setSelection(selection: EditorSelection) {
        editor.setSelectionRegion(
            selection.anchor.line,
            selection.anchor.character,
            selection.active.line,
            selection.active.character,
        )
    }

    override fun setDecorations(decorations: List<EditorDecoration>) {
        styleOverlay.setSemanticDecorations(decorations)
    }

    override fun observe(listener: (EditorEvent) -> Unit): Closeable {
        val contentReceipt = editor.subscribeAlways(ContentChangeEvent::class.java) { event ->
            if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                listener(EditorEvent.ContentChanged("", 0L, text))
            }
        }
        val selectionReceipt = editor.subscribeAlways(SelectionChangeEvent::class.java) { event ->
            listener(
                EditorEvent.SelectionChanged(
                    "",
                    EditorSelection(
                        LspPosition(event.left.line, event.left.column),
                        LspPosition(event.right.line, event.right.column),
                    ),
                ),
            )
        }
        val scrollReceipt = editor.subscribeAlways(ScrollEvent::class.java) {
            listener(EditorEvent.ViewportChanged("", visibleLines))
        }
        val focusReceipt = editor.subscribeAlways(EditorFocusChangeEvent::class.java) { event ->
            listener(EditorEvent.FocusChanged("", event.isGainFocus))
        }
        return Closeable {
            contentReceipt.unsubscribe()
            selectionReceipt.unsubscribe()
            scrollReceipt.unsubscribe()
            focusReceipt.unsubscribe()
        }
    }
}

fun applyLspTextEdits(text: String, edits: List<LspTextEdit>): String {
    val indexed = edits.map { edit ->
        val start = positionToOffset(text, edit.range.start)
        val end = positionToOffset(text, edit.range.end)
        require(start <= end) { "Text edit range is reversed" }
        Triple(start, end, edit.newText)
    }
    val result = StringBuilder(text)
    indexed.sortedByDescending { it.first }.forEach { (start, end, replacement) ->
        result.replace(start, end, replacement)
    }
    return result.toString()
}

private fun positionToOffset(text: String, position: LspPosition): Int {
    require(position.line >= 0 && position.character >= 0) { "LSP positions cannot be negative" }
    var lineStart = 0
    repeat(position.line) {
        val newline = text.indexOf('\n', lineStart)
        require(newline >= 0) { "LSP line ${position.line} is outside the document" }
        lineStart = newline + 1
    }
    var lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
    if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd--
    require(position.character <= lineEnd - lineStart) {
        "LSP character ${position.character} is outside line ${position.line}"
    }
    return lineStart + position.character
}
