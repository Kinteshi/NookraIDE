package com.avfstudio.nativeide.workbench

import android.graphics.drawable.GradientDrawable
import com.avfstudio.nativeide.editor.EditorConfig
import com.avfstudio.nativeide.editor.EditorDecoration
import com.avfstudio.nativeide.editor.EditorEvent
import com.avfstudio.nativeide.editor.EditorProblem
import com.avfstudio.nativeide.editor.EditorSelection
import com.avfstudio.nativeide.editor.EditorSurface
import com.avfstudio.nativeide.editor.LineMarks
import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspTextEdit
import com.avfstudio.nativeide.editor.SoraBracketColorizer
import com.avfstudio.nativeide.editor.VisibleLines
import com.avfstudio.nativeide.editor.tagDecorations
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorFocusChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.diagnostic.Quickfix
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import io.github.rosemoe.sora.lang.styling.line.LineAnchorStyle
import io.github.rosemoe.sora.lang.styling.line.LineBackground
import io.github.rosemoe.sora.lang.styling.line.LineSideIcon
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.style.DiagnosticIndicatorStyle
import java.io.Closeable

/**
 * sora-editor behind [EditorSurface]. With the editor pane, it is the only code that knows sora,
 * so another engine (Aardink, for its licence) replaces this class and the pane, not the workbench.
 */
