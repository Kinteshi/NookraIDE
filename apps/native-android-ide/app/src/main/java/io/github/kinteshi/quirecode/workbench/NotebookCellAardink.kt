package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.ContentChange
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.InlineCompletion
import com.aardarch.aardink.core.InlineCompletionProvider
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.core.SelectionChangeReason
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.LocalEditorTheme
import io.github.kinteshi.quirecode.notebook.CellType
import io.github.kinteshi.quirecode.notebook.NotebookCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Space around a cell's editor besides Aardink's own above and below its rows: together about the text field's 12 dp. */
private val CELL_TEXT_PADDING = 8.dp

private const val CELL_COPILOT_TIMEOUT_MS = 10_000L

/**
 * A notebook cell edited in Aardink (A20, `avf.notebook.cellEditor` "aardink"): the code editor's gutter (line numbers,
 * G21), folding, bracket colours and several cursors in a cell, with what the text field cells had: the kernel's
 * completions (Ctrl+Space, Tab after a name), Copilot's ghost text, the language server's problems, Up and Down into
 * the next cell, go to a problem or a definition, find. Aardink has no height-of-its-text mode, so the cell is given
 * its rows' height exactly and its own scrolling has nowhere to go; the notebook's list scrolls.
 */
@Composable
internal fun AardinkCellEditor(controller: WorkbenchController, session: NotebookSession, cell: NotebookCell, selected: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val settings = controller.settings
    val syntax = remember(colors, dark, settings.syntaxColors, controller.editorThemeRevision) { syntaxTheme(context, colors, dark, settings.syntaxColors) }
    val registry = remember(syntax.key) { AardinkSyntax.registry(context, syntax) }
    val language = when (cell.type) {
        CellType.Code -> "python"
        CellType.Markdown -> "markdown"
        else -> null
    }
    val tokenizer = remember(registry, language) { language?.let { AardinkSyntax.codeFenceTokenizer(registry, it) } }
    val state = remember(cell.id) { session.cellEditors.getOrPut(cell.id) { CodeEditorState(cell.source, tokenizer ?: PlainTextTokenizer) } }
    SideEffect { state.tokenizer = tokenizer ?: PlainTextTokenizer }
    val cellColor = if (cell.type == CellType.Code) colors.surfaceContainerLow else colors.surface
    val theme = remember(syntax, registry, colors, dark, cellColor) {
        AardinkSyntax.editorTheme(syntax, registry, colors, dark).copy(background = cellColor, gutterBackground = cellColor)
    }
    var lines by remember(cell.id) { mutableIntStateOf(state.document.lineCount.coerceAtLeast(1)) }
    var focused by remember(cell.id) { mutableStateOf(false) }
    var box by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val focus = remember { FocusRequester() }

    /** The cell as the text field cells saw it, for the controller's cell helpers. */
    fun value(): TextFieldValue = TextFieldValue(state.text, state.selection)

    /** A helper's whole new cell text, as one edit Aardink can undo, with its cursor. */
    fun apply(next: TextFieldValue) {
        val old = state.text
        val new = next.text
        if (old != new) {
            var start = 0
            while (start < old.length && start < new.length && old[start] == new[start]) start++
            var end = 0
            while (end < old.length - start && end < new.length - start && old[old.length - 1 - end] == new[new.length - 1 - end]) end++
            state.applyTextEdits(listOf(TextEdit(start until old.length - end, new.substring(start, new.length - end))))
        }
        state.selection = next.selection
    }

    // What's typed goes to the notebook, and on to the language server's copy; a change made elsewhere (a reload, a
    // type toggle) comes back here.
    DisposableEffect(state) {
        val edited: (List<ContentChange>) -> Unit = { changes ->
            lines = state.document.lineCount.coerceAtLeast(1)
            val typed = changes.any { !it.isReset } && state.text != cell.source
            if (typed) {
                cell.source = state.text
                controller.onCellEdited(session)
            }
            controller.onCellInput(session, cell, value(), typed, scheduleCopilot = false)
        }
        val moved: (List<TextRange>, SelectionChangeReason) -> Unit = { _, reason ->
            if (reason != SelectionChangeReason.Edit) controller.onCellInput(session, cell, value(), false, scheduleCopilot = false)
        }
        state.addContentListener(edited)
        state.addSelectionListener(moved)
        onDispose {
            state.removeContentListener(edited)
            state.removeSelectionListener(moved)
        }
    }
    LaunchedEffect(cell.source) {
        if (state.text != cell.source) state.replaceText(cell.source)
    }
    // Selecting a cell (Shift+Enter, + Code, a tap, Up or Down from the next cell) puts the keyboard in it.
    LaunchedEffect(selected, session.focusRequest) {
        if (!selected) return@LaunchedEffect
        if (session.keepFocusOnSelect) {
            session.keepFocusOnSelect = false
            return@LaunchedEffect
        }
        session.caretAtEnd?.let { atEnd ->
            // On the first row coming from above, the last from below, in the column the cursor had.
            val text = state.text
            val column = session.caretColumn ?: 0
            val lineStart = if (atEnd) text.lastIndexOf('\n') + 1 else 0
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            state.selection = TextRange((lineStart + column).coerceAtMost(lineEnd))
            session.caretAtEnd = null
            session.caretGoalX = null
            session.caretColumn = null
        }
        repeat(10) {
            if (focused) return@LaunchedEffect
            runCatching { focus.requestFocus() }
            withFrameNanos { }
        }
    }
    // Go to definition or a problem's row: the caret lands there.
    LaunchedEffect(session.cellCaretRequest) {
        val (cellId, offset) = session.cellCaretRequest ?: return@LaunchedEffect
        if (cellId != cell.id) return@LaunchedEffect
        state.selection = TextRange(offset.coerceIn(0, state.text.length))
        session.cellCaret = offset.coerceIn(0, state.text.length)
        session.cellCaretRequest = null
    }
    // Find's current match in this cell becomes its selection.
    LaunchedEffect(session.findReveal) {
        val (cellId, range) = session.findCurrentMatch ?: return@LaunchedEffect
        if (cellId == cell.id && range.last < state.text.length) state.selection = TextRange(range.first, range.last + 1)
    }

    val fontSize = controller.shownFontSize
    val rowHeight = fontSize * MONOSPACE_ROW_RATIO
    // Aardink's rows and the space it keeps above them, measured from the first cell with two lines; until then an
    // estimate with a row to spare, so nothing is cut off.
    val metrics = session.cellRowMetrics?.takeIf { it.fontSize == fontSize }
    LaunchedEffect(state, metrics == null, lines >= 2) {
        if (metrics != null || state.document.lineCount < 2) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        val geometry = AardinkGeometry.of(state) ?: return@LaunchedEffect
        val bounds = geometry.boundsInRoot() ?: return@LaunchedEffect
        val first = geometry.caretAt(0) ?: return@LaunchedEffect
        val second = geometry.caretAt(state.document.lineStart(1)) ?: return@LaunchedEffect
        val pitch = second.top - first.top
        if (pitch > 0f) session.cellRowMetrics = CellRowMetrics(fontSize, pitch, (first.top - bounds.top).coerceAtLeast(0f))
    }
    val density = LocalDensity.current
    val height = with(density) {
        if (metrics != null) (metrics.top * 2 + metrics.pitch * lines).toDp() else rowHeight.sp.toDp() * (lines + 1)
    } + CELL_TEXT_PADDING
    val lineNumbers = settings.notebookLineNumbers != (cell.id in session.cellLineNumbersToggled)
    val problems = session.cellProblems[cell.id].orEmpty()
    val diagnostics = remember(problems, settings.problemUnderlines) {
        if (!settings.problemUnderlines) emptyList() else problems.map { problem ->
            Diagnostic(
                range = problem.start until problem.end.coerceAtLeast(problem.start + 1),
                message = problem.message,
                severity = when (problem.severity) {
                    2 -> DiagnosticSeverity.Warning
                    3 -> DiagnosticSeverity.Info
                    4 -> DiagnosticSeverity.Hint
                    else -> DiagnosticSeverity.Error
                },
                source = problem.origin.ifBlank { null },
            )
        }
    }
    // Copilot's ghost text in code cells, from the notebook's copy at the language server (the cell's offsets mapped back).
    val copilot = remember(cell.id, cell.type, controller.copilotEnabled, settings.notebookCopilot) {
        if (cell.type != CellType.Code || !controller.copilotEnabled || !settings.notebookCopilot) null else InlineCompletionProvider { snapshot, offset ->
            val request = withContext(Dispatchers.Main) {
                if (state.text != snapshot.text || cell.source != snapshot.text) null else controller.cellInlineCompletions(session, cell, offset)
            } ?: return@InlineCompletionProvider emptyList()
            runCatching { withTimeoutOrNull(CELL_COPILOT_TIMEOUT_MS) { request.await() } }.getOrNull().orEmpty()
                .map { InlineCompletion(it.text, it.start until it.end.coerceAtLeast(it.start)) }
        }
    }

    Box(Modifier.fillMaxWidth().onGloballyPositioned { box = it }) {
        CompositionLocalProvider(LocalEditorTheme provides theme) {
            CodeEditorLayout(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .padding(vertical = CELL_TEXT_PADDING / 2)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // The kernel's completions take Up, Down, Enter, Tab and Esc while they're open.
                        val completion = session.cellCompletion?.takeIf { it.cellId == cell.id }
                        val visible = completion?.visibleItems(value())
                        if (completion != null && visible != null && !event.isCtrlPressed && !event.isAltPressed) {
                            when (event.key) {
                                Key.DirectionDown -> {
                                    session.cellCompletion = completion.copy(selected = (completion.selected.coerceIn(visible.indices) + 1) % visible.size)
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionUp -> {
                                    session.cellCompletion = completion.copy(selected = (completion.selected.coerceIn(visible.indices) - 1 + visible.size) % visible.size)
                                    return@onPreviewKeyEvent true
                                }
                                Key.Enter, Key.NumPadEnter, Key.Tab -> {
                                    completion.accept(value())?.let(::apply)
                                    session.cellCompletion = null
                                    return@onPreviewKeyEvent true
                                }
                                Key.Escape -> {
                                    session.cellCompletion = null
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                        if (event.key == Key.Spacebar && event.isCtrlPressed && cell.type == CellType.Code) {
                            controller.requestCellCompletion(session, cell, value())
                            return@onPreviewKeyEvent true
                        }
                        if (event.isCtrlPressed || event.isAltPressed || event.isShiftPressed) return@onPreviewKeyEvent false
                        // Jupyter: Tab after a name or a dot completes (with a kernel running); elsewhere Aardink indents.
                        if (event.key == Key.Tab && cell.type == CellType.Code && settings.notebookTabCompletion &&
                            session.kernelName != null && !state.hasSelection && tabCompletes(value())
                        ) {
                            controller.requestCellCompletion(session, cell, value())
                            return@onPreviewKeyEvent true
                        }
                        // Past the first or last line, Up and Down go to the neighbouring cell (Jupyter), in the same column.
                        when (event.key) {
                            Key.DirectionUp -> !state.hasSelection && state.cursorLine == 0 && run {
                                session.caretColumn = state.cursorColumn
                                controller.selectAdjacentCell(session, cell, -1)
                            }
                            Key.DirectionDown -> !state.hasSelection && state.cursorLine >= state.document.lineCount - 1 && run {
                                session.caretColumn = state.cursorColumn
                                controller.selectAdjacentCell(session, cell, 1)
                            }
                            else -> false
                        }
                    }
                    .onFocusChanged { change ->
                        focused = change.isFocused || change.hasFocus
                        if (focused) {
                            session.selectedCell = cell.id
                            controller.focusArea = FocusArea.Notebook
                        } else if (controller.focusArea == FocusArea.Notebook && session.selectedCell == cell.id) {
                            controller.focusArea = FocusArea.Other
                        }
                    },
                diagnostics = diagnostics,
                inlineCompletionProvider = copilot,
                // Its undo, redo and arrows would sit over a short cell; the app's key bar is above the keyboard anyway.
                keyboardToolbarPlacement = com.aardarch.aardink.ui.KeyboardToolbarPlacement.Hidden,
                options = EditorOptions(
                    softWrap = false,
                    showGutter = lineNumbers,
                    showLineNumbers = lineNumbers,
                    showFoldMarkers = lineNumbers,
                    showDiffMarkers = false,
                    highlightCurrentLine = selected,
                    stickyScroll = false,
                    showMinimap = false,
                    breadcrumbs = false,
                    overviewRuler = false,
                    quickSuggestions = false,
                    codeLens = false,
                    tabSize = settings.tabSize,
                    insertSpaces = settings.insertSpaces,
                    fontSize = fontSize.sp,
                    lineHeight = rowHeight.sp,
                    pinchToZoom = false,
                ),
            )
        }
        // A cell that isn't selected takes a tap (selecting it, with the cursor where it was tapped) but not a drag:
        // the drag scrolls the notebook instead of putting a cursor, and the keyboard, in every cell it starts on.
        if (!selected) {
            Box(
                Modifier.matchParentSize().pointerInput(cell.id) {
                    detectTapGestures { tap ->
                        val root = box?.takeIf { it.isAttached }?.localToRoot(tap)
                        val geometry = AardinkGeometry.of(state)
                        // Past the end of a line Aardink finds no character: the end of the line under the tap.
                        val offset = root?.let { geometry?.offsetAt(it) } ?: run {
                            val y = root?.y ?: return@run null
                            val top = geometry?.boundsInRoot()?.top ?: return@run null
                            val rows = session.cellRowMetrics ?: return@run null
                            val line = ((y - top - rows.top) / rows.pitch).toInt().coerceIn(0, state.document.lineCount - 1)
                            val next = if (line + 1 < state.document.lineCount) state.document.lineStart(line + 1) - 1 else state.text.length
                            next
                        }
                        session.selectedCell = cell.id
                        offset?.let { state.selection = TextRange(it.coerceIn(0, state.text.length)) }
                        session.focusRequest++
                    }
                },
            )
        }
        val completion = session.cellCompletion?.takeIf { it.cellId == cell.id }
        val items = completion?.visibleItems(value())
        if (completion != null && items != null) {
            // Under the cell, at the caret's column; it never takes focus, so typing goes on in the cell.
            val caretX = box?.takeIf { it.isAttached }?.let { coordinates ->
                AardinkGeometry.of(state)?.caretAt(state.selection.start)?.left?.minus(coordinates.localToRoot(androidx.compose.ui.geometry.Offset.Zero).x)
            } ?: 0f
            DropdownMenu(
                expanded = true,
                onDismissRequest = { session.cellCompletion = null },
                offset = DpOffset(with(density) { caretX.toDp() }, 0.dp),
                properties = PopupProperties(focusable = false),
                modifier = Modifier.heightIn(max = 280.dp),
            ) {
                items.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = { Text(item, fontFamily = FontFamily.Monospace, maxLines = 1) },
                        onClick = {
                            completion.accept(value(), index)?.let(::apply)
                            session.cellCompletion = null
                            runCatching { focus.requestFocus() }
                        },
                        modifier = if (index == completion.selected.coerceIn(items.indices)) {
                            Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}
