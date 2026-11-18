package io.github.kinteshi.quirecode.workbench

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import android.util.Log
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.kinteshi.quirecode.notebook.formatRunDuration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kinteshi.quirecode.notebook.CellType
import io.github.kinteshi.quirecode.notebook.NotebookCell
import io.github.kinteshi.quirecode.notebook.NotebookDocument
import com.google.gson.JsonObject

@Composable
fun NotebookEditor(controller: WorkbenchController, session: NotebookSession) {
    controller.documentRevision
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(colors.surfaceContainerLow)) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .widthIn(min = maxWidth)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        modifier = Modifier.noKeyFocus(),
                        onClick = { controller.chooseKernel(session) },
                        // Before a run: the kernel the notebook names (it starts on the first run), as in VS Code.
                        label = { Text(session.kernelName ?: session.notebook.kernelDisplayName ?: "Select kernel") },
                        leadingIcon = { Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    Text(
                        if (session.kernelName == null && session.kernelState == "No kernel" && session.notebook.kernelDisplayName != null) "Not started" else session.kernelState,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    if (session.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolbarButton(Icons.Outlined.FastForward, "Run all") { controller.runAllCells(session) }
                    ToolbarButton(Icons.Outlined.Stop, "Interrupt") { controller.interruptKernel(session) }
                    ToolbarButton(Icons.Outlined.RestartAlt, "Restart kernel") { controller.restartKernel(session) }
                    ToolbarButton(Icons.Outlined.ClearAll, "Clear outputs") { controller.clearAllOutputs(session) }
                    ToolbarButton(Icons.Outlined.DataObject, "Variables") { controller.showVariables() }
                    ToolbarButton(Icons.Outlined.Save, "Save") { controller.saveNotebook(session) }
                }
            }
        }
        // Cells added, moved or deleted: the language server gets the notebook again.
        LaunchedEffect(session, session.notebook.cells.map { it.id }) { controller.scheduleCellMirrorSync(session, delayMs = 200) }
        val listState = rememberLazyListState()
        // Keep the selected cell composed and on screen so it can take focus (Shift+Enter moves on).
        LaunchedEffect(session.selectedCell) {
            val index = session.notebook.cells.indexOfFirst { it.id == session.selectedCell }
            val visible = listState.layoutInfo.visibleItemsInfo
            if (index >= 0 && (visible.none { it.index == index } || visible.last().index == index)) {
                listState.animateScrollToItem(index)
            }
        }
        Box(Modifier.fillMaxSize().clipToBounds()) {
        LazyColumn(
            Modifier.fillMaxSize().editorFontZoom(controller),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(session.notebook.cells, key = { it.id }) { cell ->
                val dragged = session.draggedCell == cell.id
                NotebookCellView(
                    controller, session, cell, listState,
                    if (dragged) {
                        Modifier.zIndex(1f).graphicsLayer { translationY = session.dragOffset; shadowElevation = 12f }
                    } else {
                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                    },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                    TextButton(modifier = Modifier.noKeyFocus(), onClick = { controller.addCell(session, session.notebook.cells.lastOrNull()) }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(" Code")
                    }
                    TextButton(modifier = Modifier.noKeyFocus(), onClick = { controller.addCell(session, session.notebook.cells.lastOrNull(), CellType.Markdown) }) {
                        Icon(Icons.Outlined.TextFields, contentDescription = null)
                        Text(" Markdown")
                    }
                }
            }
        }
        if (controller.settings.stickyCellHeader) StickyCellHeader(controller, session, listState)
        if (controller.findVisible) FindWidget(controller, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp))
        UndoDeleteBar(controller, session, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ToolbarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Tooltip(label) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.noKeyFocus().semantics { contentDescription = label },
        ) { Icon(icon, contentDescription = label) }
    }
}

/** The cursor's visual row (wrapped rows count); falls back to logical lines before the first layout. */
private fun visualRow(layout: TextLayoutResult?, text: String, offset: Int): Int =
    layout?.takeIf { it.layoutInput.text.length == text.length }?.getLineForOffset(offset.coerceIn(0, text.length))
        ?: text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' }

private fun visualRowCount(layout: TextLayoutResult?, text: String): Int =
    layout?.takeIf { it.layoutInput.text.length == text.length }?.lineCount ?: (text.count { it == '\n' } + 1)

