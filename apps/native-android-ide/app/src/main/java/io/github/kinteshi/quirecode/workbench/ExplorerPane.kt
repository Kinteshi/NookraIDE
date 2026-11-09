package io.github.kinteshi.quirecode.workbench

import androidx.compose.material.icons.outlined.CloudOff
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A drag in Explorer (long press, then move): what is carried, where the finger is (in root
 * coordinates) and the folder it would land in. Rows register where they are and which folder
 * a drop on them means: a folder itself, a file's folder.
 */
private class ExplorerDrag {
    var paths by mutableStateOf<List<String>?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var target by mutableStateOf<String?>(null)
    val rows = mutableMapOf<String, Pair<Rect, String>>()

    fun update() {
        target = rows.values.firstOrNull { (bounds, _) -> bounds.contains(pointer) }?.second
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ExplorerList(controller: WorkbenchController) {
    val directory = controller.currentDirectory
    // Changes made in a terminal since the last save or Git action show when Explorer opens.
    LaunchedEffect(controller.connected) { if (controller.connected && controller.settings.gitDecorations) controller.refreshGitStatus() }
    when {
        controller.offline -> OfflineFilesList(controller)
        // The connection's own words (G22): set up, connecting, offline and why.
        !controller.connected && controller.roots.isEmpty() -> controller.connection.let { summary ->
            EmptyState(Icons.Outlined.CloudOff, summary.title, "Explorer shows the files of the machine QuireCode is connected to.") {
                androidx.compose.material3.FilledTonalButton(onClick = controller::openConnect) {
                    Text(if (summary.state == ConnectionState.NotSetUp) "Set up" else "Connections")
                }
            }
        }
        directory.isEmpty() -> LazyColumn {
            items(controller.roots) { root ->
                PaneRow(Icons.Outlined.Folder, root.substringAfterLast('/').ifEmpty { root }) { controller.loadDirectory(root) }
            }
        }
        else -> ExplorerTree(controller, directory)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExplorerTree(controller: WorkbenchController, directory: String) {
    val settings = controller.settings
    val tree = settings.explorerTree
    val marks = controller.explorerMarks
    val hidden = { entry: io.github.kinteshi.quirecode.transport.FileEntry -> controller.isExcluded(entry.path) }
    val rows = if (tree) {
        explorerRows(controller.entries, controller.expandedFolders.toSet(), controller.folderChildren, compact = settings.compactFolders, hidden = hidden)
    } else {
        controller.entries.filterNot(hidden).map { ExplorerRow(it, 0, expanded = false) }
    }
    val selection = controller.explorerSelection
    val selecting = selection.isNotEmpty()
    val modifiers = LocalWindowInfo.current.keyboardModifiers
    val drag = remember { ExplorerDrag() }
    val listState = rememberLazyListState()
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    // Near the top or bottom edge, a drag scrolls the list.
    LaunchedEffect(drag.paths != null) {
        while (drag.paths != null) {
            val y = drag.pointer.y
            val step = when {
                y < listBounds.top + edge -> -18f
                y > listBounds.bottom - edge -> 18f
                else -> 0f
            }
            if (step != 0f) {
                listState.scrollBy(step)
                drag.update()
            }
            delay(16)
        }
    }
    val parent = directory.substringBeforeLast('/', "")
    // The open workspace is the top, as in VS Code (the daemon may serve folders above it).
    val atRoot = controller.workspaceRoot?.let { it.trimEnd('/') == directory.trimEnd('/') }
        ?: controller.roots.any { it.trimEnd('/') == directory.trimEnd('/') }
    // Files made outside the app (a terminal, the agent) are there when Explorer opens.
    LaunchedEffect(Unit) { controller.refreshExplorer() }
    Column(Modifier.fillMaxSize()) {
        // Its own section above the files, as in VS Code (inside the list, tabs opened later went
        // above the rows it keeps in view).
        if (settings.openEditors && controller.documents.isNotEmpty()) {
            OpenEditorsHeader(controller)
            if (controller.openEditorsExpanded) {
                // Six rows, and half of a seventh when there are more to scroll to.
                Column(Modifier.heightIn(max = 246.dp).verticalScroll(rememberScrollState())) {
                    controller.documents.toList().forEach { document -> key(document.path) { OpenEditorRow(controller, document) } }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp))
        }
        Box(Modifier.weight(1f).onGloballyPositioned { listBounds = it.boundsInRoot() }) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "up") {
                    Box(Modifier.dropTarget(drag, "up", parent.takeIf { !atRoot && it.isNotEmpty() })) {
                        PaneRow(
                            // The arrow only where it goes somewhere.
                            if (atRoot) Icons.Outlined.FolderOpen else Icons.AutoMirrored.Outlined.ArrowBack,
                            if (atRoot) directory else "..",
                            muted = true,
                            selected = drag.target != null && drag.target == parent && !atRoot,
                            trailing = if (tree && rows.any { it.expanded }) {
                                {
                                    Tooltip("Collapse folders") { IconButton(onClick = controller::collapseFolders, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Outlined.UnfoldLess, contentDescription = "Collapse folders", modifier = Modifier.size(18.dp))
                                    } }
                                }
                            } else {
                                null
                            },
                        ) {
                            if (!atRoot && parent.isNotEmpty()) controller.loadDirectory(parent)
                        }
                    }
                }
                items(rows, key = { it.entry.path }) { row ->
                    val entry = row.entry
                    val mark = marks[entry.path]
                    val dark = LocalDarkTheme.current
                    var menu by remember { mutableStateOf(false) }
                    val dropFolder = if (entry.isDirectory) entry.path else entry.path.substringBeforeLast('/')
                    val picked = entry.path in selection
                    Box(Modifier.dropTarget(drag, entry.path, dropFolder)) {
                        PaneRow(
                            when {
                                selecting -> if (picked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank
                                // The tree marks folders with VS Code's chevrons (the open folder icon is
                                // too close to the closed one at this size).
                                tree && row.expanded -> Icons.Outlined.KeyboardArrowDown
                                tree && entry.isDirectory -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
                                entry.isDirectory -> Icons.Outlined.Folder
                                entry.name.endsWith(".ipynb") -> Icons.Outlined.Code
                                else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                            },
                            row.label,
                            leading = controller.fileIconTheme?.takeIf { !selecting && (!entry.isDirectory || it.hasFolderIcons) }?.let { theme ->
                                {
                                    if (entry.isDirectory) {
                                        // A theme with folder icons (Material Icon Theme): its folder, after the chevron unless it hides them.
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            if (tree && !theme.hidesExplorerArrows) {
                                                Icon(
                                                    if (row.expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            FileIconView(entry.name, theme, dark, Icons.Outlined.Folder, folder = true, expanded = row.expanded)
                                        }
                                    } else {
                                        FileIconView(entry.name, theme, dark, Icons.AutoMirrored.Outlined.InsertDriveFile)
                                    }
                                }
                            },
                            selected = picked || (entry.isDirectory && drag.target == entry.path) || (!selecting && entry.path == controller.activePath),
                            indent = row.depth,
                            onLongClick = { menu = true },
                            longClick = !settings.explorerDragAndDrop,
                            dragModifier = if (settings.explorerDragAndDrop) {
                                Modifier.explorerDragSource(controller, drag, entry.path) { menu = true }
                            } else {
                                Modifier
                            },
                            trailing = mark?.let { { ExplorerMark(it, dark) } },
                            labelColor = mark?.tone?.color(dark),
                        ) {
                            when {
                                modifiers.isShiftPressed -> controller.selectExplorerRange(entry.path, rows.map { it.entry.path })
                                modifiers.isCtrlPressed || selecting -> controller.toggleExplorerSelection(entry.path)
                                !entry.isDirectory -> controller.openPath(entry.path, preview = true)
                                tree -> controller.toggleFolder(entry.path)
                                else -> controller.loadDirectory(entry.path)
                            }
                        }
                        EntryMenu(controller, entry, tree, expanded = menu) { menu = false }
                    }
                }
            }
            drag.paths?.let { paths ->
                val label = if (paths.size == 1) paths.single().substringAfterLast('/') else "${paths.size} items"
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = MaterialTheme.shapes.small,
                    shadowElevation = 4.dp,
                    modifier = Modifier.offset {
                        IntOffset((drag.pointer.x - listBounds.left + 16).roundToInt(), (drag.pointer.y - listBounds.top - 40).roundToInt())
                    },
                ) {
                    Text(
                        drag.target?.let { "$label → ${it.substringAfterLast('/')}" } ?: label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        // Below the files while something is selected (G24): what applies to all of it.
        if (selecting) SelectionBar(controller, selection.toList())
    }
}

/** Where a row is, so a drag can land on it ([folder]: where a drop moves to; null takes no drops). */
@Composable
private fun Modifier.dropTarget(drag: ExplorerDrag, key: String, folder: String?): Modifier {
    // A row scrolled out of the list is gone; it must not catch drops where another row now is.
    DisposableEffect(key) { onDispose { drag.rows.remove(key) } }
    return onGloballyPositioned { coordinates ->
        if (folder == null) drag.rows.remove(key) else drag.rows[key] = coordinates.boundsInRoot() to folder
    }
}

/**
 * Long press and move to carry the row (or the selection it is in) onto a folder; a long press
 * that doesn't move opens the row's menu instead.
 */
@Composable
private fun Modifier.explorerDragSource(controller: WorkbenchController, drag: ExplorerDrag, path: String, onMenu: () -> Unit): Modifier {
    // Read by the gesture, which outlives recompositions.
    val origin = remember { arrayOf(Offset.Zero) }
    val menu by rememberUpdatedState(onMenu)
    return this
        .onGloballyPositioned { origin[0] = it.positionInRoot() }
        .pointerInput(path) {
            var travelled = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    travelled = 0f
                    drag.pointer = origin[0] + offset
                    drag.paths = if (path in controller.explorerSelection) controller.explorerSelection.toList() else listOf(path)
                    controller.haptic(HapticFeedbackConstants.LONG_PRESS)
                },
                onDrag = { change, amount ->
                    change.consume()
                    travelled += amount.getDistance()
                    drag.pointer += amount
                    drag.update()
                },
                onDragEnd = {
                    val paths = drag.paths
                    val target = drag.target
                    drag.paths = null
                    drag.target = null
                    when {
                        travelled < 24f -> menu()
                        paths != null && target != null -> controller.moveEntries(paths, target)
                    }
                },
                onDragCancel = {
                    drag.paths = null
                    drag.target = null
                },
            )
        }
}

@Composable
private fun SelectionBar(controller: WorkbenchController, selection: List<String>) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(6.dp), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${selection.size} selected", style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.weight(1f))
            Tooltip("Share") { IconButton(onClick = { controller.shareEntries(selection) }) {
                Icon(Icons.Outlined.Share, contentDescription = "Share")
            } }
            Tooltip("Save to the device") { IconButton(onClick = { controller.beginSaveToDevice(selection) }) {
                Icon(Icons.Outlined.Download, contentDescription = "Save to the device")
            } }
            Tooltip("Copy paths") { IconButton(onClick = { controller.copyPaths(selection) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy paths")
            } }
            Tooltip("Move to…") { IconButton(onClick = { controller.beginMoveEntries(selection) }) {
                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = "Move to…")
            } }
            Tooltip("Delete") { IconButton(onClick = { controller.beginDeleteEntries(selection) }) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
            } }
            Tooltip("Clear selection") { IconButton(onClick = controller::clearExplorerSelection) {
                Icon(Icons.Outlined.Close, contentDescription = "Clear selection")
            } }
        }
    }
}

