package com.avfstudio.nativeide.workbench

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.ContentChange
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.SelectionChangeReason
import com.aardarch.aardink.core.TextEdit
import com.avfstudio.nativeide.editor.EditorConfig
import com.avfstudio.nativeide.editor.EditorDecoration
import com.avfstudio.nativeide.editor.EditorEvent
import com.avfstudio.nativeide.editor.EditorSelection
import com.avfstudio.nativeide.editor.EditorSurface
import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspTextEdit
import com.avfstudio.nativeide.editor.VisibleLines
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Aardink behind [EditorSurface], beside `SoraEditorSurface`, while the switch's phases fill it in
 * (see docs/AARDINK-SWITCH-PLAN.md). The text, the selection, undo and the events work; colours,
 * problems, folding and the rest take the interface's do-nothing defaults for now.
 */
internal class AardinkEditorSurface(val state: CodeEditorState) : EditorSurface {
    val focusRequester = FocusRequester()
    private val listeners = CopyOnWriteArrayList<(EditorEvent) -> Unit>()

    /** What the pane draws with; it reads this as state, so a changed setting takes effect at once. */
    var config by mutableStateOf(EditorConfig())
        private set

    override var inlineSuggestionShown = false
    override var parameterHintsShown = false

    /**
     * The direction of a selection the keyboard just made. Samsung's keyboard answers each selection
     * Aardink 0.8.0 reports with `setSelection(start, end)`, which can't say which end the cursor is at,
     * so Shift+Left came back as the same range turned round and the next Shift+Left collapsed it. That
     * echo, and only that one, is turned back, once per key.
     */
    private var keyedSelection: TextRange? = null

    init {
        state.addSelectionListener { ranges, reason ->
            val keyed = keyedSelection
            keyedSelection = null
            val now = ranges.singleOrNull()
            if (reason == SelectionChangeReason.Keyboard && now != null) {
                val restored = turnedRound(keyed, now)
                if (restored != null) state.selection = restored else keyedSelection = now
            }
        }
    }

    override val text: String get() = state.text
    override val selection: EditorSelection
        get() = state.selection.let { EditorSelection(positionAt(it.min), positionAt(it.max)) }
    override val visibleLines: VisibleLines
        get() = state.viewport.let { VisibleLines(it.firstVisibleLine, it.lastVisibleLine) }
    override val lineCount: Int get() = state.document.lineCount
    override fun lineText(line: Int): String = state.document.lineText(line)
    override val selectionOffsets: IntRange get() = state.selection.let { it.min..it.max }
    override fun positionAt(offset: Int): LspPosition =
        state.document.offsetToLineCol(offset.coerceIn(0, state.document.length)).let { LspPosition(it.first, it.second) }

    override fun setText(value: String) = state.loadText(value)

    override fun applyEdits(edits: List<LspTextEdit>) {
        state.executeEdits(edits.map { TextEdit(offsetOf(it.range.start) until offsetOf(it.range.end), it.newText) })
    }

    override fun setSelection(selection: EditorSelection) {
        state.selection = TextRange(offsetOf(selection.anchor), offsetOf(selection.active))
    }

    override fun moveCursor(position: LspPosition, reveal: Boolean) {
        val offset = offsetOf(position)
        state.selection = TextRange(offset)
        if (reveal) state.execute(EditorCommand.Reveal(offset))
    }

    override fun insertAtCursor(value: String) {
        val range = selectionOffsets
        state.executeEdits(
            listOf(TextEdit(range.first until range.last, value)),
            listOf(TextRange(range.first + value.length)),
        )
    }

    override fun outdentSelection(tabSize: Int) = state.outdentSelection()

    override fun undo() {
        state.undo()
    }

    override fun redo() {
        state.redo()
    }

    override fun focus() {
        // Throws until the pane has drawn it; a request that early has nothing to focus yet.
        runCatching { focusRequester.requestFocus() }
    }

    override fun setDecorations(decorations: List<EditorDecoration>) {}

    override fun applyConfig(config: EditorConfig) {
        if (this.config != config) this.config = config
    }

    override val indentUnit: String
        get() = if (config.insertSpaces) " ".repeat(config.tabSize) else "\t"

    /** For what only the pane can see: the viewport, focus, a pinch. */
    fun emit(event: EditorEvent) = listeners.forEach { it(event) }

    override fun observe(listener: (EditorEvent) -> Unit): Closeable {
        listeners += listener
        val content: (List<ContentChange>) -> Unit = { changes ->
            // A reset is setText: what the workbench sent, not an edit to tell it about.
            if (changes.any { !it.isReset }) listener(EditorEvent.ContentChanged("", 0L, text))
        }
        val selected: (List<TextRange>, SelectionChangeReason) -> Unit = { _, _ ->
            listener(EditorEvent.SelectionChanged("", selection))
        }
        state.addContentListener(content)
        state.addSelectionListener(selected)
        return Closeable {
            listeners -= listener
            state.removeContentListener(content)
            state.removeSelectionListener(selected)
        }
    }

    private fun offsetOf(position: LspPosition): Int {
        val line = position.line.coerceIn(0, state.document.lineCount - 1)
        return state.document.lineColToOffset(line, position.character.coerceIn(0, state.document.lineText(line).length))
    }
}

/** [keyed] again when [now] is the same range with its ends swapped; null for any other selection. */
internal fun turnedRound(keyed: TextRange?, now: TextRange): TextRange? =
    keyed?.takeIf { !it.collapsed && now.start == it.end && now.end == it.start }