/** Keys that must never move focus out of a cell. */
private val cellKeepsKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.Tab)

/** `adb shell setprop log.tag.AVFNB DEBUG` logs every cell cursor move (for key navigation tests). */
private const val NOTEBOOK_LOG_TAG = "AVFNB"

/** Notebook controls stay tappable but out of the arrow-key path, so Up/Down and Enter can't hit Delete. */
internal fun Modifier.noKeyFocus(): Modifier = focusProperties { canFocus = false }

@Composable
private fun NotebookCellView(
    controller: WorkbenchController,
    session: NotebookSession,
    cell: NotebookCell,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val selected = session.selectedCell == cell.id
    val dark = LocalDarkTheme.current
    val cellIndex = session.notebook.cells.indexOf(cell).coerceAtLeast(0)
    Column(
        modifier
            .semantics { contentDescription = notebookCellDescription(cellIndex, session.notebook.cells.size, cell, selected) }
            .background(if (session.draggedCell == cell.id) colors.surface else Color.Transparent)
            .widthIn(max = 1100.dp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row {
            Column(Modifier.width(CELL_GUTTER).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CellRunButton(controller, session, cell, selected)
                if (cell.type == CellType.Code && controller.settings.cellStatistics) {
                    CellRunStatus(controller, session, cell, Modifier.padding(top = 2.dp))
                }
                if (selected || session.draggedCell == cell.id) CellDragHandle(controller, session, cell, listState)
            }
            Column(Modifier.weight(1f)) {
                Surface(
                    color = if (cell.type == CellType.Code) colors.surfaceContainerLow else colors.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .noKeyFocus()
                        .clickable { session.selectedCell = cell.id },
                ) {
                    if (cell.type == CellType.Markdown && !selected) {
                        val source = remember(session.notebook.path, cell.attachments) { controller.markdownSource(session.notebook.path, cell.attachments) }
                        NotebookMarkdown(controller, cell.source.ifBlank { "*Empty Markdown cell*" }, source, Modifier.padding(12.dp))
                    } else if (controller.settings.cellEditor == "aardink") {
                        AardinkCellEditor(controller, session, cell, selected)
                    } else {
                        CellSourceEditor(controller, session, cell, dark, selected)
                    }
                }
                CellProblemsList(controller, session, cell)
                if (selected) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.noKeyFocus()) {
                        CellTypePicker(controller, session, cell)
                        Spacer(Modifier.weight(1f))
                        CellActions(controller, session, cell)
                    }
                }
            }
        }
        if (cell.type == CellType.Code) CellOutputs(controller, session, cell, selected)
    }
}

/** The selected cell's kind: Code, Markdown or Raw, from a menu (as VS Code's language picker on a cell). */
@Composable
private fun CellTypePicker(controller: WorkbenchController, session: NotebookSession, cell: NotebookCell) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(modifier = Modifier.noKeyFocus(), onClick = { open = true }) {
            Text(cell.type.title)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CellType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.title) },
                    leadingIcon = { if (type == cell.type) Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        open = false
                        controller.setCellType(session, cell, type)
                    },
                )
            }
        }
    }
}

private val CellType.title: String
    get() = when (this) {
        CellType.Code -> "Code"
        CellType.Markdown -> "Markdown"
        CellType.Raw -> "Raw"
    }

/** Move, insert and delete: the selected cell's toolbar, and its sticky header. */
@Composable
private fun CellActions(controller: WorkbenchController, session: NotebookSession, cell: NotebookCell) {
    Tooltip("Move up") { IconButton(onClick = { controller.moveCell(session, cell, -1) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.ArrowUpward, "Move up") } }
    Tooltip("Move down") { IconButton(onClick = { controller.moveCell(session, cell, 1) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.ArrowDownward, "Move down") } }
    Tooltip("Insert cell below") { IconButton(onClick = { controller.addCell(session, cell) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.Add, "Insert cell below") } }
    Tooltip("Delete cell") { IconButton(onClick = { controller.deleteCell(session, cell) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.Delete, "Delete cell") } }
}

