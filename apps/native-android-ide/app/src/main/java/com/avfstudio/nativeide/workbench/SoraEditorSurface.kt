package com.avfstudio.nativeide.workbench

import android.graphics.drawable.GradientDrawable
import com.avfstudio.nativeide.editor.EditorConfig
import com.avfstudio.nativeide.editor.EditorDecoration
import com.avfstudio.nativeide.editor.EditorEvent
import com.avfstudio.nativeide.editor.EditorProblem
import com.avfstudio.nativeide.editor.EditorSelection
import com.avfstudio.nativeide.editor.EditorSurface
import com.avfstudio.nativeide.editor.FIND_CURRENT_COLOR_ID
import com.avfstudio.nativeide.editor.FIND_MATCH_COLOR_ID
import com.avfstudio.nativeide.editor.LineMarks
import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.editor.SYMBOL_HIGHLIGHT_COLOR_ID
import com.avfstudio.nativeide.editor.SYMBOL_WRITE_HIGHLIGHT_COLOR_ID
import com.avfstudio.nativeide.editor.TagDecoration
import com.avfstudio.nativeide.editor.LspTextEdit
import com.avfstudio.nativeide.editor.SoraBracketColorizer
import com.avfstudio.nativeide.editor.VisibleLines
import com.avfstudio.nativeide.editor.tagDecorations
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorFocusChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.TextSizeChangeEvent
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
        val content = editor.text
        fun clamp(position: LspPosition): Pair<Int, Int> {
            val line = position.line.coerceIn(0, (content.lineCount - 1).coerceAtLeast(0))
            return line to position.character.coerceIn(0, content.getColumnCount(line))
        }
        val (anchorLine, anchorColumn) = clamp(selection.anchor)
        val (activeLine, activeColumn) = clamp(selection.active)
        editor.setSelectionRegion(anchorLine, anchorColumn, activeLine, activeColumn)
    }

    override fun moveCursor(position: LspPosition, reveal: Boolean) {
        // A position from elsewhere (a link, a stale search hit, a file still loading) may lie
        // past the text; sora throws for that, so it goes to the nearest place that exists.
        val content = editor.text
        val line = position.line.coerceIn(0, (content.lineCount - 1).coerceAtLeast(0))
        editor.setSelection(line, position.character.coerceIn(0, content.getColumnCount(line)))
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
        // A file with no grammar gets no styles from its analyzer; the marks need somewhere to live.
        val styles = editor.styles ?: io.github.rosemoe.sora.lang.styling.Styles().also(editor::setStyles)
        styles.eraseAllLineStyles()
        val marks = buildList<LineAnchorStyle> {
            lineMarks.problems.forEach { (line, severity) ->
                if (line !in lineMarks.breakpoints) add(LineSideIcon(line, problemIcon(severity)))
            }
            lineMarks.breakpoints.forEach { line ->
                val icon = when (line) {
                    in lineMarks.logpoints -> logpointIcon
                    in lineMarks.conditionalBreakpoints -> conditionalBreakpointIcon
                    else -> breakpointIcon
                }
                add(LineSideIcon(line, icon))
            }
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
        // Only a changed setting sets the size, so a pinch in progress isn't undone by a recomposition.
        if (config.fontSizeSp != appliedFontSizeSp) {
            appliedFontSizeSp = config.fontSizeSp
            if (view.textSizePx != spToPx(config.fontSizeSp)) view.setTextSize(config.fontSizeSp)
        }
        if (view.isScalable != config.pinchToZoom) view.isScalable = config.pinchToZoom
        view.wheelZoom = config.wheelZoom
        view.pointerRestDelayMs = config.hoverDelayMs.toLong()
        hoverEnabled = config.hover
        if (config.pinchToZoom && !scaleBoundsSet) {
            scaleBoundsSet = true
            view.setScaleTextSizes(spToPx(MIN_FONT_SP), spToPx(MAX_FONT_SP))
        }
        if (view.tabWidth != config.tabSize) view.setTabWidth(config.tabSize)
        view.insertSpaces = config.insertSpaces
        view.autoIndent = config.autoIndent
        view.useTabStops = config.useTabStops
        if (view.isWordwrap != config.wordWrap) view.setWordwrap(config.wordWrap)
        if (view.isLineNumberEnabled != config.lineNumbers) view.setLineNumberEnabled(config.lineNumbers)
        view.setFoldingEnabled(config.folding)
    }

    override fun setFoldingRanges(ranges: List<com.avfstudio.nativeide.editor.FoldRange>) {
        editor.foldRanges = ranges
    }

    override fun runFoldCommand(commandId: String): Boolean = editor.runFoldCommand(commandId)

    private var appliedFontSizeSp = Float.NaN
    private var hoverEnabled = true
    private var scaleBoundsSet = false

    private fun spToPx(sp: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, editor.resources.displayMetrics)

    /** The inverse of [spToPx]; Android 14 scales large fonts non-linearly. */
    private fun pxToSp(px: Float): Float {
        val metrics = editor.resources.displayMetrics
        return if (android.os.Build.VERSION.SDK_INT >= 34) {
            android.util.TypedValue.deriveDimension(android.util.TypedValue.COMPLEX_UNIT_SP, px, metrics)
        } else {
            @Suppress("DEPRECATION")
            px / metrics.scaledDensity
        }
    }

    override var parameterHintsShown: Boolean
        get() = editor.hasParameterHints
        set(value) {
            editor.hasParameterHints = value
        }

    /** One background run per line the range covers (symbols rarely span lines). */
    override fun setSymbolHighlights(highlights: List<Pair<LspRange, Boolean>>) {
        val content = editor.text
        val decorations = highlights.flatMap { (range, write) ->
            val color = if (write) SYMBOL_WRITE_HIGHLIGHT_COLOR_ID else SYMBOL_HIGHLIGHT_COLOR_ID
            (range.start.line..range.end.line).filter { it in 0 until content.lineCount }.map { line ->
                val start = if (line == range.start.line) range.start.character else 0
                val end = if (line == range.end.line) range.end.character else content.getColumnCount(line)
                TagDecoration(line, start, end, background = color)
            }
        }
        styleOverlay.setHighlightDecorations(decorations)
    }

    /** sora's setSelectionRegion asks for focus; an unfocusable editor can't take it from the find box. */
    override fun selectFound(selection: EditorSelection) {
        val focusable = editor.isFocusable
        val touch = editor.isFocusableInTouchMode
        if (!editor.hasFocus()) editor.isFocusable = false
        try {
            setSelection(selection)
        } finally {
            editor.isFocusable = focusable
            editor.isFocusableInTouchMode = touch
        }
        editor.post { editor.getComponent(io.github.rosemoe.sora.widget.component.EditorTextActionWindow::class.java).dismiss() }
    }

    override fun triggerSuggest() {
        (editor.editorLanguage as? SoraCompletionLanguage)?.invokedNext = true
        editor.getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java).requireCompletion()
    }

    override fun setFindHighlights(matches: List<LspRange>, current: Int) {
        val content = editor.text
        val decorations = matches.flatMapIndexed { index, range ->
            val color = if (index == current) FIND_CURRENT_COLOR_ID else FIND_MATCH_COLOR_ID
            (range.start.line..range.end.line).filter { it in 0 until content.lineCount }.map { line ->
                val start = if (line == range.start.line) range.start.character else 0
                val end = if (line == range.end.line) range.end.character else content.getColumnCount(line)
                TagDecoration(line, start, end, background = color)
            }
        }
        styleOverlay.setFindDecorations(decorations)
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
        editor.onScrolled = { listener(EditorEvent.ViewportChanged("", visibleLines)) }
        val focusReceipt = editor.subscribeAlways(EditorFocusChangeEvent::class.java) { event ->
            listener(EditorEvent.FocusChanged("", event.isGainFocus))
        }
        // Our own setTextSize fires this too; only a size the setting didn't ask for is a pinch.
        val zoomReceipt = editor.subscribeAlways(TextSizeChangeEvent::class.java) { event ->
            if (event.newTextSize != spToPx(appliedFontSizeSp)) listener(EditorEvent.Zoomed("", pxToSp(event.newTextSize)))
        }
        editor.onPointerRest = { line, column ->
            if (hoverEnabled || line < 0) listener(EditorEvent.PointerRested("", if (line < 0) null else LspPosition(line, column), left = line == -2))
        }
        return Closeable {
            editor.onPointerRest = null
            editor.onScrolled = null
            contentReceipt.unsubscribe()
            selectionReceipt.unsubscribe()
            scrollReceipt.unsubscribe()
            focusReceipt.unsubscribe()
            zoomReceipt.unsubscribe()
        }
    }

    private companion object {
        /** The bounds settings.json allows for `editor.fontSize`. */
        const val MIN_FONT_SP = 8f
        const val MAX_FONT_SP = 40f
        val breakpointIcon by lazy {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE51400.toInt())
                setSize(24, 24)
            }
        }
        val conditionalBreakpointIcon by lazy { BreakpointMark(conditional = true) }
        val logpointIcon by lazy { BreakpointMark(conditional = false) }
    }
}

