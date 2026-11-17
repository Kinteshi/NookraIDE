package io.github.kinteshi.quirecode.workbench

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
import io.github.kinteshi.quirecode.editor.EditorConfig
import io.github.kinteshi.quirecode.editor.EditorDecoration
import io.github.kinteshi.quirecode.editor.EditorEvent
import io.github.kinteshi.quirecode.editor.EditorSelection
import io.github.kinteshi.quirecode.editor.EditorSurface
import io.github.kinteshi.quirecode.editor.LspPosition
import io.github.kinteshi.quirecode.editor.ClosingOvertype
import io.github.kinteshi.quirecode.editor.LanguageRules
import io.github.kinteshi.quirecode.editor.EditorProblem
import io.github.kinteshi.quirecode.editor.LineMarks
import io.github.kinteshi.quirecode.editor.FoldRange
import io.github.kinteshi.quirecode.editor.LspRange
import io.github.kinteshi.quirecode.editor.LspTextEdit
import io.github.kinteshi.quirecode.editor.ParsedSnippet
import io.github.kinteshi.quirecode.editor.SemanticSpan
import io.github.kinteshi.quirecode.editor.SemanticTokenizer
import io.github.kinteshi.quirecode.editor.VisibleLines
import java.io.Closeable
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Aardink editor behind [EditorSurface] (see docs/AARDINK-SWITCH-PLAN.md): what the workbench reads and changes
 * in the editor goes through here.
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
    /** Without a CR LF file's `\r`, which Aardink keeps on the line: the workbench's line features count without it. */
    override fun lineText(line: Int): String = state.document.lineText(line).removeSuffix("\r")
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

    /**
     * Breakpoints as a bar in the gutter, in a colour for each kind (Aardink has no gutter icons yet,
     * request 2), and the paused line tinted across. Problems need nothing here: their diagnostics put
     * a dot in the gutter already.
     */
    override fun setLineMarks(marks: LineMarks) {
        val lines = state.document.lineCount
        val decorations = buildList {
            marks.breakpoints.filter { it in 0 until lines }.forEach { line ->
                val color = when (line) {
                    in marks.logpoints -> LOGPOINT_COLOR
                    in marks.conditionalBreakpoints -> CONDITIONAL_BREAKPOINT_COLOR
                    else -> BREAKPOINT_COLOR
                }
                val start = state.document.lineStart(line)
                add(Decoration(start until start, DecorationStyle(gutterColor = color, overviewRulerColor = color)))
            }
            marks.pausedLine?.takeIf { it in 0 until lines }?.let { line ->
                add(Decoration(lineRange(line, line), DecorationStyle(background = PAUSED_LINE_COLOR, wholeLine = true, gutterColor = PAUSED_GUTTER_COLOR, overviewRulerColor = PAUSED_GUTTER_COLOR)))
            }
        }
        state.setDecorations("line-marks", decorations)
    }

    /** Whole lines tinted for [owner]: merge conflicts' sides, Python cells' markers. */
    fun setLineTints(owner: String, tints: List<LineTint>) {
        val lines = state.document.lineCount
        state.setDecorations(owner, tints.mapNotNull { tint ->
            if (tint.last < tint.first || tint.first !in 0 until lines) return@mapNotNull null
            Decoration(
                lineRange(tint.first, tint.last.coerceAtMost(lines - 1)),
                DecorationStyle(background = tint.color, wholeLine = true, overviewRulerColor = tint.ruler),
            )
        })
    }

    /** Text after the end of each line in [notes] (line → text) for [owner], in [color]: inline values, blame. */
    fun setLineEndNotes(owner: String, notes: Map<Int, String>, color: Color) {
        val lines = state.document.lineCount
        state.setDecorations(owner, notes.mapNotNull { (line, note) ->
            if (line !in 0 until lines || note.isBlank()) return@mapNotNull null
            val end = state.document.lineStart(line) + lineText(line).length
            Decoration(end until end, DecorationStyle(after = "    $note", inlineColor = color))
        })
    }

    /**
     * Copilot's next edit in the text: what it takes out tinted red and underlined, and what it puts in after it in green, on
     * one row (line breaks show as ⏎). The whole of it is in the card the pane docks at an edge.
     */
    fun setNextEdit(preview: NextEditPreview?) {
        val decorations = preview?.let { nextEditDecorations(it) }.orEmpty()
        state.setDecorations("next-edit", decorations)
    }

    private fun nextEditDecorations(preview: NextEditPreview): List<Decoration> {
        if (preview.firstLine !in 0 until state.document.lineCount) return emptyList()
        val base = state.document.lineStart(preview.firstLine)
        val length = state.document.length
        val removedStart = (base + preview.oldChange.first).coerceIn(0, length)
        val removedEnd = if (preview.oldChange.isEmpty()) removedStart else (base + preview.oldChange.last + 1).coerceIn(removedStart, length)
        val added = if (preview.newChange.isEmpty()) "" else preview.newText.substring(preview.newChange.first, preview.newChange.last + 1)
        return buildList {
            if (removedEnd > removedStart) {
                add(Decoration(removedStart until removedEnd, DecorationStyle(background = NEXT_EDIT_REMOVED.copy(alpha = 0.22f), underline = NEXT_EDIT_REMOVED, overviewRulerColor = NEXT_EDIT_ADDED)))
            }
            if (added.isNotEmpty()) {
                val shown = added.replace("\r", "").replace("\n", " ⏎ ").let { if (it.length > 80) it.take(79) + "…" else it }
                add(Decoration(removedEnd until removedEnd, DecorationStyle(after = shown, inlineColor = NEXT_EDIT_ADDED, overviewRulerColor = NEXT_EDIT_ADDED)))
            }
        }
    }

    /** Find in selection's scope, shaded while the find widget is open (VS Code's `findRangeHighlightBackground`). */
    fun setFindScope(scope: IntRange?, color: Color) {
        val length = state.document.length
        val range = scope?.takeIf { !it.isEmpty() && it.first < length }?.let { it.first until minOf(it.last + 1, length) }
        state.setDecorations("find-scope", listOfNotNull(range?.let { Decoration(it, DecorationStyle(background = color)) }))
    }

    /** From the start of [first] to the end of [last], as a decoration's range. */
    private fun lineRange(first: Int, last: Int): IntRange {
        val start = state.document.lineStart(first)
        val end = state.document.lineStart(last) + lineText(last).length
        return start until end
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

    /**
     * A key typed on a hardware keyboard that makes its line match the language's decrease-indent rule (`else:` after a
     * block): the line moves back a level as the key goes in, one edit and one undo. Before the input method, as with
     * [typePairKey]. False when it doesn't apply, and the key goes on as usual.
     */
    fun typeOutdentKey(typed: Char): Boolean {
        val language = rules ?: return false
        val selection = state.selection
        if (!selection.collapsed || typed == '\n') return false
        val caret = selection.start
        val line = state.document.offsetToLineCol(caret).first
        val lineStart = state.document.lineStart(line)
        val before = lineText(line)
        val column = (caret - lineStart).coerceIn(0, before.length)
        val after = before.substring(0, column) + typed + before.substring(column)
        val current = after.takeWhile { it == ' ' || it == '\t' }
        if (column < current.length) return false
        val above = (line - 1 downTo maxOf(0, line - MAX_LINES_ABOVE)).asSequence().map(state.document::lineText).firstOrNull { it.isNotBlank() }
        val indentation = language.outdentAsTyped(before, after, above, indentUnit) ?: return false
        state.executeEdits(
            listOf(TextEdit(caret until caret, typed.toString()), TextEdit(lineStart until lineStart + current.length, indentation)),
            listOf(TextRange(caret + 1 - current.length + indentation.length)),
        )
        return true
    }

    /**
     * Where text is on screen, from Aardink's internals (A19); null until the editor is laid out, or when they
     * can't be reached. With it, the workbench's own cards are drawn at their text, and Aardink's hover and
     * signature popups give way to them.
     */
    var geometry by mutableStateOf<AardinkGeometry?>(null)

    /** The word the mouse rests on, so moving within it asks nothing new; cleared once a card closes. */
    private var restingOn: IntRange? = null
    private var restJob: kotlinx.coroutines.Job? = null

    /**
     * The mouse at [rootPoint] over the text (null: off the text), from what Aardink's hover tracking saw: once it
     * rests on a word for the hover delay, the workbench is told.
     */
    fun pointerAt(rootPoint: androidx.compose.ui.geometry.Offset?, scope: kotlinx.coroutines.CoroutineScope) {
        val offset = rootPoint?.let { geometry?.offsetAt(it) }
        val word = offset?.let { wordRange(state.text, it) }
        if (word == restingOn) return
        restJob?.cancel()
        if (restingOn != null) emit(EditorEvent.PointerRested("", null))
        restingOn = word
        if (word == null || !config.hover) return
        restJob = scope.launch {
            kotlinx.coroutines.delay(config.hoverDelayMs.toLong())
            emit(EditorEvent.PointerRested("", positionAt(offset)))
        }
    }

    /** The mouse left the editor. */
    fun pointerLeft() {
        restJob?.cancel()
        if (restingOn != null) emit(EditorEvent.PointerRested("", null, left = true))
        restingOn = null
    }

    override fun resetPointerRest() {
        restingOn = null
    }

    /** Our list for a hardware keyboard while it's in charge (the pane sets it); Aardink's strip otherwise. */
    var suggestions: KeyboardSuggestions? = null

    /**
     * Backspace on a hardware keyboard, where VS Code does more than Aardink's one character: between a pair whose closer
     * auto-closing inserted, both go (`editor.autoClosingDelete`); in a line's leading spaces, back to the previous tab
     * stop (`editor.useTabStops`). False otherwise, and the key goes on as usual.
     */
    fun backspace(): Boolean = deletePair() || backspaceToTabStop()

    private fun deletePair(): Boolean {
        val selection = state.selection
        if (!selection.collapsed || selection.start == 0) return false
        val caret = selection.start
        val text = state.text
        val opener = text[caret - 1]
        val closer = rules?.autoClosingPairs?.firstOrNull { it.open == opener.toString() }?.close ?: return false
        if (!text.startsWith(closer, caret) || !overtype.autoClosedAt(caret, text)) return false
        state.executeEdits(listOf(TextEdit(caret - 1 until caret + closer.length, "")), listOf(TextRange(caret - 1)))
        return true
    }

    fun backspaceToTabStop(): Boolean {
        val config = config
        if (!config.useTabStops || !config.insertSpaces || config.tabSize < 2) return false
        val selection = state.selection
        if (!selection.collapsed) return false
        val caret = selection.start
        val line = state.document.offsetToLineCol(caret).first
        val lineStart = state.document.lineStart(line)
        val column = caret - lineStart
        if (column == 0 || (0 until column).any { lineText(line)[it] != ' ' }) return false
        val target = (column - 1) / config.tabSize * config.tabSize
        state.executeEdits(listOf(TextEdit(lineStart + target until caret, "")), listOf(TextRange(lineStart + target)))
        return true
    }

    override fun triggerSuggest() {
        suggestions?.let { return it.ask(invoked = true) }
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

    /** The file's tokenizer, when one knows it; semantic colours are drawn through it. */
    var tokenizer: SemanticTokenizer? = null

    override fun setDecorations(decorations: List<EditorDecoration>) {
        val grammar = tokenizer ?: return
        val nextSemantic = decorations.mapNotNull { decoration ->
            val (start, end) = decoration.range
            if (start.line != end.line || end.character <= start.character) return@mapNotNull null
            SemanticSpan(start.line, start.character, end.character, AardinkSyntax.semanticType(decoration.kind))
        }.groupBy { it.line }.mapValues { (_, spans) -> spans.sortedBy { it.start } }
        if (grammar.semantic != nextSemantic) {
            grammar.semantic = nextSemantic
            state.scheduleTokenization()
        }
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
        return state.document.lineColToOffset(line, position.character.coerceIn(0, lineText(line).length))
    }
}

/** The word (letters, digits, `_`) at [offset] in [text], or null when there's none under it. */
internal fun wordRange(text: CharSequence, offset: Int): IntRange? {
    fun word(char: Char) = char.isLetterOrDigit() || char == '_'
    if (offset !in text.indices || !word(text[offset])) return null
    var start = offset
    while (start > 0 && word(text[start - 1])) start--
    var end = offset
    while (end < text.length && word(text[end])) end++
    return start until end
}

/** [keyed] again when [now] is the same range with its ends swapped; null for any other selection. */
internal fun turnedRound(keyed: TextRange?, now: TextRange): TextRange? =
    keyed?.takeIf { !it.collapsed && now.start == it.end && now.end == it.start }

/** Lines [first] to [last] tinted [color], marked in the overview ruler with [ruler] if given. */
internal data class LineTint(val first: Int, val last: Int, val color: Color, val ruler: Color = Color.Unspecified)

/** VS Code's breakpoint red; the other kinds get their own colour while the gutter shows bars, not icons. */
/** How far up a typed `else:` looks for the line it lines up against. */
private const val MAX_LINES_ABOVE = 200

internal val BREAKPOINT_COLOR = Color(0xFFE51400)
internal val CONDITIONAL_BREAKPOINT_COLOR = Color(0xFFFF8C00)
internal val LOGPOINT_COLOR = Color(0xFFB180D7)
private val PAUSED_LINE_COLOR = Color(0x40FFCC00)
internal val NEXT_EDIT_REMOVED = Color(0xFFE5534B)
internal val NEXT_EDIT_ADDED = Color(0xFF3FB950)
private val PAUSED_GUTTER_COLOR = Color(0xFFFFCC00)

/**
 * Merge conflicts tinted as VS Code does (current changes green, incoming blue, the marker lines
 * stronger), and each Python cell's `# %%` line, faintly, as a rule above the cell.
 */
internal fun conflictTints(conflicts: List<MergeConflict>): List<LineTint> {
    val current = Color(0xFF40C8AE)
    val incoming = Color(0xFF40A6FF)
    return conflicts.flatMap { conflict ->
        val currentEnd = (conflict.base ?: conflict.separator) - 1
        listOf(
            LineTint(conflict.start, conflict.start, current.copy(alpha = 0.35f), current),
            LineTint(conflict.start + 1, currentEnd, current.copy(alpha = 0.14f)),
            LineTint(conflict.separator + 1, conflict.end - 1, incoming.copy(alpha = 0.14f)),
            LineTint(conflict.end, conflict.end, incoming.copy(alpha = 0.35f), incoming),
        )
    }
}

internal fun cellTints(cells: List<PythonCell>, color: Color): List<LineTint> =
    cells.map { LineTint(it.marker, it.marker, color) }

internal data class HighlightColors(
    val symbol: Color = Color(0x2E7F7FFF),
    val symbolWrite: Color = Color(0x42FF7FBF),
    val findMatch: Color = Color(0x47FF8F5F),
    val findCurrent: Color = Color(0x99FF8F5F),
)
