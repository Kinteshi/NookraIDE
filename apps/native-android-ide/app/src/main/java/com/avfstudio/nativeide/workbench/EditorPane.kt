package com.avfstudio.nativeide.workbench

import androidx.compose.runtime.remember
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.layout.layout
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
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.VerticalSplit
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
import androidx.compose.ui.draw.clipToBounds
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
import com.avfstudio.nativeide.document.isDevicePath
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
        val preview = activeDocument?.let { controller.markdownPreviews[it.path] }
        activeDocument?.takeUnless { it.path.startsWith("scratch:") }?.let { document ->
            // A device file has no folders to go to; its crumb says where it is.
            val device = isDevicePath(document.path)
            Breadcrumbs(
                if (device) "/This device/${document.path.substringAfterLast('/')}" else document.path,
                if (device) { _ -> } else controller::navigateToDirectory,
            ) {
                if (controller.hasPreview(document.path)) MarkdownPreviewButtons(controller, preview)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val notebook = controller.activeNotebook
            when {
                paged != null -> LargeFileView(controller)
                notebook != null -> NotebookEditor(controller, notebook)
                activeDocument != null && activeDocument.binary -> key(activeDocument.path) {
                    ImageViewer(controller, activeDocument.path)
                }
                activeDocument != null && preview == MarkdownPreviewMode.Only -> key(activeDocument.path) {
                    if (isSvgPath(activeDocument.path)) {
                        SvgPreview(controller, activeDocument.path, { activeDocument.text })
                    } else {
                        MarkdownPreview(controller, activeDocument, followEditor = false, Modifier.fillMaxSize())
                    }
                }
                activeDocument != null && preview == MarkdownPreviewMode.Side -> key(activeDocument.path) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            NativeEditorView(controller, activeDocument)
                            EditorOverlays(controller)
                        }
                        androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (isSvgPath(activeDocument.path)) {
                            SvgPreview(controller, activeDocument.path, { activeDocument.text }, Modifier.weight(1f).fillMaxHeight())
                        } else {
                            MarkdownPreview(
                                controller,
                                activeDocument,
                                followEditor = controller.settings.markdownScrollSync,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
                activeDocument != null -> key(activeDocument.path) { NativeEditorView(controller, activeDocument) }
                else -> EmptyEditor(controller)
            }
            val image = activeDocument?.binary == true
            if (notebook == null && paged == null && preview == null && !image) EditorOverlays(controller)
            if (notebook == null && paged == null && activeDocument != null && preview == null && !image) {
                ProblemScrollMarks(controller, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/** What the pane draws over the code: inlay hints, Copilot's ghost text and parameter hints. */
@Composable
private fun EditorOverlays(controller: WorkbenchController) {
    InlayHintsOverlay(controller)
    InlineGhostText(controller)
    ParameterHints(controller)
}

/** For a Markdown file: its preview beside the editor (Ctrl+K V) or instead of it (Ctrl+Shift+V). */
@Composable
private fun MarkdownPreviewButtons(controller: WorkbenchController, preview: MarkdownPreviewMode?) {
    val colors = MaterialTheme.colorScheme
    @Composable
    fun button(mode: MarkdownPreviewMode, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
        val on = preview == mode
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(6.dp))
                .background(if (on) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { controller.toggleMarkdownPreview(mode) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp), tint = if (on) colors.onSecondaryContainer else colors.onSurfaceVariant)
        }
    }
    button(MarkdownPreviewMode.Side, Icons.Outlined.VerticalSplit, "Open preview to the side")
    button(MarkdownPreviewMode.Only, Icons.Outlined.Preview, "Open preview")
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
            key(document.path) { EditorTab(controller, document, Modifier.align(Alignment.Bottom)) }
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

/**
 * One tab: the preview tab's name is italic (a double tap keeps it), a pinned tab shows a pin
 * instead of the close button (tap it to unpin), and a long press opens the tab's menu.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EditorTab(controller: WorkbenchController, document: DocumentSession, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val path = document.path
    val selected = path == controller.activePath
    val dirty = document.dirty || controller.notebooks[path]?.notebook?.dirty == true
    val preview = controller.previewTab == path
    val pinned = path in controller.pinnedTabs
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = if (selected) colors.surface else colors.surfaceContainer,
        contentColor = if (selected) colors.onSurface else colors.onSurfaceVariant,
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        modifier = modifier.height(34.dp),
    ) {
        Box {
            Row(
                Modifier
                    .combinedClickable(
                        onClick = { controller.selectDocument(path) },
                        // Only the preview tab waits for a second tap, so other tabs switch at once.
                        onDoubleClick = if (preview) {
                            { controller.keepTab(path) }
                        } else {
                            null
                        },
                        onLongClick = { menu = true },
                    )
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    when {
                        path.endsWith(".ipynb") -> Icons.Outlined.Code
                        isImagePath(path) -> Icons.Outlined.Image
                        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                    },
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (selected) colors.primary else colors.onSurfaceVariant,
                )
                Text(
                    path.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelLarge,
                    fontStyle = if (preview) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1,
                )
                Box(
                    Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).clickable {
                        if (pinned) controller.unpinTab(path) else controller.requestCloseDocument(path)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        dirty -> Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.primary))
                        pinned -> Icon(Icons.Outlined.PushPin, contentDescription = "Unpin", modifier = Modifier.size(14.dp))
                        else -> Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(15.dp))
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun item(label: String, action: () -> Unit) = DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        menu = false
                        action()
                    },
                )
                if (pinned) item("Unpin") { controller.unpinTab(path) } else item("Pin") { controller.pinTab(path) }
                if (preview) item("Keep open") { controller.keepTab(path) }
                item("Close") { controller.requestCloseDocument(path) }
                item("Close others") { controller.closeOtherTabs(path) }
                if (!path.startsWith("scratch:")) item("Copy path") { controller.copyPath(if (isDevicePath(path)) path.substringAfterLast('/') else path) }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit, actions: @Composable () -> Unit = {}) {
    val segments = path.split('/').filter(String::isNotEmpty)
    Row(Modifier.fillMaxWidth().height(26.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).padding(horizontal = 14.dp).horizontalScroll(rememberScrollState()),
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
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
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
            androidx.compose.material3.OutlinedButton(onClick = { controller.pickLocalFile?.invoke() }) { Text("Open from device…") }
        }
        // VS Code's Start page: the last folders, a tap away (without a guest, from this device's copies).
        val offlineFolders = !controller.connected && controller.settings.offlineWorkspace
        val recent = when {
            controller.connected -> controller.recentWorkspaceChoices
            offlineFolders -> controller.recentWorkspaces.filter { it != controller.workspaceRoot }
            else -> emptyList()
        }.take(5)
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
                Text(
                    if (offlineFolders) "Recent folders · offline" else "Recent folders",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                recent.forEach { path ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (offlineFolders) controller.openOfflineWorkspace(path) else controller.openWorkspace(path) }
                            .padding(vertical = 6.dp),
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
    // The preview's layouts replace this view with another for the same document; the old one
    // must not close the binding the new one made.
    val shown = remember { arrayOfNulls<SoraEditorSurface>(1) }
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
                shown[0] = editorSurface
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
        onDispose { shown[0]?.let { controller.editorHost.close(document.path, it) } ?: controller.editorHost.close(document.path) }
    }
}

/**
 * VS Code's parameter hints: the signature with the current parameter in bold, its
 * documentation, and "1/3" when there are overloads. Drawn above the cursor's line (below it on
 * the first lines), inside the editor's width; Escape closes it.
 */
@Composable
private fun ParameterHints(controller: WorkbenchController) {
    controller.editorViewportTick
    val hint = controller.signatureHint ?: return
    val view = controller.soraEditor() ?: return
    val cursor = view.cursor
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val x = view.getCharOffsetX(cursor.leftLine, cursor.leftColumn)
    val lineTop = view.getCharOffsetY(cursor.leftLine, cursor.leftColumn) - view.rowHeight
    val gap = with(density) { 4.dp.roundToPx() }
    val label = remember(hint) {
        buildAnnotatedString {
            append(hint.label)
            if (hint.activeStart in 0 until hint.activeEnd) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.primary), hint.activeStart, hint.activeEnd)
            }
        }
    }
    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 3.dp,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val left = x.roundToInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                val above = lineTop.roundToInt() - placeable.height - gap
                val top = if (above >= 0) above else (lineTop + view.rowHeight).roundToInt() + gap
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(left, top) }
            },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hint.count > 1) {
                    Text(
                        "${hint.index + 1}/${hint.count}  ",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
            }
            (hint.parameterDocumentation ?: hint.documentation)?.let { doc ->
                Text(
                    doc,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Inlay hints at the end of their line, as small chips in the editor's font (sora can't open a
 * gap inside a line). The cursor's line drops them while a Copilot suggestion is drawn there.
 */
@Composable
private fun InlayHintsOverlay(controller: WorkbenchController) {
    controller.editorViewportTick
    controller.documentRevision
    val lines = controller.inlayHintLines
    if (lines.isEmpty()) return
    val view = controller.soraEditor() ?: return
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val fontSize = with(density) { (view.textSizePx * 0.86f).toSp() }
    val rowHeight = view.rowHeight
    val gap = with(density) { 12.dp.toPx() }
    val ghostLine = view.cursor.leftLine.takeIf { controller.inlineGhostText() != null }
    val content = view.text
    Box(Modifier.fillMaxSize().clipToBounds()) {
        for (line in view.firstVisibleLine..view.lastVisibleLine) {
            val hints = lines[line] ?: continue
            if (line == ghostLine || line >= content.lineCount) continue
            val column = content.getColumnCount(line)
            val x = view.getCharOffsetX(line, column) + gap
            val top = view.getCharOffsetY(line, column) - rowHeight
            Row(
                Modifier
                    .offset { IntOffset(x.roundToInt(), top.roundToInt()) }
                    .height(with(density) { rowHeight.toDp() }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                hints.forEach { hint ->
                    Text(
                        hint,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize,
                        color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .background(colors.surfaceContainerHighest.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        }
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
    /** While parameter hints are shown, Escape closes them. */
    var hasParameterHints: Boolean = false

    /** Paste goes through the workbench (shortcut, menu, selection handles) so format on paste applies. */
    override fun pasteText() {
        onCommand("editor.action.clipboardPasteAction")
    }
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
        if (hasParameterHints && event.action == AndroidKeyEvent.ACTION_DOWN && event.hasNoModifiers() &&
            event.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE
        ) {
            onCommand("closeParameterHints")
            return true
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
