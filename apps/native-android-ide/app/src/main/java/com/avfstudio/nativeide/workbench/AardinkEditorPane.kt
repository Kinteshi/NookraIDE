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
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.LocalEditorTheme
import com.avfstudio.nativeide.document.DocumentSession
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
            ),
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
            changes.filter { !it.isReset }.forEach { overtype.edited(it.offset, it.deletedLength, it.insertedText) }
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
