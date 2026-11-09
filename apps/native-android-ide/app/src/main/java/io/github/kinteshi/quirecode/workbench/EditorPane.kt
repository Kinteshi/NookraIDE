package io.github.kinteshi.quirecode.workbench

import io.github.kinteshi.quirecode.document.isGuestPath

import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FolderOpen
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
import androidx.compose.foundation.border
import io.github.kinteshi.quirecode.editor.LspPosition
import io.github.kinteshi.quirecode.editor.LspRange
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kinteshi.quirecode.assets.AssetPackGrammarLoader
import io.github.kinteshi.quirecode.document.DocumentSession
import io.github.kinteshi.quirecode.document.isDevicePath
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Info

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
            // A device folder's file (G29): its folder's name and the path below it; a crumb goes to that folder.
            val treeRoot = io.github.kinteshi.quirecode.document.treeRootOf(document.path)
            val treeName = treeRoot?.let(controller::displayName)
            Breadcrumbs(
                when {
                    device -> "/This device/${document.path.substringAfterLast('/')}"
                    treeRoot != null -> "/" + controller.displayPath(document.path)
                    else -> document.path
                },
                when {
                    device -> { _ -> }
                    treeRoot != null -> { crumb -> controller.navigateToDirectory(treeRoot + crumb.removePrefix("/$treeName")) }
                    else -> controller::navigateToDirectory
                },
            ) {
                if (controller.hasPreview(document.path)) MarkdownPreviewButtons(controller, preview)
            }
        }
        activeDocument?.takeIf { it.path in controller.deletedFiles }?.let { DeletedFileBanner(controller, it.path) }
        controller.languagePackOffer?.let { pack -> LanguagePackBanner(controller, pack) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val notebook = controller.activeNotebook
            when {
                paged != null -> LargeFileView(controller)
                notebook != null -> NotebookEditor(controller, notebook)
                activeDocument != null && activeDocument.binary -> key(activeDocument.path) {
                    if (isImagePath(activeDocument.path)) ImageViewer(controller, activeDocument.path) else HexView(controller, activeDocument.path)
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
                            key(controller.grammarRevision) { AardinkEditorView(controller, activeDocument) }
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
                // A grammar that arrives (or goes) makes the editor again with its colours.
                activeDocument != null -> key(activeDocument.path, controller.grammarRevision) { AardinkEditorView(controller, activeDocument) }
                else -> EmptyEditor(controller)
            }
            val image = activeDocument?.binary == true
            // With every tab closed the welcome page shows; the last editor's lenses and hints go with it.
            if (notebook == null && paged == null && activeDocument != null && preview == null && !image) {
                EditorOverlays(controller)
            }
        }
    }
}

/** The active tab's file is gone from the guest; what's shown is this device's copy. */
@Composable
private fun DeletedFileBanner(controller: WorkbenchController, path: String) {
    WorkbenchBanner(
        Icons.Outlined.Warning,
        "This file no longer exists in the guest. This is the copy kept on this device; saving puts it back.",
    ) {
        androidx.compose.material3.TextButton(onClick = controller::saveActiveDocument) { Text("Save") }
        androidx.compose.material3.TextButton(onClick = { controller.requestCloseDocument(path) }) { Text("Close") }
    }
}

/** A file without colours whose language VS Code's built-in extensions cover: fetch them, later, or never. */
@Composable
private fun LanguagePackBanner(controller: WorkbenchController, pack: io.github.kinteshi.quirecode.assets.LanguagePack) {
    val downloading = pack.id in controller.languagePackDownloads
    WorkbenchBanner(
        Icons.Outlined.Palette,
        if (downloading) "Downloading ${pack.languageName} colours…" else "Colour ${pack.languageName} files? Downloads VS Code's ${pack.languageName} grammar (${pack.id} ${pack.version}) from Open VSX.",
        tonal = true,
    ) {
        if (!downloading) {
            androidx.compose.material3.TextButton(onClick = { controller.installLanguagePack(pack) }) { Text("Download") }
            androidx.compose.material3.TextButton(onClick = { controller.postponeLanguagePack(pack) }) { Text("Not now") }
            androidx.compose.material3.TextButton(onClick = { controller.declineLanguagePack(pack) }) { Text("Never") }
        }
    }
}

