package com.avfstudio.nativeide.editor

import com.avfstudio.nativeide.document.DocumentSession
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
    /** The user pinched the editor to [fontSizeSp]. */
    data class Zoomed(override val uri: String, val fontSizeSp: Float) : EditorEvent
    /** A mouse or touchpad pointer rested on [position] (null: it moved off the word; [left]: off the editor). */
    data class PointerRested(override val uri: String, val position: LspPosition?, val left: Boolean = false) : EditorEvent
}

/**
 * What the workbench needs from a code editor, whichever engine draws it (sora-editor today,
 * see `SoraEditorSurface`). The defaults work from [text] alone; engines override them with their
 * own, cheaper versions.
 */
interface EditorSurface {
    val text: String
    val selection: EditorSelection
    val visibleLines: VisibleLines
    val lineCount: Int get() = text.count { it == '\n' } + 1
    fun lineText(line: Int): String = text.split('\n').getOrElse(line) { "" }.removeSuffix("\r")
    /** The selection as character offsets, start first. */
    val selectionOffsets: IntRange
        get() {
            val (anchor, active) = selection.let { positionToOffset(text, it.anchor) to positionToOffset(text, it.active) }
            return minOf(anchor, active)..maxOf(anchor, active)
        }
    fun positionAt(offset: Int): LspPosition = offsetToPosition(text, offset)
    fun setText(value: String)
    /** Applies [edits] in place; editors that can keep the cursor and undo history do so. */
    fun applyEdits(edits: List<LspTextEdit>) = setText(applyLspTextEdits(text, edits))
    fun setSelection(selection: EditorSelection)
    /** Puts the cursor at [position] and, with [reveal], scrolls it into view. */
    fun moveCursor(position: LspPosition, reveal: Boolean = true) = setSelection(EditorSelection(position, position))
    /** Types [value] at the cursor, replacing the selection. */
    fun insertAtCursor(value: String) {
        val range = selectionOffsets
        val edit = LspTextEdit(LspRange(positionAt(range.first), positionAt(range.last)), value)
        applyEdits(listOf(edit))
        moveCursor(edit.endAfterInsert())
    }
    fun deleteSelection() = insertAtCursor("")
    /** Shift+Tab: removes one indentation level from each selected line. */
    fun outdentSelection(tabSize: Int) {
        val first = positionAt(selectionOffsets.first).line
        val last = positionAt(selectionOffsets.last).line
        applyEdits(
            (first..last).mapNotNull { line ->
                val text = lineText(line)
                val width = if (text.startsWith("\t")) 1 else text.takeWhile { it == ' ' }.length.coerceAtMost(tabSize)
                width.takeIf { it > 0 }?.let { LspTextEdit(LspRange(LspPosition(line, 0), LspPosition(line, it)), "") }
            },
        )
    }
    fun selectAll() {
        val last = lineCount - 1
        setSelection(EditorSelection(LspPosition(0, 0), LspPosition(last, lineText(last).length)))
    }
    fun undo() {}
    fun redo() {}
    fun focus() {}
    fun setDecorations(decorations: List<EditorDecoration>)
    /** Breakpoints, the paused line and problem severities beside the line numbers. */
    fun setLineMarks(marks: LineMarks) {}
    /** Problems drawn in the text; [onQuickFix] asks for code actions at a problem. */
    fun setProblems(problems: List<EditorProblem>, config: EditorConfig, onQuickFix: (LspPosition) -> Unit) {}
    fun applyConfig(config: EditorConfig) {}
    /** A Copilot suggestion is shown: Tab accepts it and Escape dismisses it. */
    var inlineSuggestionShown: Boolean
        get() = false
        set(_) {}
    /** Parameter hints are shown: Escape closes them. */
    var parameterHintsShown: Boolean
        get() = false
        set(_) {}
    /** The other uses of the symbol under the cursor, drawn behind the text ([write] ones stronger). */
    fun setSymbolHighlights(highlights: List<Pair<LspRange, Boolean>>) {}
    /** Selects for the find widget: the keys stay in its box, and no cut/copy/paste bar shows. */
    fun selectFound(selection: EditorSelection) = setSelection(selection)
    /** Shows the suggest popup now (Ctrl+Space), whatever was typed. */
    fun triggerSuggest() {}
    /** Where code can fold (from the language server or indentation). */
    fun setFoldingRanges(ranges: List<FoldRange>) {}
    /** Fold, Unfold, Toggle Fold, Fold All, Unfold All at the cursor; false when the editor can't fold. */
    fun runFoldCommand(commandId: String): Boolean = false
    /** The find widget's matches, [current] (an index in [matches], or -1) stronger. */
    fun setFindHighlights(matches: List<LspRange>, current: Int) {}
    /** One level of indentation here, spaces or a tab; null to go by the settings. */
    val indentUnit: String? get() = null
    val selectedText: String get() = selectionOffsets.let { text.substring(it.first, it.last) }
    /** Puts [snippet] over the offsets [from] until [to], with its tab stops active. */
    fun insertSnippet(snippet: ParsedSnippet, from: Int, to: Int) {}
    /** The pointer left what it rested on, so the same place can show a hover again. */
    fun resetPointerRest() {}
    /** A touchpad pinch in window coordinates: whether it was used, or null when it's not over the editor. */
    fun touchpadPinch(event: android.view.MotionEvent): Boolean? = null
    fun observe(listener: (EditorEvent) -> Unit): Closeable
}

