package com.avfstudio.nativeide.workbench

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.avfstudio.nativeide.PythonTextMate
import com.avfstudio.nativeide.assets.AssetPackGrammarLoader
import com.avfstudio.nativeide.document.DocumentSession
import com.avfstudio.nativeide.editor.AutoPairReplacement
import com.avfstudio.nativeide.editor.SoraBracketColorizer
import com.avfstudio.nativeide.editor.autoPairAfterInsertion
import com.avfstudio.nativeide.editor.pairedCloserOffsetAfterDeletion
import com.avfstudio.nativeide.editor.skipExistingCloserAfterInsertion
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import kotlin.math.roundToInt

@Composable
fun EditorArea(controller: WorkbenchController, modifier: Modifier = Modifier) {
    // Reading the revision makes tab dirty markers recompose after edits.
    controller.documentRevision
    val activeDocument = controller.activeDocument
    val paged = controller.pagedDocument
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (controller.documents.isNotEmpty() || paged != null) EditorTabs(controller)
        activeDocument?.takeUnless { it.path.startsWith("scratch:") }?.let { document ->
            Breadcrumbs(document.path, controller::navigateToDirectory)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val notebook = controller.activeNotebook
            when {
                paged != null -> LargeFileView(controller)
                notebook != null -> NotebookEditor(controller, notebook)
                activeDocument != null -> key(activeDocument.path) { NativeEditorView(controller, activeDocument) }
                else -> EmptyEditor(controller)
            }
            if (notebook == null && paged == null) InlineGhostText(controller)
            if (notebook == null && paged == null && activeDocument != null) {
                ProblemScrollMarks(controller, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/**
 * The active file's problems beside the scrollbar (VS Code's overview ruler): the whole file
 * maps onto the editor's height, and a tap on a mark moves the cursor to that problem. Only the
 * marks take touches, so the editor and its scrollbar keep the rest of the strip.
 */
@Composable
private fun ProblemScrollMarks(controller: WorkbenchController, modifier: Modifier = Modifier) {
    val marks = controller.editorProblemMarks
    if (marks.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxHeight().width(12.dp)) {
        val usable = maxHeight - 8.dp
        marks.forEach { mark ->
            Box(
                Modifier
                    .offset(y = usable * mark.fraction)
                    .size(12.dp, 8.dp)
                    .clickable { controller.goToProblemMark(mark) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(6.dp, 3.dp)
                        .background(colors.problemColor(mark.severity), RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@Composable
private fun EditorTabs(controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(colors.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        controller.documents.forEach { document ->
            val selected = document.path == controller.activePath
            val dirty = document.dirty || controller.notebooks[document.path]?.notebook?.dirty == true
            Surface(
                color = if (selected) colors.surface else colors.surfaceContainer,
                contentColor = if (selected) colors.onSurface else colors.onSurfaceVariant,
                shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                modifier = Modifier.height(34.dp).align(Alignment.Bottom),
            ) {
                Row(
                    Modifier.clickable { controller.selectDocument(document.path) }.padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (document.path.endsWith(".ipynb")) Icons.Outlined.Code else Icons.AutoMirrored.Outlined.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (selected) colors.primary else colors.onSurfaceVariant,
                    )
                    Text(
                        document.path.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                    Box(
                        Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).clickable { controller.requestCloseDocument(document.path) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dirty) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.primary))
                        } else {
                            Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
        controller.pagedDocument?.let {
            Text(
                "${it.path.substringAfterLast('/')} · read-only",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val segments = path.split('/').filter(String::isNotEmpty)
    Row(
        Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 14.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        var directory = ""
        segments.forEachIndexed { index, segment ->
            directory += "/$segment"
            val target = directory
            val last = index == segments.lastIndex
            Text(
                segment,
                style = MaterialTheme.typography.labelMedium,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (last) Modifier else Modifier.clip(RoundedCornerShape(4.dp)).clickable { onNavigate(target) }
                    .padding(horizontal = 2.dp),
            )
            if (!last) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyEditor(controller: WorkbenchController) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("MaterialCode", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(16.dp))
        listOf(
            "Open Folder" to "Ctrl+K Ctrl+O",
            "Quick Open" to "Ctrl+P",
            "Command Palette" to "Ctrl+Shift+P",
            "Toggle Terminal" to "Ctrl+`",
            "Copilot Chat" to "Ctrl+Alt+I",
            "Settings" to "Ctrl+,",
        ).forEach { (label, keys) ->
            Row(Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(vertical = 4.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(keys, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (controller.connected) {
                androidx.compose.material3.FilledTonalButton(onClick = { controller.showOpenFolder() }) { Text("Open folder…") }
            } else {
                androidx.compose.material3.FilledTonalButton(onClick = { controller.dialog = WorkbenchDialog.Connect }) {
                    Text("Connect to guest")
                }
            }
            androidx.compose.material3.OutlinedButton(onClick = { controller.newUntitledFile() }) { Text("New untitled file") }
        }
        // VS Code's Start page: the last folders, a tap away.
        val recent = controller.recentWorkspaceChoices.takeIf { controller.connected }.orEmpty().take(5)
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
                Text("Recent folders", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                recent.forEach { path ->
                    Row(
                        Modifier.fillMaxWidth().clickable { controller.openWorkspace(path) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(path.substringAfterLast('/').ifEmpty { path }, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "  " + path.substringBeforeLast('/'),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LargeFileView(controller: WorkbenchController) {
    val paged = controller.pagedDocument ?: return
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(colors.tertiaryContainer).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Read-only large file · ${paged.size / (1024 * 1024)} MiB · page ${paged.pageIndex + 1}/${paged.pageCount}",
                color = colors.onTertiaryContainer,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { controller.pageLargeFile(-1) }, enabled = paged.canGoPrevious) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous page")
            }
            IconButton(onClick = { controller.pageLargeFile(1) }, enabled = paged.canGoNext) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Next page")
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                CodeEditor(context).apply {
                    setTypefaceText(Typeface.MONOSPACE)
                    setTextSize(13f)
                    setWordwrap(false)
                    setEditable(false)
                    applyEditorTheme(this, context, colors, dark, controller.settings.syntaxColors)
                    setText(paged.text)
                }
            },
            update = { view -> if (view.text.toString() != paged.text) view.setText(paged.text) },
        )
    }
}

@Composable
private fun NativeEditorView(controller: WorkbenchController, document: DocumentSession) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val settings = controller.settings
    val themeRevision = controller.editorThemeRevision
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PythonTextMate.initialize(context)
            NativeCodeEditor(context, controller::runCommand, controller::handleKeyDown).apply {
                setTypefaceText(Typeface.MONOSPACE)
                setTypefaceLineNumber(Typeface.MONOSPACE)
                setDisableSoftKbdIfHardKbdAvailable(false)
                val language = if (document.languageId in setOf("py", "pyi")) {
                    TextMateLanguage.create("source.python", true)
                } else {
                    AssetPackGrammarLoader.scopeNameForExtension(document.languageId)
                        ?.let { TextMateLanguage.create(it, true) }
                        ?: editorLanguage
                }
                applyEditorTheme(this, context, colors, dark, controller.settings.syntaxColors)
                setEditorLanguage(language)
                props.symbolPairAutoCompletion = false
                val bracketColorizer = SoraBracketColorizer(this, document.languageId)
                overlay = bracketColorizer
                val editorSurface = SoraEditorSurface(this, bracketColorizer)
                surface = editorSurface
                editorSurface.applyConfig(controller.editorConfig())
                onStylesPublished = {
                    bracketColorizer.update()
                    editorSurface.drawLineMarks()
                }
                // A tap in the line-number gutter toggles a breakpoint, as in VS Code.
                subscribeAlways(io.github.rosemoe.sora.event.ClickEvent::class.java) { event ->
                    if (event.motionRegion == io.github.rosemoe.sora.event.EditorMotionEvent.REGION_LINE_NUMBER ||
                        event.motionRegion == io.github.rosemoe.sora.event.EditorMotionEvent.REGION_SIDE_ICON
                    ) {
                        controller.toggleBreakpoint(document.path, event.line + 1)
                    }
                }
                setText(document.text)
                controller.editorHost.open(document, editorSurface)
                startSelectionTracking()
                var previousText = document.text
                subscribeAlways(ContentChangeEvent::class.java) { event ->
                    if (event.action == ContentChangeEvent.ACTION_SET_NEW_TEXT) return@subscribeAlways
                    if (!isApplyingAutoPair && event.action == ContentChangeEvent.ACTION_INSERT) {
                        val currentText = text.toString()
                        val insertedText = event.changedText.toString()
                        val insertionOffset = event.changeStart.index
                        (
                            skipExistingCloserAfterInsertion(previousText, currentText, insertionOffset, insertedText)
                                ?: autoPairAfterInsertion(previousText, currentText, insertionOffset, insertedText, selectionBeforeChange)
                            )?.let(::applyAutoPairReplacement)
                    } else if (!isApplyingAutoPair && event.action == ContentChangeEvent.ACTION_DELETE) {
                        pairedCloserOffsetAfterDeletion(
                            previousText,
                            text.toString(),
                            event.changeStart.index,
                            event.changedText.toString(),
                        )?.let(::deletePairedCloser)
                    }
                    document.updateText(text.toString())
                    controller.onDocumentEdited(document)
                    previousText = text.toString()
                }
                controller.editorReady(editorSurface)
            }
        },
        update = { view ->
            if (view.text.toString() != document.text) view.setText(document.text)
            // Reading these here re-runs `update` when breakpoints or the paused line change.
            controller.breakpoints[document.path]
            controller.executionPoint
            val surface = (view as NativeCodeEditor).surface
            controller.refreshLineMarks(document.path)
            surface.applyConfig(controller.editorConfig())
            view.setEditable(true)
            surface.inlineSuggestionShown = controller.inlineSuggestion != null
            controller.editorReady(surface)
        },
    )
    LaunchedEffect(dark, colors, themeRevision, settings.semanticHighlighting, settings.syntaxColors) {
        controller.soraEditor()?.let { applyEditorTheme(it, it.context, colors, dark, settings.syntaxColors) }
    }
    LaunchedEffect(document.path) { controller.onActiveDocumentChanged() }
    DisposableEffect(document.path) {
        onDispose { controller.editorHost.close(document.path) }
    }
}

/** Copilot's suggestion drawn after the cursor in the editor's font, dimmed, like VS Code ghost text. */
@Composable
private fun InlineGhostText(controller: WorkbenchController) {
    controller.editorViewportTick
    val ghost = controller.inlineGhostText()?.takeIf { it.isNotEmpty() } ?: return
    val view = controller.soraEditor() ?: return
    val cursor = view.cursor
    val density = LocalDensity.current
    val x = view.getCharOffsetX(cursor.leftLine, cursor.leftColumn)
    val baseline = view.getCharOffsetY(cursor.leftLine, cursor.leftColumn)
    val top = baseline - view.rowHeight
    val fontSizeSp = with(density) { view.textSizePx.toSp() }
    val lineHeight = with(density) { view.rowHeight.toSp() }
    val lines = ghost.lines()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.offset { IntOffset(x.roundToInt(), top.roundToInt()) }) {
        Text(
            lines.first(),
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp,
            lineHeight = lineHeight,
            color = colors.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        if (lines.size > 1) {
            Surface(color = colors.surface.copy(alpha = 0.92f), shape = RoundedCornerShape(4.dp)) {
                Text(
                    lines.drop(1).take(12).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp,
                    lineHeight = lineHeight,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    softWrap = false,
                    modifier = Modifier.offset { IntOffset(-x.roundToInt() + view.measureTextRegionOffset().roundToInt(), 0) },
                )
            }
        }
        Surface(
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Tab accept",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable { controller.runCommand("editor.action.inlineSuggest.commit") },
                )
                Text(
                    "Esc dismiss",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable { controller.runCommand("editor.action.inlineSuggest.hide") },
                )
            }
        }
    }
}

internal class NativeCodeEditor(
    context: Context,
    private val onCommand: (String) -> Unit,
    private val onKey: (AndroidKeyEvent) -> Boolean,
) : CodeEditor(context) {
    /** Runs on the UI thread each time the analyzer publishes spans, so overlays (bracket pairs,
     *  semantic tokens) are re-applied to the fresh spans before the next frame. */
    var onStylesPublished: (() -> Unit)? = null
    /** Bracket, semantic and problem-tag colours drawn over the analyzer's spans. */
    var overlay: SoraBracketColorizer? = null
    /** This editor as the workbench sees it. */
    lateinit var surface: SoraEditorSurface
    /** `editor.insertSpaces`; the tab size is the editor's tab width. */
    var insertSpaces = true
    /** `editor.autoIndent`; off leaves Enter to sora (a plain newline). */
    var autoIndent = true
    /** `editor.useTabStops`: Backspace in leading spaces removes one indentation level. */
    var useTabStops = true

    override fun setStyles(styles: Styles?) {
        super.setStyles(styles)
        onStylesPublished?.invoke()
    }

    override fun updateStyles(styles: Styles, range: StyleUpdateRange?) {
        super.updateStyles(styles, range)
        onStylesPublished?.invoke()
    }

    /** While a Copilot suggestion is shown, Tab accepts it and Escape dismisses it. */
    var hasInlineSuggestion: Boolean = false
    var selectionBeforeChange: IntRange = 0 until 0
        private set
    var isApplyingAutoPair: Boolean = false
        private set

    fun startSelectionTracking() {
        fun captureSelection() {
            selectionBeforeChange = cursorRange.startIndex until cursorRange.endIndex
        }
        captureSelection()
        subscribeAlways(SelectionChangeEvent::class.java) { captureSelection() }
    }

    fun deletePairedCloser(offset: Int) {
        val position = text.indexer.getCharPosition(offset)
        isApplyingAutoPair = true
        try {
            text.delete(position.line, position.column, position.line, position.column + 1)
        } finally {
            isApplyingAutoPair = false
        }
    }

    fun applyAutoPairReplacement(replacement: AutoPairReplacement) {
        replacement.deleteOffset?.let { offset ->
            val position = text.indexer.getCharPosition(offset)
            text.delete(position.line, position.column, position.line, position.column + 1)
        }
        if (replacement.insertion.isNotEmpty()) {
            val position = text.indexer.getCharPosition(replacement.insertionOffset)
            isApplyingAutoPair = true
            try {
                text.beginBatchEdit()
                try {
                    text.insert(position.line, position.column, replacement.insertion)
                } finally {
                    text.endBatchEdit()
                }
            } finally {
                isApplyingAutoPair = false
            }
        }
        val start = text.indexer.getCharPosition(replacement.selectionStart)
        val end = text.indexer.getCharPosition(replacement.selectionEnd)
        setSelectionRegion(start.line, start.column, end.line, end.column)
    }

    override fun deleteText() {
        val cursor = cursor
        if (useTabStops && insertSpaces && isEditable && !cursor.isSelected) {
            val prefix = text.getLineString(cursor.leftLine).substring(0, cursor.leftColumn)
            backspaceTabStop(prefix, tabWidth)?.let { stop ->
                text.delete(cursor.leftLine, stop, cursor.leftLine, cursor.leftColumn)
                return
            }
        }
        super.deleteText()
    }

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if (hasInlineSuggestion && event.action == AndroidKeyEvent.ACTION_DOWN && event.hasNoModifiers()) {
            when (event.keyCode) {
                AndroidKeyEvent.KEYCODE_TAB -> {
                    onCommand("editor.action.inlineSuggest.commit")
                    return true
                }
                AndroidKeyEvent.KEYCODE_ESCAPE -> {
                    onCommand("editor.action.inlineSuggest.hide")
                    return true
                }
            }
        }
        if (event.action == AndroidKeyEvent.ACTION_DOWN && event.repeatCount == 0 && onKey(event)) return true
        // Enter keeps the indentation (the TextMate grammar has no indentation rules), unless the
        // completion popup is open and Enter picks an item.
        if (event.action == AndroidKeyEvent.ACTION_DOWN && event.keyCode == AndroidKeyEvent.KEYCODE_ENTER &&
            event.hasNoModifiers() && isEditable && autoIndent &&
            !getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java).isShowing
        ) {
            val cursor = cursor
            val prefix = text.getLineString(cursor.leftLine).substring(0, cursor.leftColumn)
            // false: sora's own auto-indent would add the line's indentation a second time.
            commitText(newlineIndentation(prefix, tabWidth, insertSpaces), false)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** The sora view behind the open editor, for the pane's own drawing (theme, ghost text). */
internal fun WorkbenchController.soraEditor(): NativeCodeEditor? = (editorSurface as? SoraEditorSurface)?.editor
