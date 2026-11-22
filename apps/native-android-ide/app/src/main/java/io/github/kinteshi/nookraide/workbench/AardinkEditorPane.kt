package io.github.kinteshi.nookraide.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.ContentChange
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.InlineCompletion
import com.aardarch.aardink.core.InlineCompletionProvider
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.CursorBlinking
import com.aardarch.aardink.ui.CursorStyle
import com.aardarch.aardink.ui.EditorMenuItem
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.LineNumberStyle
import com.aardarch.aardink.ui.LocalEditorTheme
import com.aardarch.aardink.ui.WrappingIndent
import io.github.kinteshi.nookraide.document.DocumentSession
import io.github.kinteshi.nookraide.document.isGuestPath
import io.github.kinteshi.nookraide.editor.selectionStart
import io.github.kinteshi.nookraide.editor.EditorEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The active file in Aardink, made again for each tab: one live editor at a time. */
@Composable
internal fun AardinkEditorView(controller: WorkbenchController, document: DocumentSession) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val syntaxColors = controller.settings.syntaxColors
    val syntax = remember(colors, dark, syntaxColors, controller.editorThemeRevision) { syntaxTheme(context, colors, dark, syntaxColors) }
    val registry = remember(syntax.key) { AardinkSyntax.registry(context, syntax) }
    val rules = remember(document.path, controller.grammarRevision) { AardinkSyntax.rulesFor(context, document) }
    val grammar = remember(registry, document.path, document.languageId, controller.grammarRevision, rules) { AardinkSyntax.tokenizerFor(registry, document, rules) }
    // Made once per file: a new theme or grammar changes its tokenizer, and keeps its text and undo.
    val state = remember { CodeEditorState(document.text, grammar ?: PlainTextTokenizer) }
    val surface = remember(state) { AardinkEditorSurface(state) }
    // What the language servers know, with the file's closing pairs and Enter rules.
    val overtype = surface.overtype
    // Refresh hints and lenses natively when language servers restart, emit */refresh, or toggles change.
    val settings = controller.settings
    val service = remember(document.path, rules) {
        AardinkLanguageService(controller, document, rules, tokenKindAt = { offset ->
            val line = state.document.offsetToLineCol(offset.coerceIn(0, state.document.length)).first
            tokenKindName(state.tokensForLine(line).firstOrNull { it.start <= offset && offset < it.end }?.type)
        }, overtype = overtype, ownCards = { surface.geometry != null })
    }
    LaunchedEffect(
        state,
        controller.languageFeaturesRevision,
        settings.codeLens,
        settings.interactiveCodeLens,
        settings.inlayHints,
        settings.formatOnType,
    ) {
        state.refreshLanguageFeatures()
    }
    // With a hardware keyboard, our list in place of Aardink's touch strip, which the keys can't choose from (request 8).
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val hardwareKeyboard = configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    val ownList = settings.keyboardSuggestionList && hardwareKeyboard
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val keyboardSuggestions = remember(state) { KeyboardSuggestions(state, scope) }
    SideEffect {
        keyboardSuggestions.service = service
        keyboardSuggestions.triggerCharacters = service.serverTriggerCharacters
        keyboardSuggestions.whileTyping = settings.quickSuggestions
        service.ownList = ownList
        surface.suggestions = if (ownList) keyboardSuggestions else null
        if (!ownList) keyboardSuggestions.dismiss()
    }
    SideEffect {
        surface.rules = rules
        surface.tokenKindAt = service.tokenKindAt
        surface.tokenizer = grammar
        state.tokenizer = grammar ?: PlainTextTokenizer
        surface.highlightColors = HighlightColors(
            symbol = colors.primary.copy(alpha = 0.18f),
            symbolWrite = colors.tertiary.copy(alpha = 0.26f),
            findMatch = colors.tertiary.copy(alpha = 0.28f),
            findCurrent = colors.tertiary.copy(alpha = 0.6f),
        )
    }
    surface.applyConfig(controller.editorConfig(document))
    val config = surface.config
    // Toggle and edit the cursor line's breakpoint; the gutter can't be tapped for one yet (request 2).
    val breakpointItems = remember(document.path) {
        // Share the selection (G26) through Android's share sheet; the selection's own text, read when chosen.
        val share = EditorMenuItem("Share Selection") {
            val range = state.selection
            controller.shareText(state.text.substring(minOf(range.start, range.end), maxOf(range.start, range.end)))
        }
        if (!isGuestPath(document.path)) {
            listOf(share)
        } else {
            listOf(
                share,
                EditorMenuItem("Toggle Breakpoint") { controller.toggleBreakpoint(document.path, state.cursorLine + 1) },
                EditorMenuItem("Edit Breakpoint…") { controller.editBreakpoint(document.path, state.cursorLine + 1) },
            )
        }
    }
    AardinkDecorations(controller, document, surface)
    // Copilot's ghost text, asked for once typing pauses: Tab accepts, Alt+] and Alt+[ go through it, Esc dismisses.
    val copilot = remember(document.path, controller.copilotEnabled) {
        if (!controller.copilotEnabled) null else InlineCompletionProvider { snapshot, offset -> copilotCompletions(controller, document, state, snapshot, offset) }
    }
    val theme = remember(syntax, registry, colors, dark) { AardinkSyntax.editorTheme(syntax, registry, colors, dark) }
    // Code in hover, signature help and a suggestion's details, coloured by its fence's language.
    val fenceTokenizers = remember(registry) { AardinkSyntax.fenceTokenizerProvider(registry) }
    // The mouse resting on a word: what Aardink's hover tracking saw is taken (so it shows no card of its own) and the
    // workbench's card shows there. After the editor's own pointer handling, which runs first in this pass.
    val pointerScope = androidx.compose.runtime.rememberCoroutineScope()
    val hoverWatch = Modifier.pointerInput(surface) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                val geometry = surface.geometry ?: continue
                when {
                    event.type == androidx.compose.ui.input.pointer.PointerEventType.Exit -> surface.pointerLeft()
                    event.changes.firstOrNull()?.type != androidx.compose.ui.input.pointer.PointerType.Mouse -> Unit
                    else -> surface.pointerAt(geometry.takeHover(), pointerScope)
                }
            }
        }
    }
    CompositionLocalProvider(LocalEditorTheme provides theme, com.aardarch.aardink.ui.LocalCodeFenceTokenizers provides fenceTokenizers) {
        CodeEditorLayout(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(surface.focusRequester)
                .then(hoverWatch)
                .semantics {
                    contentDescription = codeEditorDescription(
                        path = document.path,
                        language = controller.lspLanguageId(document.path) ?: document.languageId.ifEmpty { "plain text" },
                        lineCount = state.document.lineCount,
                        cursorLine = (controller.cursorPosition?.line ?: state.cursorLine) + 1,
                        cursorCol = (controller.cursorPosition?.character ?: state.cursorColumn) + 1,
                        problemCount = surface.problems.size,
                    )
                },
            languageService = service,
            foldState = surface.foldState,
            foldingProvider = surface.foldingProvider,
            // The workbench's problems: handed over, so the editor doesn't ask the service for its own.
            diagnostics = surface.problems,
            options = EditorOptions(
                softWrap = config.wordWrap,
                showLineNumbers = config.lineNumbers,
                showFoldMarkers = config.folding,
                showDiffMarkers = false,
                quickSuggestions = settings.quickSuggestions && !ownList,
                tabSize = config.tabSize,
                insertSpaces = config.insertSpaces,
                fontSize = config.fontSizeSp.sp,
                // Rows as tall as the font, no more, as the editor's were before Aardink (its default is about a fifth taller).
                lineHeight = (config.fontSizeSp * MONOSPACE_ROW_RATIO).sp,
                pinchToZoom = config.pinchToZoom,
                inlayHints = settings.inlayHints,
                // Tags renamed in pairs (`editor.linkedEditing`); the service answers for markup files only.
                linkedEditing = settings.linkedEditing,
                // The service decides: cells and conflicts always, the server's with the Code Lens setting.
                codeLens = true,
                // Problems, find matches, a symbol's uses and breakpoints beside the scrollbar; a tap goes there.
                overviewRuler = true,
                formatOnType = settings.formatOnType,
                lineNumbers = editorLineNumbers(settings.lineNumbersStyle),
                cursorStyle = editorCursorStyle(settings.cursorStyle),
                cursorBlinking = editorCursorBlinking(settings.cursorBlinking),
                indentGuides = settings.indentGuides,
                bracketPairGuides = settings.bracketPairGuides,
                scrollBeyondLastLine = settings.scrollBeyondLastLine,
                smoothScrolling = settings.smoothScrolling && settings.animations,
                wrappingIndent = editorWrappingIndent(settings.wrappingIndent),
            ),
            inlineCompletionProvider = copilot,
            contextMenuItems = breakpointItems,
            touchMenuItems = breakpointItems,
            onSearchWorkspace = { query, options ->
                controller.searchCaseSensitive = options.caseSensitive
                controller.searchWholeWord = options.wholeWord
                controller.searchRegex = options.useRegex
                if (query.isNotEmpty()) {
                    controller.searchText = query
                    controller.scheduleSearch(0)
                }
                controller.showSearch()
            },
            onFontSizeChange = if (config.pinchToZoom) {
                { size -> surface.emit(EditorEvent.Zoomed("", size.value)) }
            } else {
                null
            },
        )
    }
    DisposableEffect(surface) {
        val edited: (List<ContentChange>) -> Unit = { changes ->
            if (changes.any { it.isReset }) {
                overtype.clear()
                keyboardSuggestions.dismiss()
            }
            surface.suggestions?.let { list -> changes.filter { !it.isReset }.forEach { list.edited(it.offset, it.deletedLength, it.insertedText) } }
            changes.filter { !it.isReset }.forEach { change ->
                overtype.edited(change.offset, change.deletedLength, change.insertedText)
                // Breakpoints move with the lines they're on.
                if (change.deletedLength > 0) {
                    controller.moveBreakpoints(document.path, insertion = false, change.startLine, change.startColumn, change.oldEndLine)
                }
                if (change.insertedText.isNotEmpty()) {
                    val endLine = change.startLine + change.insertedText.count { it == '\n' }
                    controller.moveBreakpoints(document.path, insertion = true, change.startLine, change.startColumn, endLine)
                }
            }
            if (changes.any { !it.isReset }) {
                val wasDirty = document.dirty
                document.updateText(surface.text)
                controller.onDocumentEdited(document, dirtyChanged = wasDirty != document.dirty)
            }
        }
        state.addContentListener(edited)
        val moved: (List<androidx.compose.ui.text.TextRange>, com.aardarch.aardink.core.SelectionChangeReason) -> Unit = { _, reason ->
            if (reason != com.aardarch.aardink.core.SelectionChangeReason.Edit) surface.suggestions?.selectionMoved()
        }
        state.addSelectionListener(moved)
        controller.editorHost.open(document, surface)
        onDispose {
            state.removeContentListener(edited)
            state.removeSelectionListener(moved)
            controller.editorHost.close(document.path, surface)
        }
    }
    LaunchedEffect(surface) {
        // When a file opens, typing goes to the file, not to whatever had focus.
        withFrameNanos {}
        // Laid out now: where its text is on screen, if Aardink's internals let us see (A19).
        surface.geometry = AardinkGeometry.of(state)
        surface.focus()
        launch { snapshotFlow { state.viewport }.collect { surface.emit(EditorEvent.ViewportChanged("", surface.visibleLines)) } }
        launch { snapshotFlow { state.isFocused }.collect { surface.emit(EditorEvent.FocusChanged("", it)) } }
    }
    // A reload or a server's edit. What's typed reaches the session first, so it never comes back through here.
    LaunchedEffect(document.text) {
        if (state.text != document.text) surface.setText(document.text)
    }
    SideEffect { controller.editorReady(surface) }
    LaunchedEffect(document.path) { controller.onActiveDocumentChanged() }
}