@Composable
private fun OpenEditorsHeader(controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { controller.openEditorsExpanded = !controller.openEditorsExpanded }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (controller.openEditorsExpanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = colors.onSurfaceVariant,
        )
        Text("OPEN EDITORS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        val dirty = controller.documents.count { it.dirty || controller.notebooks[it.path]?.notebook?.dirty == true }
        if (dirty > 0) Text("$dirty unsaved", style = MaterialTheme.typography.labelSmall, color = colors.primary)
    }
}

/** A tab in Explorer's Open Editors: tap to show it, the button closes it (a dot while unsaved). */
@Composable
private fun OpenEditorRow(controller: WorkbenchController, document: io.github.kinteshi.quirecode.document.DocumentSession) {
    val path = document.path
    val dirty = document.dirty || controller.notebooks[path]?.notebook?.dirty == true
    val folder = path.substringBeforeLast('/', "").removePrefix(controller.workspaceRoot?.trimEnd('/').orEmpty()).trim('/')
    PaneRow(
        when {
            path.endsWith(".ipynb") -> Icons.Outlined.Code
            else -> Icons.AutoMirrored.Outlined.InsertDriveFile
        },
        path.removePrefix("scratch:/").substringAfterLast('/') + (if (path in controller.deletedFiles) " (deleted)" else "") + if (folder.isNotEmpty() && !path.startsWith("scratch:")) "  ·  $folder" else "",
        labelColor = MaterialTheme.colorScheme.error.takeIf { path in controller.deletedFiles },
        leading = controller.fileIconTheme?.let { theme ->
            { FileIconView(path.substringAfterLast('/'), theme, LocalDarkTheme.current, Icons.AutoMirrored.Outlined.InsertDriveFile) }
        },
        selected = path == controller.activePath,
        trailing = {
            Box(
                Modifier.size(24.dp).clickable { controller.requestCloseDocument(path) },
                contentAlignment = Alignment.Center,
            ) {
                if (dirty) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                } else {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(15.dp))
                }
            }
        },
    ) { controller.selectDocument(path) }
}

