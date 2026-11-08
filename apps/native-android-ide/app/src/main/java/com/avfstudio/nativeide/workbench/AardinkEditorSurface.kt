package com.avfstudio.nativeide.workbench

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.ContentChange
import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.Decoration
import com.aardarch.aardink.core.DecorationStyle
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.DiagnosticTag
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.FoldRange as AardinkFoldRange
import com.aardarch.aardink.core.SelectionChangeReason
import com.aardarch.aardink.core.TextEdit
import com.avfstudio.nativeide.editor.EditorConfig
import com.avfstudio.nativeide.editor.EditorDecoration
import com.avfstudio.nativeide.editor.EditorEvent
import com.avfstudio.nativeide.editor.EditorSelection
import com.avfstudio.nativeide.editor.EditorSurface
import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.ClosingOvertype
import com.avfstudio.nativeide.editor.LanguageRules
import com.avfstudio.nativeide.editor.EditorProblem
import com.avfstudio.nativeide.editor.FoldRange
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.editor.LspTextEdit
import com.avfstudio.nativeide.editor.ParsedSnippet
import com.avfstudio.nativeide.editor.SemanticSpan
import com.avfstudio.nativeide.editor.TextMateTokenizer
import com.avfstudio.nativeide.editor.VisibleLines
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Aardink behind [EditorSurface], beside `SoraEditorSurface`, while the switch's phases fill it in
 * (see docs/AARDINK-SWITCH-PLAN.md). The text, the selection, undo, the events and colours work;
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

    /** The workbench's problems for the file, which the pane hands the editor. */
    var problems by mutableStateOf<List<Diagnostic>>(emptyList())
        private set

    override fun setProblems(problems: List<EditorProblem>, config: EditorConfig, onQuickFix: (LspPosition) -> Unit) {
        this.problems = if (!config.problemUnderlines) emptyList() else problems.map { problem ->
            val start = offsetOf(LspPosition(problem.startLine, problem.startColumn))
            val end = offsetOf(LspPosition(problem.endLine, problem.endColumn)).coerceAtLeast(start + 1)
            Diagnostic(
                range = start until end,
                message = problem.message,
                severity = when (problem.severity) {
                    2 -> DiagnosticSeverity.Warning
                    3 -> DiagnosticSeverity.Info
                    4 -> DiagnosticSeverity.Hint
                    else -> DiagnosticSeverity.Error
                },
                source = problem.detail.ifBlank { null },
                tags = buildSet {
                    if (problem.unnecessary && config.showUnused) add(DiagnosticTag.Unnecessary)
                    if (problem.deprecated && config.showDeprecated) add(DiagnosticTag.Deprecated)
                },
            )
        }
    }

    /** Find matches and a symbol's other uses, in the app's colours (the pane sets them from the theme). */
    var highlightColors = HighlightColors()

    override fun setSymbolHighlights(highlights: List<Pair<LspRange, Boolean>>) {
        state.setDecorations("symbols", highlights.map { (range, write) ->
            Decoration(
                offsetOf(range.start) until offsetOf(range.end),
                DecorationStyle(
                    background = if (write) highlightColors.symbolWrite else highlightColors.symbol,
                    overviewRulerColor = if (write) highlightColors.symbolWrite else highlightColors.symbol,
                ),
            )
        })
    }

    override fun setFindHighlights(matches: List<LspRange>, current: Int) {
        state.setDecorations("find", matches.mapIndexed { index, range ->
            val color = if (index == current) highlightColors.findCurrent else highlightColors.findMatch
            Decoration(offsetOf(range.start) until offsetOf(range.end), DecorationStyle(background = color, overviewRulerColor = color))
        })
    }

    override fun selectFound(selection: EditorSelection) {
        setSelection(selection)
        state.execute(EditorCommand.Reveal(offsetOf(selection.active), center = true))
    }

    /** The closers auto-closing inserted, which typing the same one steps over. */
    val overtype = ClosingOvertype()

    /** The file's closing and surrounding pairs; the pane sets them with what tells strings and comments apart. */
    var rules: LanguageRules? = null
    var tokenKindAt: (Int) -> String? = { null }

    /**
     * A key typed on a hardware keyboard that pairs do something with, done here before the input method
     * sees it: a closer in front of one auto-closing inserted steps over it, an opener over a selection
     * surrounds it, and an opener closes itself. Done by Samsung's keyboard and Aardink together, the
     * keyboard's idea of the text drifted from the editor's and it typed closers again later. False when
     * [typed] is none of these, and it goes on as usual.
     */
    fun typePairKey(typed: Char): Boolean {
        val selection = state.selection
        val text = state.text
        if (selection.collapsed && overtype.stepsOver(selection.start, typed, text)) {
            state.selection = TextRange(selection.start + 1)
            return true
        }
        val pairs = rules ?: return false
        if (!selection.collapsed) {
            val close = pairs.surroundingPairs[typed] ?: return false
            val (from, to) = selection.min to selection.max
            state.executeEdits(
                listOf(TextEdit(to until to, close), TextEdit(from until from, typed.toString())),
                listOf(TextRange(from + 1, to + 1)),
            )
            return true
        }
        val caret = selection.start
        val withTyped = StringBuilder(text).insert(caret, typed)
        val close = pairs.autoClose(withTyped, caret, typed, tokenKindAt) ?: return false
        overtype.expect(caret, typed + close, closerAt = caret + 1, closer = close)
        state.executeEdits(listOf(TextEdit(caret until caret, typed + close)), listOf(TextRange(caret + 1)))
        return true
    }

    override fun triggerSuggest() {
        state.execute(EditorCommand.TriggerSuggest)
    }

    /** Folds where the workbench says code can fold (the language server's ranges, or indentation's). */
    val foldState = FoldState()
    @Volatile
    private var foldRanges: List<AardinkFoldRange> = emptyList()
    val foldingProvider = FoldingProvider { foldRanges }

    override fun setFoldingRanges(ranges: List<FoldRange>) {
        foldRanges = ranges.map { AardinkFoldRange(it.startLine, it.endLine) }
        foldState.updateFoldableRanges(foldRanges)
    }

    override fun runFoldCommand(commandId: String): Boolean {
        val line = positionAt(selectionOffsets.first).line
        when (commandId) {
            "editor.fold" -> state.execute(EditorCommand.Fold)
            "editor.unfold" -> state.execute(EditorCommand.Unfold)
            "editor.toggleFold" -> if (foldState.isFolded(line)) state.execute(EditorCommand.Unfold) else state.execute(EditorCommand.Fold)
            "editor.foldAll" -> state.execute(EditorCommand.FoldAll)
            "editor.unfoldAll" -> state.execute(EditorCommand.UnfoldAll)
            else -> commandId.removePrefix("editor.foldLevel").toIntOrNull()?.let { state.execute(EditorCommand.FoldLevel(it)) } ?: return false
        }
        return true
    }

    /**
     * Aardink's own snippet engine, which indents the snippet as the line it lands on; the workbench
     * has indented it already, so that indentation comes off again first.
     */
    override fun insertSnippet(snippet: ParsedSnippet, from: Int, to: Int) {
        val indentation = lineText(positionAt(from).line).takeWhile { it == ' ' || it == '\t' }
        val syntax = snippetSyntax(snippet).let { if (indentation.isEmpty()) it else it.replace("\n$indentation", "\n") }
        state.selection = TextRange(from, to)
        state.execute(EditorCommand.InsertSnippet(syntax))
    }

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

    /** The file's grammar, when one knows it; semantic colours are drawn through it. */
    var tokenizer: TextMateTokenizer? = null

    override fun setDecorations(decorations: List<EditorDecoration>) {
        val grammar = tokenizer ?: return
        grammar.semantic = decorations.mapNotNull { decoration ->
            val (start, end) = decoration.range
            if (start.line != end.line || end.character <= start.character) return@mapNotNull null
            SemanticSpan(start.line, start.character, end.character, AardinkSyntax.semanticType(decoration.kind))
        }.groupBy { it.line }.mapValues { (_, spans) -> spans.sortedBy { it.start } }
        state.scheduleTokenization()
    }

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

internal data class HighlightColors(
    val symbol: Color = Color(0x2E7F7FFF),
    val symbolWrite: Color = Color(0x42FF7FBF),
    val findMatch: Color = Color(0x47FF8F5F),
    val findCurrent: Color = Color(0x99FF8F5F),
)