private fun tokenKindName(type: TokenType?): String? = when (type) {
    TokenType.Comment -> "comment"
    TokenType.StringLiteral -> "string"
    is NamedTokenType -> type.name
    else -> null
}

/**
 * What the workbench draws on the file's lines in Aardink, as decorations that follow the text: breakpoints
 * and the paused line, merge conflicts' sides, Python cells' markers, find in selection's scope.
 */
@Composable
private fun AardinkDecorations(controller: WorkbenchController, document: DocumentSession, surface: AardinkEditorSurface) {
    val colors = MaterialTheme.colorScheme
    val breakpoints = controller.breakpoints[document.path]
    val options = controller.breakpointOptions[document.path]
    val paused = controller.executionPoint
    LaunchedEffect(surface, breakpoints, options, paused) { controller.refreshLineMarks(document.path) }
    val conflicts = controller.activeConflicts()
    LaunchedEffect(surface, conflicts) { surface.setLineTints("conflicts", conflictTints(conflicts)) }
    val cells = controller.activePythonCells()
    val cellColor = colors.outlineVariant.copy(alpha = 0.35f)
    LaunchedEffect(surface, cells, cellColor) { surface.setLineTints("cells", cellTints(cells, cellColor)) }
    // While paused, the frame's values beside its lines; and git blame for the cursor's line while the file is as saved.
    val values = controller.debugInlineValues?.takeIf { it.first == document.path }?.second.orEmpty()
    val valueColor = colors.tertiary
    LaunchedEffect(surface, values, valueColor) { surface.setLineEndNotes("inline-values", values, valueColor) }
    controller.editRevision
    val blameLine = controller.cursorPosition?.line?.takeIf { controller.settings.gitBlame && !document.dirty }
    val blame = blameLine?.let { controller.blameLines[it] }?.let { line ->
        mapOf(blameLine to if (line.uncommitted) "You, not committed yet" else "${line.author}, ${relativeTime(line.time)} • ${line.summary}")
    }.orEmpty()
    val blameColor = colors.onSurfaceVariant.copy(alpha = 0.6f)
    LaunchedEffect(surface, blame, blameColor) { surface.setLineEndNotes("blame", blame, blameColor) }
    // Copilot's next edit, in the text; the card is AardinkNextEditCard. Tab goes to it, or accepts it there.
    val nextEdit = controller.shownNextEdit()
    LaunchedEffect(surface, nextEdit) { surface.setNextEdit(nextEdit) }
    SideEffect { surface.inlineSuggestionShown = controller.nextEdit != null }
    val scope = controller.findScope?.takeIf { controller.findVisible && controller.activeNotebook == null }
    val scopeColor = colors.primary.copy(alpha = 0.12f)
    LaunchedEffect(surface, scope, scopeColor) { surface.setFindScope(scope, scopeColor) }
}

