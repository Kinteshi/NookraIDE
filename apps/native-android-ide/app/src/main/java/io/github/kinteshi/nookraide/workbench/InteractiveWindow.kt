package io.github.kinteshi.nookraide.workbench

import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kinteshi.nookraide.notebook.NotebookCell

/** Lines of a run's code shown before it is cut short. */
private const val RUN_CODE_LINES = 12

/**
 * The Interactive window (VS Code's, for `# %%` cells of a .py file): each run's code and its
 * outputs, with the file's own kernel, and a box at the bottom to run more code in it.
 */
@Composable
fun InteractiveView(controller: WorkbenchController) {
    controller.documentRevision
    val colors = MaterialTheme.colorScheme
    val path = controller.interactivePath
    val session = path?.let(controller::interactiveSession) ?: controller.activeInteractive
    if (path == null || session == null) {
        EmptyState(
            Icons.Outlined.PlayCircleOutline,
            "Nothing has run here yet",
            "Run a # %% cell of a Python file (Ctrl+Enter, or Run Cell above it) to see its output here. " +
                "\"Jupyter: Run Selection/Line in Interactive Window\" runs other code.",
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(path.substringAfterLast('/'), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
            AssistChip(
                modifier = Modifier.noKeyFocus(),
                onClick = { controller.chooseKernel(session) },
                label = { Text(session.kernelName ?: "Select kernel") },
                leadingIcon = { Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            Text(session.kernelState, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp))
            if (session.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Tooltip("Interrupt") { IconButton(onClick = { controller.interruptKernel(session) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.Stop, "Interrupt") } }
            Tooltip("Restart kernel") { IconButton(onClick = { controller.restartKernel(session) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.RestartAlt, "Restart kernel") } }
            Tooltip("Clear all") { IconButton(onClick = { controller.clearInteractive(session) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.DeleteSweep, "Clear all") } }
            Tooltip("Variables") { IconButton(onClick = { controller.showVariables() }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.DataObject, "Variables") } }
            Tooltip("Export as notebook") { IconButton(onClick = { controller.exportInteractive(session) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.SaveAlt, "Export as notebook") } }
            Tooltip("Close and stop the kernel") { IconButton(onClick = { controller.closeInteractive(path) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.Close, "Close and stop the kernel") } }
        }
        val cells = session.notebook.cells
        val listState = rememberLazyListState()
        LaunchedEffect(cells.size) { if (cells.isNotEmpty()) listState.animateScrollToItem(cells.lastIndex) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().editorFontZoom(controller), state = listState) {
            items(cells, key = { it.id }) { cell ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    InteractiveRun(controller, session, cell)
                    CellOutputs(controller, session, cell, selected = false)
                }
            }
        }
        InteractiveInput(controller, path)
    }
}

@Composable
private fun InteractiveRun(controller: WorkbenchController, session: NotebookSession, cell: NotebookCell) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val palette = syntaxPalette(controller.settings.syntaxColors, colors, dark)
    val highlighted = remember(cell.source, palette) {
        val lines = cell.source.lines()
        val shown = if (lines.size > RUN_CODE_LINES) lines.take(RUN_CODE_LINES).joinToString("\n") + "\n…" else cell.source
        PythonHighlighter(palette).filter(AnnotatedString(shown)).text
    }
    val origin = controller.interactiveOrigin(cell)
    Row {
        Box(Modifier.width(CELL_GUTTER).padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
            when {
                cell.running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> Text(
                    if (cell.queued) "[*]" else "[${cell.executionCount ?: " "}]",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (cell.lastRun?.failed == true) colors.error else colors.onSurfaceVariant,
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .background(colors.surfaceContainer, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (origin != null) {
                Text(
                    "${origin.first.substringAfterLast('/')}, line ${origin.second + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    modifier = Modifier.noKeyFocus().clickable { controller.openInteractiveOrigin(cell) }
                        .padding(bottom = 2.dp),
                )
            }
            Text(
                highlighted,
                fontFamily = FontFamily.Monospace,
                fontSize = (controller.shownFontSize * 0.9f).sp,
                color = colors.onSurface,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** Code typed here runs in the same kernel (Shift+Enter or the send button), as VS Code's input box. */
@Composable
private fun InteractiveInput(controller: WorkbenchController, path: String) {
    val colors = MaterialTheme.colorScheme
    var code by remember { mutableStateOf("") }
    fun run() {
        if (code.isBlank()) return
        controller.runInteractive(path, code.trimEnd(), null)
        code = ""
    }
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = code,
            onValueChange = { code = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = (controller.shownFontSize * 0.9f).sp, color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 36.dp, max = 160.dp)
                .background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                // Keys go to this box, not to a notebook that may be open beside it.
                .onFocusChanged { state ->
                    if (state.isFocused) controller.focusArea = FocusArea.Chat
                    else if (controller.focusArea == FocusArea.Chat) controller.focusArea = FocusArea.Other
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isShiftPressed) {
                        run()
                        true
                    } else false
                },
            decorationBox = { field ->
                if (code.isEmpty()) Text("Type code to run · Shift+Enter", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                field()
            },
        )
        Tooltip("Run") { IconButton(onClick = ::run, modifier = Modifier.noKeyFocus()) { Icon(Icons.AutoMirrored.Outlined.Send, "Run") } }
    }
}