/** Where the selection starts (the cursor when nothing is selected) and ends. */
fun EditorSurface.selectionStart(): LspPosition = positionAt(selectionOffsets.first)
fun EditorSurface.selectionEnd(): LspPosition = positionAt(selectionOffsets.last)

interface TextEditorHost {
    fun open(document: DocumentSession, surface: EditorSurface)
    fun close(uri: String)
    /** Closes [uri] only while [surface] shows it: a view going away after another took the document over leaves that one bound. */
    fun close(uri: String, surface: EditorSurface) {
        close(uri)
    }
    fun applyEdits(uri: String, edits: List<LspTextEdit>)
    /** Whether [uri] is shown in an editor now (the pane goes away behind Settings, for one). */
    fun isOpen(uri: String): Boolean
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
                is EditorEvent.Zoomed -> event.copy(uri = document.path)
                is EditorEvent.PointerRested -> event.copy(uri = document.path)
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

    override fun close(uri: String, surface: EditorSurface) {
        bindings.computeIfPresent(uri) { _, binding ->
            if (binding.surface === surface) {
                binding.subscription.close()
                null
            } else {
                binding
            }
        }
    }

    override fun isOpen(uri: String): Boolean = bindings.containsKey(uri)

    override fun applyEdits(uri: String, edits: List<LspTextEdit>) {
        val binding = requireNotNull(bindings[uri]) { "Document is not open: $uri" }
        val updated = applyLspTextEdits(binding.document.text, edits)
        binding.document.updateText(updated)
        binding.suppressContentEvents = true
        try {
            binding.surface.applyEdits(edits)
            if (binding.surface.text != updated) binding.surface.setText(updated)
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

/** Where the cursor ends up after inserting [edit]: just past its new text. */
fun LspTextEdit.endAfterInsert(): LspPosition {
    val lines = newText.split('\n')
    return if (lines.size == 1) {
        LspPosition(range.start.line, range.start.character + newText.length)
    } else {
        LspPosition(range.start.line + lines.size - 1, lines.last().length)
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

/** The position of character [offset], clamped to the text. */
fun offsetToPosition(text: String, offset: Int): LspPosition {
    val clamped = offset.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', clamped - 1) + 1
    return LspPosition(text.substring(0, lineStart).count { it == '\n' }, clamped - lineStart)
}

internal fun positionToOffset(text: String, position: LspPosition): Int {
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