/** The workbench's cards over the code (Next Edit, suggestions, parameter hints, hover, the peek) and the find widget. */
@Composable
private fun EditorOverlays(controller: WorkbenchController) {
    LaunchedEffect(controller.settings.gitBlame, controller.activePath, controller.workspaceRoot) {
        if (controller.settings.gitBlame) controller.activePath?.let(controller::loadBlame)
    }
    AardinkNextEditCard(controller)
    AardinkSuggestionList(controller)
    AardinkParameterHints(controller)
    AardinkHoverCard(controller)
    PeekOverlay(controller)
    if (controller.findVisible) {
        Box(Modifier.fillMaxSize()) { FindWidget(controller, Modifier.align(Alignment.TopEnd)) }
    }
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
    // Read here, not only above: a skipped tab (same document object) missed a save's clean state until something
    // else redrew it, which for a guest file was the Git refresh after the save and for a device folder's nothing.
    controller.documentRevision
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
                val fallback = when {
                    path.endsWith(".ipynb") -> Icons.Outlined.Code
                    isImagePath(path) -> Icons.Outlined.Image
                    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                }
                FileIconView(
                    path.substringAfterLast('/'),
                    controller.fileIconTheme,
                    LocalDarkTheme.current,
                    fallback,
                    size = 15.dp,
                    tint = if (selected) colors.primary else colors.onSurfaceVariant,
                )
                val deleted = path in controller.deletedFiles
                Text(
                    path.substringAfterLast('/') + if (deleted) " (deleted)" else "",
                    style = MaterialTheme.typography.labelLarge,
                    fontStyle = if (preview) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (deleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    color = if (deleted) colors.error else androidx.compose.ui.graphics.Color.Unspecified,
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
                fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) = DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    onClick = {
                        menu = false
                        action()
                    },
                )
                if (pinned) item("Unpin", Icons.Outlined.PushPin) { controller.unpinTab(path) } else item("Pin", Icons.Outlined.PushPin) { controller.pinTab(path) }
                if (preview) item("Keep open", Icons.Outlined.Check) { controller.keepTab(path) }
                item("Close", Icons.Outlined.Close) { controller.requestCloseDocument(path) }
                item("Close others", Icons.Outlined.ClearAll) { controller.closeOtherTabs(path) }
                item("Close all", Icons.Outlined.DeleteSweep) { controller.runCommand("workbench.action.closeAllEditors") }
                if (!path.startsWith("scratch:")) item("Copy path", Icons.Outlined.ContentCopy) { controller.copyPath(if (isDevicePath(path)) path.substringAfterLast('/') else path) }
                if (path == controller.activePath && (isGuestPath(path) || io.github.kinteshi.quirecode.document.isTreePath(path) || path.startsWith("scratch:"))) {
                    item("Share…", Icons.Outlined.Share) { controller.shareActiveFile() }
                }
                if (isGuestPath(path)) item("Save to the device…", Icons.Outlined.Download) { controller.beginSaveToDevice(listOf(path)) }
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

/**
 * The welcome page's first-run card: what the app needs (a Linux machine: Linux Terminal's guest, or a computer), and
 * the setup a tap away. Without Linux Terminal turned on it says where to turn it on.
 */
@Composable
private fun GetStartedCard(controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    val terminal = controller.linuxTerminalAvailable()
    androidx.compose.material3.Surface(
        color = colors.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Get started", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(
                "QuireCode edits files on a Linux machine: the guest of Android's Linux Terminal on this tablet, or a " +
                    "computer on your network. Set it up once; afterwards the app connects by itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            if (!terminal) {
                Text(
                    "Linux Terminal isn't turned on here. Turn on Linux development environment in Developer options, " +
                        "open Linux Terminal once to install its Debian guest, then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (terminal) {
                    androidx.compose.material3.Button(onClick = { controller.beginGuestSetup() }) { Text("Set up the guest") }
                } else {
                    androidx.compose.material3.Button(onClick = controller::openDeveloperOptions) { Text("Developer options") }
                }
                androidx.compose.material3.OutlinedButton(onClick = { controller.dialog = WorkbenchDialog.ComputerPairing }) {
                    Text(if (terminal) "Pair with a computer" else "Use a computer instead")
                }
                androidx.compose.material3.TextButton(onClick = { controller.dialog = WorkbenchDialog.ConnectionEditor(null) }) { Text("Connect by hand") }
            }
        }
    }
}