/** The gutter's top: a spinner while it runs, `[*]` while queued, Run when selected, else `[n]`. */
@Composable
private fun CellRunButton(controller: WorkbenchController, session: NotebookSession, cell: NotebookCell, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    when {
        cell.type != CellType.Code -> Unit
        cell.running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        cell.queued -> Text(
            "[*]",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            modifier = Modifier.semantics { contentDescription = "Cell queued" },
        )
        selected -> Tooltip("Run cell") { IconButton(onClick = { controller.runCell(session, cell) }, modifier = Modifier.size(32.dp).noKeyFocus()) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = "Run cell", tint = colors.primary)
        } }
        else -> Text(
            "[${cell.executionCount ?: " "}]",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = if (cell.executionCount != null) "Execution count ${cell.executionCount}" else "Not run yet"
            },
        )
    }
}

/**
 * How the cell's run went (VS Code's mark under a cell): a live timer while it runs, "queued"
 * while it waits, then a check or a cross with the run time. A tap says when it finished.
 */
@Composable
private fun CellRunStatus(controller: WorkbenchController, session: NotebookSession, cell: NotebookCell, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall
    val run = cell.lastRun
    when {
        cell.running -> {
            val started = cell.runStartedAt ?: return
            var now by remember(started) { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(started) {
                while (true) {
                    // Tenths for the first minute, then whole seconds are all it shows.
                    delay(if (now - started < 60_000) 100 else 1000)
                    now = System.currentTimeMillis()
                }
            }
            Text(formatRunDuration(now - started), style = style, color = colors.primary, maxLines = 1, modifier = modifier)
        }
        cell.queued -> Text("queued", style = style, color = colors.onSurfaceVariant, maxLines = 1, modifier = modifier)
        run != null -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.clip(RoundedCornerShape(4.dp)).noKeyFocus().clickable { controller.describeCellRun(session, cell) },
        ) {
            val tint = if (run.failed) colors.error else successColor(LocalDarkTheme.current)
            Icon(
                if (run.failed) Icons.Outlined.Close else Icons.Outlined.Check,
                contentDescription = if (run.failed) "Failed" else "Done",
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
            run.millis?.let { Text(formatRunDuration(it), style = style, color = colors.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/** Material has no success role; a green that reads on both the light and the dark surfaces. */
private fun successColor(dark: Boolean): Color = if (dark) Color(0xFF81C995) else Color(0xFF1E8E3E)

/** Height of the sticky cell header. */
private val STICKY_HEADER_HEIGHT = 40.dp

/**
 * A cell taller than the screen keeps a header at the top while it scrolls: Run (or its
 * progress), the first line of its source (a tap goes back to its top), its run status and, when
 * selected, its actions. The next cell's top pushes it out, like a sticky list header.
 */
@Composable
private fun StickyCellHeader(controller: WorkbenchController, session: NotebookSession, listState: LazyListState) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val headerPx = with(density) { STICKY_HEADER_HEIGHT.roundToPx() }
    val sticky by remember(listState, headerPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            stickyCell(
                info.visibleItemsInfo.map { StickyItem(it.index, it.offset, it.size) },
                info.viewportStartOffset,
                session.notebook.cells.size,
                headerPx,
            )
        }
    }
    val (index, offset) = sticky ?: return
    val cell = session.notebook.cells.getOrNull(index) ?: return
    val selected = session.selectedCell == cell.id
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxWidth().offset { IntOffset(0, offset) },
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            color = colors.surfaceContainerHigh,
            shadowElevation = 3.dp,
            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
            modifier = Modifier.widthIn(max = 1100.dp).fillMaxWidth().padding(horizontal = 12.dp).height(STICKY_HEADER_HEIGHT),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(CELL_GUTTER), contentAlignment = Alignment.Center) {
                    CellRunButton(controller, session, cell, selected = cell.type == CellType.Code)
                }
                Text(
                    cellHeadline(cell),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = if (cell.type == CellType.Code) FontFamily.Monospace else null,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .noKeyFocus()
                        .clickable {
                            session.selectedCell = cell.id
                            scope.launch { listState.animateScrollToItem(index) }
                        }
                        .padding(vertical = 10.dp),
                )
                if (cell.type == CellType.Code && controller.settings.cellStatistics) {
                    CellRunStatus(controller, session, cell, Modifier.padding(horizontal = 8.dp))
                }
                if (selected) CellActions(controller, session, cell)
            }
        }
    }
}

