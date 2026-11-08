package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.ContentChange
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorMenuItem
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.LocalEditorTheme
import com.avfstudio.nativeide.document.DocumentSession
import com.avfstudio.nativeide.document.isGuestPath
import com.avfstudio.nativeide.editor.EditorEvent
import kotlinx.coroutines.launch

/** The active file in Aardink, made again for each tab as the sora view is: one live editor at a time. */
@Composable
internal fun AardinkEditorView(controller: WorkbenchController, document: DocumentSession) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val syntaxColors = controller.settings.syntaxColors
    val syntax = remember(colors, dark, syntaxColors, controller.editorThemeRevision) { syntaxTheme(context, colors, dark, syntaxColors) }
    val registry = remember(syntax.key) { AardinkSyntax.registry(context, syntax) }
    val rules = remember(document.path, controller.grammarRevision) { AardinkSyntax.rulesFor(context, document) }
    val grammar = remember(registry, document.path, controller.grammarRevision, rules) { AardinkSyntax.tokenizerFor(registry, document, rules) }
    // Made once per file: a new theme or grammar changes its tokenizer, and keeps its text and undo.
    val state = remember { CodeEditorState(document.text, grammar ?: PlainTextTokenizer) }
    val surface = remember(state) { AardinkEditorSurface(state) }
    // What the language servers know, with the file's closing pairs and Enter rules.
    val overtype = surface.overtype
    val service = remember(document.path, rules) {
        AardinkLanguageService(controller, document, rules, tokenKindAt = { offset ->
            val line = state.document.offsetToLineCol(offset.coerceIn(0, state.document.length)).first
            (state.tokensForLine(line).firstOrNull { it.start <= offset && offset < it.end }?.type as? NamedTokenType)?.name
        }, overtype = overtype)
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
        if (!isGuestPath(document.path)) {
            emptyList()
        } else {
            listOf(
                EditorMenuItem("Toggle Breakpoint") { controller.toggleBreakpoint(document.path, state.cursorLine + 1) },
                EditorMenuItem("Edit Breakpoint…") { controller.editBreakpoint(document.path, state.cursorLine + 1) },
            )
        }
    }
    AardinkDecorations(controller, document, surface)
    val theme = remember(syntax, registry, colors, dark) { AardinkSyntax.editorTheme(syntax, registry, colors, dark) }
    CompositionLocalProvider(LocalEditorTheme provides theme) {
        CodeEditorLayout(
            state = state,
            modifier = Modifier.fillMaxSize().focusRequester(surface.focusRequester),
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
                quickSuggestions = controller.settings.quickSuggestions,
                tabSize = config.tabSize,
                insertSpaces = config.insertSpaces,
                fontSize = config.fontSizeSp.sp,
                pinchToZoom = config.pinchToZoom,
                // Problems, find matches, a symbol's uses and breakpoints beside the scrollbar; a tap goes there.
                overviewRuler = true,
            ),
            contextMenuItems = breakpointItems,
            touchMenuItems = breakpointItems,
            onFontSizeChange = if (config.pinchToZoom) {
                { size -> surface.emit(EditorEvent.Zoomed("", size.value)) }
            } else {
                null
            },
        )
    }
    DisposableEffect(surface) {
        val edited: (List<ContentChange>) -> Unit = { changes ->
            if (changes.any { it.isReset }) overtype.clear()
            changes.filter { !it.isReset }.forEach { change ->
                overtype.edited(change.offset, change.deletedLength, change.insertedText)
                // Breakpoints move with the lines they're on, as sora's edits move them.
                if (change.deletedLength > 0) {
                    controller.moveBreakpoints(document.path, insertion = false, change.startLine, change.startColumn, change.oldEndLine)
                }
                if (change.insertedText.isNotEmpty()) {
                    val endLine = change.startLine + change.insertedText.count { it == '\n' }
                    controller.moveBreakpoints(document.path, insertion = true, change.startLine, change.startColumn, endLine)
                }
            }
            if (changes.any { !it.isReset }) {
                document.updateText(state.text)
                controller.onDocumentEdited(document)
            }
        }
        state.addContentListener(edited)
        controller.editorHost.open(document, surface)
        onDispose {
            state.removeContentListener(edited)
            controller.editorHost.close(document.path, surface)
        }
    }
    LaunchedEffect(surface) {
        // As the sora view does when a file opens: typing goes to the file, not to whatever had focus.
        withFrameNanos {}
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