internal class SoraEditorSurface(
    val editor: NativeCodeEditor,
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
    override val lineCount: Int get() = editor.lineCount
    override fun lineText(line: Int): String = editor.text.getLineString(line)
    override val selectionOffsets: IntRange get() = editor.cursorRange.let { it.startIndex..it.endIndex }

    override fun positionAt(offset: Int): LspPosition =
        editor.text.indexer.getCharPosition(offset.coerceIn(0, editor.text.length)).let { LspPosition(it.line, it.column) }

    override fun setText(value: String) {
        editor.setText(value)
    }

    /** Replaces the edited ranges only (last first), so the cursor and undo history survive. */
    override fun applyEdits(edits: List<LspTextEdit>) {
        val content = editor.text
        content.beginBatchEdit()
        try {
            edits.sortedWith(compareBy<LspTextEdit>({ it.range.start.line }, { it.range.start.character })).asReversed()
                .forEach { edit ->
                    val (start, end) = edit.range
                    content.replace(start.line, start.character, end.line, end.character, edit.newText)
                }
        } finally {
            content.endBatchEdit()
        }
    }

    override fun setSelection(selection: EditorSelection) {
        editor.setSelectionRegion(
            selection.anchor.line,
            selection.anchor.character,
            selection.active.line,
            selection.active.character,
        )
    }

    override fun moveCursor(position: LspPosition, reveal: Boolean) {
        editor.setSelection(position.line, position.character)
        if (reveal) editor.ensureSelectionVisible()
    }

    override fun insertAtCursor(value: String) = editor.insertText(value, value.length)
    override fun deleteSelection() = editor.deleteText()
    override fun outdentSelection(tabSize: Int) = editor.unindentSelection()
    override fun undo() = editor.undo()
    override fun redo() = editor.redo()

    override fun focus() {
        editor.requestFocus()
    }

    override fun setDecorations(decorations: List<EditorDecoration>) {
        styleOverlay.setSemanticDecorations(decorations)
    }

    private var lineMarks = LineMarks()

    override fun setLineMarks(marks: LineMarks) {
        lineMarks = marks
        drawLineMarks()
    }

    /**
     * Draws [lineMarks] as line styles. The analyzer replaces the styles on each publish, so the
     * pane calls this again then. A breakpoint wins over a problem dot on the same line.
     */
    fun drawLineMarks() {
        val styles = editor.styles ?: return
        styles.eraseAllLineStyles()
        val marks = buildList<LineAnchorStyle> {
            lineMarks.problems.forEach { (line, severity) ->
                if (line !in lineMarks.breakpoints) add(LineSideIcon(line, problemIcon(severity)))
            }
            lineMarks.breakpoints.forEach { line -> add(LineSideIcon(line, breakpointIcon)) }
            lineMarks.pausedLine?.let { line -> add(LineBackground(line, ConstColor(0x40FFCC00))) }
        }
        // sora keeps line styles in the order added and looks them up by line, so add them in line order.
        marks.sortedBy { it.line }.forEach(styles::addLineStyle)
        editor.invalidate()
    }

    /** A small dot in the problem's colour (the theme sets PROBLEM_* from the Material scheme). */
    private fun problemIcon(severity: Int): GradientDrawable {
        val colorId = when (severity) {
            1 -> EditorColorScheme.PROBLEM_ERROR
            2 -> EditorColorScheme.PROBLEM_WARNING
            else -> EditorColorScheme.PROBLEM_TYPO
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(editor.colorScheme.getColor(colorId))
            setSize(14, 14)
        }
    }

    /**
     * Wavy underlines, the tap/hover tooltip (message, source and code, plus "Quick fix…", which
     * asks the servers for code actions there) and the faded or struck-through tagged code.
     */
    override fun setProblems(problems: List<EditorProblem>, config: EditorConfig, onQuickFix: (LspPosition) -> Unit) {
        val content = editor.text
        val lineLengths = (0 until content.lineCount).map(content::getColumnCount)
        val container = DiagnosticsContainer()
        if (config.problemUnderlines) {
            problems.forEachIndexed { index, problem ->
                val severity = soraSeverity(problem.severity) ?: return@forEachIndexed
                val start = content.getCharIndex(problem.startLine, problem.startColumn)
                val end = content.getCharIndex(problem.endLine, problem.endColumn)
                val at = LspPosition(problem.startLine, problem.startColumn)
                val detail = DiagnosticDetail(
                    problem.message,
                    problem.detail.takeIf { it.isNotEmpty() },
                    listOf(Quickfix("Quick fix…", 0L) { onQuickFix(at) }),
                    null,
                )
                container.addDiagnostic(DiagnosticRegion(start, end, severity, index.toLong(), detail))
            }
        }
        editor.diagnosticIndicatorStyle = DiagnosticIndicatorStyle.WAVY_LINE
        editor.diagnostics = container
        styleOverlay.setTagDecorations(tagDecorations(problems, lineLengths, config.showUnused, config.showDeprecated))
    }

    /**
     * Runs on every recomposition of the editor (each edit), so it only touches what changed:
     * sora rebuilds its layout on setTextSize/setTabWidth even for the same value, and measures
     * the new one in the background; typing into it before that finished crashed sora
     * (LineBreakLayout.afterInsert, ArrayIndexOutOfBounds).
     */
    override fun applyConfig(config: EditorConfig) {
        val view = editor
        val sizePx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, config.fontSizeSp, view.resources.displayMetrics,
        )
        if (view.textSizePx != sizePx) view.setTextSize(config.fontSizeSp)
        if (view.tabWidth != config.tabSize) view.setTabWidth(config.tabSize)
        view.insertSpaces = config.insertSpaces
        view.autoIndent = config.autoIndent
        view.useTabStops = config.useTabStops
        if (view.isWordwrap != config.wordWrap) view.setWordwrap(config.wordWrap)
        if (view.isLineNumberEnabled != config.lineNumbers) view.setLineNumberEnabled(config.lineNumbers)
    }

    override var inlineSuggestionShown: Boolean
        get() = editor.hasInlineSuggestion
        set(value) {
            editor.hasInlineSuggestion = value
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

    private companion object {
        val breakpointIcon by lazy {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE51400.toInt())
                setSize(24, 24)
            }
        }
    }
}

/** sora's squiggle for a severity; hints get none (they only fade or strike code through). */
fun soraSeverity(severity: Int): Short? = when (severity) {
    1 -> DiagnosticRegion.SEVERITY_ERROR
    2 -> DiagnosticRegion.SEVERITY_WARNING
    3 -> DiagnosticRegion.SEVERITY_TYPO
    else -> null
}