/** A cell's first non-blank line, without Markdown's heading marks. */
/** A notebook outline row: a Markdown heading ([depth] from its level) or a code cell under the heading before it. */
internal data class NotebookOutlineEntry(val cellId: String, val title: String, val depth: Int, val code: Boolean)

internal fun notebookOutline(cells: List<NotebookCell>, codeCells: Boolean): List<NotebookOutlineEntry> {
    val entries = mutableListOf<NotebookOutlineEntry>()
    var level = 0
    cells.forEach { cell ->
        if (cell.type == CellType.Markdown) {
            var fenced = false
            cell.source.lineSequence().forEach { line ->
                if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) fenced = !fenced
                val heading = if (fenced) null else Regex("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$").find(line)
                if (heading != null) {
                    level = heading.groupValues[1].length
                    entries += NotebookOutlineEntry(cell.id, heading.groupValues[2], level - 1, code = false)
                }
            }
        } else if (codeCells && cell.type == CellType.Code) {
            entries += NotebookOutlineEntry(cell.id, cellHeadline(cell).ifBlank { "Empty code cell" }, level, code = true)
        }
    }
    return entries
}

internal fun cellHeadline(cell: NotebookCell): String {
    val line = cell.source.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return "Empty cell"
    return if (cell.type == CellType.Markdown) line.trimStart('#', ' ') else line
}