/** VS Code's other breakpoint marks: a dot with "=" (a condition or hit count), or a diamond (a logpoint). */
private class BreakpointMark(private val conditional: Boolean) : android.graphics.drawable.Drawable() {
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE51400.toInt() }
    private val bars = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }

    override fun draw(canvas: android.graphics.Canvas) {
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        if (conditional) {
            canvas.drawCircle(centerX, centerY, radius, fill)
            val half = radius * 0.5f
            val thickness = radius * 0.22f
            for (offset in listOf(-radius * 0.28f, radius * 0.28f)) {
                canvas.drawRect(centerX - half, centerY + offset - thickness / 2, centerX + half, centerY + offset + thickness / 2, bars)
            }
        } else {
            val diamond = android.graphics.Path().apply {
                moveTo(centerX, centerY - radius)
                lineTo(centerX + radius, centerY)
                lineTo(centerX, centerY + radius)
                lineTo(centerX - radius, centerY)
                close()
            }
            canvas.drawPath(diamond, fill)
        }
    }

    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        bars.alpha = alpha
    }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fill.colorFilter = colorFilter
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** sora's squiggle for a severity; hints get none (they only fade or strike code through). */
fun soraSeverity(severity: Int): Short? = when (severity) {
    1 -> DiagnosticRegion.SEVERITY_ERROR
    2 -> DiagnosticRegion.SEVERITY_WARNING
    3 -> DiagnosticRegion.SEVERITY_TYPO
    else -> null
}