/** A large file's page, read-only and plain, in Aardink: only the lines on screen are laid out. */
@Composable
internal fun AardinkReadOnlyView(text: String, syntaxColors: SyntaxColors, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val syntax = remember(colors, dark, syntaxColors) { syntaxTheme(context, colors, dark, syntaxColors) }
    val registry = remember(syntax.key) { AardinkSyntax.registry(context, syntax) }
    val theme = remember(syntax, registry, colors, dark) { AardinkSyntax.editorTheme(syntax, registry, colors, dark) }
    val state = remember { CodeEditorState(text, PlainTextTokenizer) }
    LaunchedEffect(text) { if (state.text != text) state.loadText(text) }
    CompositionLocalProvider(LocalEditorTheme provides theme) {
        CodeEditorLayout(
            state = state,
            modifier = modifier,
            options = EditorOptions(readOnly = true, showDiffMarkers = false, showFoldMarkers = false, fontSize = 13.sp),
        )
    }
}

/**
 * Copilot's suggestions for Aardink's ghost text at [offset] of [snapshot]: none if the file has moved on since, or
 * Copilot is off. Asked on Aardink's compute thread; the request goes out from the main thread, as the workbench's do.
 */
private suspend fun copilotCompletions(
    controller: WorkbenchController,
    document: DocumentSession,
    state: CodeEditorState,
    snapshot: CodeDocument,
    offset: Int,
): List<InlineCompletion> {
    val (line, column) = snapshot.offsetToLineCol(offset)
    val request = withContext(Dispatchers.Main) {
        if (state.text != snapshot.text || document.text != snapshot.text) null
        else controller.copilotInlineCompletions(document, io.github.kinteshi.nookraide.editor.LspPosition(line, column))
    } ?: return emptyList()
    val suggestions = runCatching { withTimeoutOrNull(COPILOT_TIMEOUT_MS) { request.await() } }.getOrNull().orEmpty()
    return suggestions.map { suggestion ->
        val start = offsetIn(snapshot, suggestion.range.start)
        val end = offsetIn(snapshot, suggestion.range.end).coerceAtLeast(start)
        InlineCompletion(suggestion.insertText, start until end, isSnippet = suggestion.isSnippet)
    }
}