@Composable
private fun CellSourceEditor(
    controller: WorkbenchController,
    session: NotebookSession,
    cell: NotebookCell,
    dark: Boolean,
    selected: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val palette = syntaxPalette(controller.settings.syntaxColors, colors, dark)
    val baseHighlighter = remember(palette, cell.type) {
        if (cell.type == CellType.Code) PythonHighlighter(palette) else VisualTransformation.None
    }
    var field by remember(cell.id) { mutableStateOf(TextFieldValue(cell.source)) }
    var layout by remember(cell.id) { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember(cell.id) { mutableStateOf(false) }
    // Selecting a cell (Shift+Enter, + Code, a tap) puts the keyboard in it, as in Jupyter's edit mode.
    LaunchedEffect(selected, session.focusRequest) {
        if (!selected) return@LaunchedEffect
        // Find selected this cell: the keys stay in the find box.
        if (session.keepFocusOnSelect) {
            session.keepFocusOnSelect = false
            return@LaunchedEffect
        }
        session.caretAtEnd?.let { atEnd ->
            // Land on the first row (coming from above) or the last one (from below), at the
            // horizontal position the cursor had, so Up/Down feel like one continuous editor.
            val goalX = session.caretGoalX
            if (goalX != null && layout == null) withFrameNanos { }
            val current = layout?.takeIf { it.layoutInput.text.length == field.text.length }
            val offset = if (goalX != null && current != null) {
                val row = if (atEnd) current.lineCount - 1 else 0
                current.getOffsetForPosition(Offset(goalX, (current.getLineTop(row) + current.getLineBottom(row)) / 2))
            } else if (atEnd) field.text.length else 0
            // Set directly rather than through apply(), so the sticky column survives the move.
            field = field.copy(selection = TextRange(offset))
            session.caretAtEnd = null
            session.caretGoalX = null
        }
        // The request can land before the field is attached (the list is still moving the cell
        // into view, or the previous cell's editor is being removed), and Compose then hands focus
        // to something else, such as the activity bar. Keep asking for a few frames.
        repeat(10) {
            if (focused) return@LaunchedEffect
            runCatching { focus.requestFocus() }
            withFrameNanos { }
        }
        if (!focused && Log.isLoggable(NOTEBOOK_LOG_TAG, Log.DEBUG)) {
            Log.d(NOTEBOOK_LOG_TAG, "cell=${session.notebook.cells.indexOf(cell)} could not take focus")
        }
    }
    LaunchedEffect(field.selection, field.text.length) {
        if (Log.isLoggable(NOTEBOOK_LOG_TAG, Log.DEBUG)) {
            val row = visualRow(layout, field.text, field.selection.end)
            Log.d(NOTEBOOK_LOG_TAG, "cell=${session.notebook.cells.indexOf(cell)} sel=${field.selection.start}-${field.selection.end} row=$row/${visualRowCount(layout, field.text)} len=${field.text.length}")
        }
    }
    fun apply(value: TextFieldValue) {
        // Ctrl or Alt latched on the key bar: the typed character is a shortcut, not text.
        if (KeyBar.holdsTyping) typedCharacter(field, value)?.let { if (KeyBar.typed(it.toString())) return }
        session.stickyGoalX = null
        val textChanged = value.text != cell.source
        field = value
        if (textChanged) {
            cell.source = value.text
            controller.onCellEdited(session)
        }
        controller.onCellInput(session, cell, value, textChanged)
    }
    // Go to definition or a problem's row: the caret lands there.
    LaunchedEffect(session.cellCaretRequest) {
        val (cellId, offset) = session.cellCaretRequest ?: return@LaunchedEffect
        if (cellId != cell.id) return@LaunchedEffect
        field = field.copy(selection = TextRange(offset.coerceIn(0, field.text.length)))
        session.cellCaret = offset.coerceIn(0, field.text.length)
        session.cellCaretRequest = null
    }
    // Find's current match in this cell becomes its selection, so closing find leaves it selected.
    LaunchedEffect(session.findReveal) {
        val (cellId, range) = session.findCurrentMatch ?: return@LaunchedEffect
        if (cellId == cell.id && range.last < field.text.length) field = field.copy(selection = TextRange(range.first, range.last + 1))
    }
    val findMatches = session.findMatches[cell.id].orEmpty()
    val findCurrent = session.findCurrentMatch?.takeIf { it.first == cell.id }?.second
    val highlighter = if (findMatches.isEmpty()) baseHighlighter else {
        FindHighlights(baseHighlighter, findMatches, findCurrent, colors.tertiary.copy(alpha = 0.28f), colors.tertiary.copy(alpha = 0.6f))
    }
    val ghost = session.cellSuggestion?.takeIf { it.cellId == cell.id && field.selection.collapsed }
        ?.let { cellGhostText(field.text, field.selection.start, it) }
    val shownTransformation = if (ghost != null) {
        GhostTextTransformation(highlighter, field.selection.start, ghost, colors.onSurfaceVariant.copy(alpha = 0.6f))
    } else {
        highlighter
    }
    // Follow changes made elsewhere (cell type toggles, reloads) without losing the cursor.
    if (field.text != cell.source) field = TextFieldValue(cell.source, TextRange(minOf(field.selection.end, cell.source.length)))
    Box {
        BasicTextField(
            value = field,
            onValueChange = ::apply,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = controller.shownFontSize.sp,
                color = colors.onSurface,
            ),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = shownTransformation,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(12.dp)
                .semantics {
                    val label = when (cell.type) {
                        CellType.Code -> "Code"
                        CellType.Markdown -> "Markdown"
                        CellType.Raw -> "Raw"
                    }
                    contentDescription = "$label cell ${session.notebook.cells.indexOf(cell).coerceAtLeast(0) + 1} editor"
                }
                .cellProblemSquiggles(session.cellProblems[cell.id].orEmpty(), layout?.takeIf { it.layoutInput.text.length == field.text.length })
                .cellHover(controller, session, cell) { layout?.takeIf { it.layoutInput.text.length == field.text.length } }
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && Log.isLoggable(NOTEBOOK_LOG_TAG, Log.DEBUG)) {
                        Log.d(
                            NOTEBOOK_LOG_TAG,
                            "cell=${session.notebook.cells.indexOf(cell)} key ${event.key} shift=${event.isShiftPressed} " +
                                "ctrl=${event.isCtrlPressed} alt=${event.isAltPressed} sel=${field.selection.start}-${field.selection.end}",
                        )
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        // The completion popup takes Up/Down/Enter/Tab/Esc while it is open.
                        val completion = session.cellCompletion?.takeIf { it.cellId == cell.id }
                        val visible = completion?.visibleItems(field)
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
                                    completion.accept(field)?.let(::apply)
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
                            controller.requestCellCompletion(session, cell, field)
                            return@onPreviewKeyEvent true
                        }
                        if (event.key == Key.DirectionRight && event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed) {
                            controller.acceptCellSuggestionWord(session, cell, field)?.let { accepted ->
                                apply(accepted)
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed) {
                        return@onPreviewKeyEvent false
                    }
                    val settings = controller.settings
                    if (event.key == Key.Tab && !event.isShiftPressed) {
                        controller.acceptCellSuggestion(session, cell, field)?.let { accepted ->
                            apply(accepted)
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (event.key == Key.Escape && session.cellSuggestion?.cellId == cell.id) {
                        session.cellSuggestion = null
                        return@onPreviewKeyEvent true
                    }
                    // Jupyter: Tab after a name or a dot completes (with a kernel running); elsewhere it indents.
                    if (event.key == Key.Tab && !event.isShiftPressed && cell.type == CellType.Code && settings.notebookTabCompletion &&
                        session.kernelName != null && tabCompletes(field)
                    ) {
                        controller.requestCellCompletion(session, cell, field)
                        return@onPreviewKeyEvent true
                    }
                    if (event.key == Key.Tab) {
                        apply(
                            if (event.isShiftPressed) CellTextEdits.outdent(field, settings.tabSize)
                            else CellTextEdits.indent(field, settings.tabSize, settings.insertSpaces),
                        )
                        return@onPreviewKeyEvent true
                    }
                    if (event.isShiftPressed) return@onPreviewKeyEvent false
                    val selection = field.selection
                    val current = layout?.takeIf { it.layoutInput.text.length == field.text.length }
                    val goalX = session.stickyGoalX ?: current?.getHorizontalPosition(selection.end, usePrimaryDirection = true)
                    // Up/Down by visual row aiming at the sticky column, as in VS Code; past the first or
                    // last row they move to the neighbouring cell (Jupyter), still aiming at that column.
                    fun moveVertically(delta: Int): Boolean {
                        if (!selection.collapsed) return false
                        if (current == null || goalX == null) {
                            // No layout yet (an empty cell, a Markdown cell just opened): go by lines.
                            val row = field.text.substring(0, selection.end).count { it == '\n' }
                            val rows = field.text.count { it == '\n' } + 1
                            return row + delta !in 0 until rows && controller.selectAdjacentCell(session, cell, delta, goalX)
                        }
                        val target = current.getLineForOffset(selection.end) + delta
                        if (target !in 0 until current.lineCount) {
                            val moved = controller.selectAdjacentCell(session, cell, delta, goalX)
                            if (moved) session.stickyGoalX = goalX
                            return moved
                        }
                        val y = (current.getLineTop(target) + current.getLineBottom(target)) / 2
                        field = field.copy(selection = TextRange(current.getOffsetForPosition(Offset(goalX, y))))
                        session.stickyGoalX = goalX
                        return true
                    }
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> cell.type == CellType.Code && settings.autoIndent &&
                            true.also { apply(CellTextEdits.newline(field, settings.tabSize, settings.insertSpaces)) }
                        Key.Backspace -> settings.useTabStops &&
                            CellTextEdits.backspace(field, settings.tabSize)?.also { apply(it) } != null
                        Key.DirectionUp -> moveVertically(-1)
                        Key.DirectionDown -> moveVertically(1)
                        // At the edges Left/Right would hand focus to whatever is beside the cell; keep it here.
                        Key.DirectionLeft -> selection.collapsed && selection.start == 0
                        Key.DirectionRight -> selection.collapsed && selection.end == field.text.length
                        else -> false
                    }
                }
                // Keys the text field didn't use bubble up here; without this, an arrow at an edge (or
                // with a modifier the handlers above skip) moved Compose focus out of the notebook.
                .onKeyEvent { event ->
                    val kept = event.type == KeyEventType.KeyDown && event.key in cellKeepsKeys
                    if (kept && Log.isLoggable(NOTEBOOK_LOG_TAG, Log.DEBUG)) {
                        Log.d(NOTEBOOK_LOG_TAG, "cell=${session.notebook.cells.indexOf(cell)} kept unhandled ${event.key}")
                    }
                    kept
                }
                .onFocusChanged { state ->
                    if (state.isFocused != focused && Log.isLoggable(NOTEBOOK_LOG_TAG, Log.DEBUG)) {
                        Log.d(NOTEBOOK_LOG_TAG, "cell=${session.notebook.cells.indexOf(cell)} focus=${state.isFocused}")
                    }
                    focused = state.isFocused
                    if (state.isFocused) {
                        session.selectedCell = cell.id
                        controller.focusArea = FocusArea.Notebook
                    } else if (controller.focusArea == FocusArea.Notebook) {
                        controller.focusArea = FocusArea.Other
                    }
                },
        )
        session.cellHover?.takeIf { it.cellId == cell.id }?.let { hover ->
            layout?.takeIf { it.layoutInput.text.length == field.text.length }?.let { shown ->
                CellHoverCard(controller, session, hover, shown)
            }
        }
        val completion = session.cellCompletion?.takeIf { it.cellId == cell.id }
        val items = completion?.visibleItems(field)
        if (completion != null && items != null) {
            val density = LocalDensity.current
            // Under the cell, at the caret's column; it never takes focus, so typing goes on in the cell.
            val caretX = layout?.takeIf { it.layoutInput.text.length == field.text.length }
                ?.getCursorRect(field.selection.start.coerceIn(0, field.text.length))?.left ?: 0f
            DropdownMenu(
                expanded = true,
                onDismissRequest = { session.cellCompletion = null },
                offset = DpOffset(with(density) { caretX.toDp() } + 12.dp, 0.dp),
                properties = PopupProperties(focusable = false),
                modifier = Modifier.heightIn(max = 280.dp),
            ) {
                items.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = { Text(item, fontFamily = FontFamily.Monospace, maxLines = 1) },
                        onClick = {
                            completion.accept(field, index)?.let(::apply)
                            session.cellCompletion = null
                            runCatching { focus.requestFocus() }
                        },
                        modifier = if (index == completion.selected.coerceIn(items.indices)) {
                            Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                        } else Modifier,
                    )
                }
            }
        }
    }
}

