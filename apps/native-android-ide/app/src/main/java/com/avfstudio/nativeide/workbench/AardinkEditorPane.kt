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
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.ContentChange
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.rememberCodeEditorState
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.LocalEditorTheme
import com.aardarch.aardink.ui.fromColorScheme
import com.avfstudio.nativeide.document.DocumentSession
import com.avfstudio.nativeide.editor.EditorEvent
import kotlinx.coroutines.launch

/** The active file in Aardink, made again for each tab as the sora view is: one live editor at a time. */
@Composable
internal fun AardinkEditorView(controller: WorkbenchController, document: DocumentSession) {
    val state = rememberCodeEditorState(initialText = document.text)
    val surface = remember(state) { AardinkEditorSurface(state) }
    surface.applyConfig(controller.editorConfig(document))
    val config = surface.config
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val theme = remember(colors, dark) { EditorTheme.fromColorScheme(colors, dark) }
    CompositionLocalProvider(LocalEditorTheme provides theme) {
        CodeEditorLayout(
            state = state,
            modifier = Modifier.fillMaxSize().focusRequester(surface.focusRequester),
            options = EditorOptions(
                softWrap = config.wordWrap,
                showLineNumbers = config.lineNumbers,
                showFoldMarkers = false,
                showDiagnosticAnnotations = false,
                showDiffMarkers = false,
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