private const val COPILOT_TIMEOUT_MS = 10_000L

/**
 * Copilot's next edit as a card docked at the editor's edge, the lines as they'd read afterwards: at the bottom unless the
 * edit is in the lower half of the view, and pointing up or down when it's off screen. Aardink can't say where a line is
 * (request 1), so the card isn't under the edit; the edit itself is marked in the text.
 */
@Composable
internal fun AardinkNextEditCard(controller: WorkbenchController) {
    val surface = controller.editorSurface as? AardinkEditorSurface ?: return
    controller.cursorPosition
    val preview = controller.shownNextEdit() ?: return
    val visible = surface.state.viewport.let { it.firstVisibleLine..it.lastVisibleLine }
    val onScreen = preview.lastLine >= visible.first && preview.firstLine <= visible.last
    val below = preview.firstLine > visible.last
    val dockTop = if (onScreen) (preview.firstLine + preview.lastLine) / 2 - visible.first > (visible.last - visible.first) / 2 else !below
    val cursorLine = surface.selectionStart().line
    val range = io.github.kinteshi.nookraide.editor.LspRange(
        io.github.kinteshi.nookraide.editor.LspPosition(preview.firstLine, 0),
        io.github.kinteshi.nookraide.editor.LspPosition(preview.lastLine, 0),
    )
    val atCursor = nextEditTab(cursorLine, range) == NextEditTab.Accept
    val colors = MaterialTheme.colorScheme
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append(preview.newText)
        if (!preview.newChange.isEmpty()) {
            addStyle(androidx.compose.ui.text.SpanStyle(background = NEXT_EDIT_ADDED.copy(alpha = 0.3f)), preview.newChange.first, preview.newChange.last + 1)
        }
    }
    // Under the edit's last line when Aardink's geometry can be reached and the edit is on screen.
    val under = if (onScreen) surface.rectOnScreen(io.github.kinteshi.nookraide.editor.LspPosition(preview.lastLine, 0)) else null
    val card: @Composable (Modifier) -> Unit = { placement ->
        androidx.compose.material3.Surface(
            color = colors.surfaceContainerHigh,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            shadowElevation = 4.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, NEXT_EDIT_ADDED.copy(alpha = 0.6f)),
            modifier = placement.widthIn(max = 720.dp),
        ) {
            androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    val where = when {
                        onScreen -> ""
                        below -> "↓ "
                        else -> "↑ "
                    }
                    androidx.compose.material3.Text(
                        "${where}Copilot's next edit · line ${preview.firstLine + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    androidx.compose.material3.Text(
                        if (atCursor) "Tab accept" else "Tab go to it",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        modifier = Modifier.clickable {
                            controller.runCommand(if (atCursor) "editor.action.inlineSuggest.acceptNextEdit" else "editor.action.inlineSuggest.commit")
                        },
                    )
                    androidx.compose.material3.Text(
                        "Esc dismiss",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.clickable { controller.runCommand("editor.action.inlineSuggest.hide") },
                    )
                }
                androidx.compose.material3.Text(
                    text,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = surface.config.fontSizeSp.sp,
                    color = colors.onSurface,
                    softWrap = false,
                    maxLines = 12,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    if (under != null) {
        AtText(under) { card(Modifier) }
        return
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        card(
            Modifier
                .align(if (dockTop) androidx.compose.ui.Alignment.TopCenter else androidx.compose.ui.Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** A row of monospace text with no line spacing, as a multiple of the font size: the font's ascent and descent, no spacing. */
internal val MONOSPACE_ROW_RATIO: Float by lazy {
    val metrics = android.graphics.Paint().apply {
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = 100f
    }.fontMetrics
    ((metrics.descent - metrics.ascent) / 100f).takeIf { it in 1f..2f } ?: 1.2f
}

internal fun editorCursorStyle(key: String): CursorStyle = when (key) {
    "block" -> CursorStyle.Block
    "underline" -> CursorStyle.Underline
    "line-thin" -> CursorStyle.LineThin
    "block-outline" -> CursorStyle.BlockOutline
    "underline-thin" -> CursorStyle.UnderlineThin
    else -> CursorStyle.Line
}

internal fun editorCursorBlinking(key: String): CursorBlinking = when (key) {
    "blink" -> CursorBlinking.Blink
    "solid" -> CursorBlinking.Solid
    else -> CursorBlinking.Smooth
}

private fun editorLineNumbers(style: String): LineNumberStyle = when (style) {
    "relative" -> LineNumberStyle.Relative
    "interval" -> LineNumberStyle.Interval
    else -> LineNumberStyle.Absolute
}

private fun editorWrappingIndent(key: String): WrappingIndent = when (key) {
    "none" -> WrappingIndent.None
    "indent" -> WrappingIndent.Indent
    "deepIndent" -> WrappingIndent.DeepIndent
    else -> WrappingIndent.Same
}