private val ansiPattern = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

internal fun String.stripAnsi(): String = replace(ansiPattern, "")

/** Lightweight Python colouring for notebook cells, in the code editor's [SyntaxPalette]. */
/** Find's matches in a cell drawn behind its text, the current one stronger, over [base]'s colours. */
private class FindHighlights(
    private val base: VisualTransformation,
    private val matches: List<IntRange>,
    private val current: IntRange?,
    private val matchColor: Color,
    private val currentColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = base.filter(text)
        val styled = buildAnnotatedString {
            append(transformed.text)
            val length = transformed.text.length
            matches.forEach { range ->
                val start = transformed.offsetMapping.originalToTransformed(range.first.coerceIn(0, text.length))
                val end = transformed.offsetMapping.originalToTransformed((range.last + 1).coerceIn(0, text.length))
                if (start < end && end <= length) addStyle(SpanStyle(background = if (range == current) currentColor else matchColor), start, end)
            }
        }
        return TransformedText(styled, transformed.offsetMapping)
    }

    override fun equals(other: Any?): Boolean =
        other is FindHighlights && other.base == base && other.matches == matches && other.current == current

    override fun hashCode(): Int = matches.hashCode() * 31 + (current?.hashCode() ?: 0)
}

internal class PythonHighlighter(palette: SyntaxPalette) : VisualTransformation {
    private val keyword = palette.keyword
    private val string = palette.string
    private val comment = palette.comment
    private val number = palette.number
    private val builtin = palette.function
    private val decorator = palette.type
    private val token = Regex(
        """(#[^\n]*)|("{3}[\s\S]*?"{3}|'{3}[\s\S]*?'{3}|[rbfuRBFU]{0,2}"(?:\\.|[^"\\\n])*"|[rbfuRBFU]{0,2}'(?:\\.|[^'\\\n])*')|(@[\w.]+)|\b(\d+(?:\.\d+)?(?:[eE][+-]?\d+)?j?)\b|\b(False|None|True|and|as|assert|async|await|break|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|nonlocal|not|or|pass|raise|return|try|while|with|yield|match|case)\b|\b(print|len|range|list|dict|set|tuple|str|int|float|bool|open|enumerate|zip|map|filter|sum|min|max|sorted|isinstance|super|type|self)\b""",
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val styled = buildAnnotatedString {
            append(source)
            token.findAll(source).forEach { match ->
                val color = when {
                    match.groups[1] != null -> comment
                    match.groups[2] != null -> string
                    match.groups[3] != null -> decorator
                    match.groups[4] != null -> number
                    match.groups[5] != null -> keyword
                    else -> builtin
                }
                addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