@Composable
private fun EntryMenu(
    controller: WorkbenchController,
    entry: io.github.kinteshi.quirecode.transport.FileEntry,
    tree: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) = DropdownMenuItem(
            text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            onClick = {
                onDismiss()
                action()
            },
        )
        if (entry.isDirectory) {
            item("New file here", Icons.AutoMirrored.Outlined.NoteAdd) { controller.beginNewEntry(folder = false, parent = entry.path) }
            if (tree) item("Show only this folder", Icons.Outlined.FolderOpen) { controller.loadDirectory(entry.path) }
        } else {
            item("Add to Copilot chat", Icons.Outlined.AutoAwesome) { controller.attachFileToChat(entry.path) }
        }
        item("Select", Icons.Outlined.CheckBox) { controller.toggleExplorerSelection(entry.path) }
        item("Share…", Icons.Outlined.Share) { controller.shareEntries(listOf(entry.path)) }
        item("Save to the device…", Icons.Outlined.Download) { controller.beginSaveToDevice(listOf(entry.path)) }
        if (entry.isDirectory) {
            item("Import files here…", Icons.Outlined.FileUpload) { controller.beginImportFiles(entry.path) }
            item("Import a folder here…", Icons.Outlined.DriveFolderUpload) { controller.beginImportFolder(entry.path) }
        }
        item("Rename", Icons.Outlined.DriveFileRenameOutline) { controller.beginRenameEntry(entry.path) }
        item("Move to…", Icons.AutoMirrored.Outlined.DriveFileMove) { controller.beginMoveEntries(listOf(entry.path)) }
        item("Delete", Icons.Outlined.Delete) { controller.beginDeleteEntry(entry.path, entry.isDirectory) }
    }
}