@Composable
private fun EmptyEditor(controller: WorkbenchController) = BoxWithConstraints(Modifier.fillMaxSize()) {
    // Centred when it fits; scrolls in a short window (a floating one, or a phone in landscape).
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val colors = MaterialTheme.colorScheme
        Icon(
            androidx.compose.ui.res.painterResource(io.github.kinteshi.quirecode.R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(96.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("QuireCode", style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
            DevBadge()
        }
        Spacer(Modifier.height(16.dp))
        // First run (R7): nothing paired yet, so the way in comes first.
        if (!controller.connected && controller.profiles.isEmpty()) {
            GetStartedCard(controller)
            Spacer(Modifier.height(16.dp))
        }
        // Each line does what it says; the keys are for next time.
        val actions: List<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, Pair<String, () -> Unit>>> = listOf(
            Triple(Icons.Outlined.FolderOpen, "Open Folder", "Ctrl+K Ctrl+O" to { if (controller.connected) controller.showOpenFolder() else controller.openConnect() }),
            Triple(Icons.Outlined.Search, "Quick Open", "Ctrl+P" to { controller.runCommand("workbench.action.quickOpen") }),
            Triple(Icons.Outlined.Widgets, "Command Palette", "Ctrl+Shift+P" to { controller.runCommand("workbench.action.showCommands") }),
            Triple(Icons.Outlined.Terminal, "Toggle Terminal", "Ctrl+`" to { controller.runCommand("workbench.action.terminal.toggleTerminal") }),
            Triple(Icons.Outlined.AutoAwesome, "Chat", "Ctrl+Alt+I" to { controller.runCommand("workbench.action.chat.open") }),
            Triple(Icons.Outlined.Settings, "Settings", "Ctrl+," to { controller.settingsVisible = true }),
        )
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth()) {
            actions.forEach { (icon, label, keysAndAction) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = keysAndAction.second).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(label, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(keysAndAction.first, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        // Wraps at phone width rather than squeezing the buttons.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!controller.connected && controller.profiles.isNotEmpty()) {
                androidx.compose.material3.FilledTonalButton(onClick = controller::openConnect) {
                    Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                    Text("Connections")
                }
            }
            androidx.compose.material3.OutlinedButton(onClick = { controller.newUntitledFile() }) {
                Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                Text("New untitled file")
            }
            androidx.compose.material3.OutlinedButton(onClick = { controller.pickLocalFile?.invoke() }) {
                Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                Text("Open from device…")
            }
            // A folder on this device as the workspace, no guest needed (G29).
            androidx.compose.material3.OutlinedButton(onClick = controller::beginOpenDeviceFolder) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                Text("Open device folder…")
            }
        }
        // VS Code's Start page: the last folders, a tap away (without a guest, from this device's copies).
        val offlineFolders = !controller.connected && controller.settings.offlineWorkspace
        // Device folders (G29) open with or without the guest.
        val guestRecent = when {
            controller.connected -> controller.recentWorkspaceChoices
            offlineFolders -> controller.recentWorkspaces.filter { it != controller.workspaceRoot && isGuestPath(it) }
            else -> emptyList()
        }
        val recent = controller.recentWorkspaces.filter { path ->
            path != controller.workspaceRoot && (path in guestRecent || io.github.kinteshi.quirecode.document.isTreePath(path))
        }.take(5)
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.widthIn(max = 400.dp).fillMaxWidth()) {
                Text(
                    if (offlineFolders) "Recent folders · offline" else "Recent folders",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                recent.forEach { path ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (offlineFolders && isGuestPath(path)) controller.openOfflineWorkspace(path) else controller.openWorkspace(path)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val onDevice = io.github.kinteshi.quirecode.document.isTreePath(path)
                        Icon(if (onDevice) Icons.Outlined.PhoneAndroid else Icons.Outlined.Folder, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Text(controller.displayName(path), color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "  " + if (onDevice) "on this device" else path.substringBeforeLast('/'),
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
    Column(Modifier.fillMaxSize()) {
        WorkbenchBanner(
            Icons.Outlined.Description,
            "Read-only large file · ${paged.size / (1024 * 1024)} MiB · page ${paged.pageIndex + 1}/${paged.pageCount}",
            tonal = true,
        ) {
            Tooltip("Previous page") { IconButton(onClick = { controller.pageLargeFile(-1) }, enabled = paged.canGoPrevious) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous page")
            } }
            Tooltip("Next page") { IconButton(onClick = { controller.pageLargeFile(1) }, enabled = paged.canGoNext) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Next page")
            } }
        }
        AardinkReadOnlyView(paged.text, controller.settings.syntaxColors, Modifier.fillMaxSize())
    }
}

/** The parameter hints' card, wherever it's placed ([modifier]). */
@Composable
internal fun ParameterHintsCard(hint: io.github.kinteshi.quirecode.language.SignatureHint, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
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
        modifier = Modifier.widthIn(max = 560.dp).then(modifier),
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
 * The hover card itself, wherever it's placed ([placement], before the pointer handling so only the card takes
 * the pointer): problems, then the server's Markdown. Both editor engines draw this one.
 */
@Composable
internal fun HoverCardSurface(controller: WorkbenchController, card: HoverCard, placement: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier
            .widthIn(max = 560.dp)
            .heightIn(max = 320.dp)
            .then(placement)
            // After the placement, so only the card itself (not the whole pane) takes the pointer.
            .pointerInput(card) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            androidx.compose.ui.input.pointer.PointerEventType.Enter -> controller.hoverCardHovered = true
                            androidx.compose.ui.input.pointer.PointerEventType.Exit -> controller.leaveHoverCard()
                        }
                    }
                }
            },
    ) {
        val problems = card.problems.map { problem ->
            val code = problem.code?.takeIf { it.isJsonPrimitive }?.asString
            val origin = when {
                problem.source != null && code != null -> "${problem.source}($code)"
                else -> listOfNotNull(problem.source, code).firstOrNull().orEmpty()
            }
            HoverProblem(problem.severity, problem.message, origin)
        }
        // Expanding is for documentation: a card with problems only has none.
        val expand = card.markdown?.let { markdown ->
            {
                val place = "${card.path.substringAfterLast('/')}:${card.position.line + 1}"
                val word = controller.editorSurface?.lineText(card.position.line)?.let { wordAt(it, card.position.character) }
                controller.expandHover(ExpandedDocs(card.path, word?.let { "$it · $place" } ?: place, markdown))
            }
        }
        HoverCardContent(controller, card.path, problems, card.markdown, expand)
    }
}
