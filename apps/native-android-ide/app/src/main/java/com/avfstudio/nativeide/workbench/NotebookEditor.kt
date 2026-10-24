package com.avfstudio.nativeide.workbench

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.outlined.ClearAll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
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
import com.avfstudio.nativeide.notebook.CellType
import com.avfstudio.nativeide.notebook.NotebookCell
import com.avfstudio.nativeide.notebook.NotebookDocument
import com.google.gson.JsonObject

@Composable
fun NotebookEditor(controller: WorkbenchController, session: NotebookSession) {
    controller.documentRevision
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                modifier = Modifier.noKeyFocus(),
                onClick = { controller.chooseKernel(session) },
                label = { Text(session.kernelName ?: "Select kernel") },
                leadingIcon = { Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            Text(
                session.kernelState,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            if (session.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.weight(1f))
            ToolbarButton(Icons.Outlined.FastForward, "Run all") { controller.runAllCells(session) }
            ToolbarButton(Icons.Outlined.Stop, "Interrupt") { controller.interruptKernel(session) }
            ToolbarButton(Icons.Outlined.RestartAlt, "Restart kernel") { controller.restartKernel(session) }
            ToolbarButton(Icons.Outlined.ClearAll, "Clear outputs") { controller.clearAllOutputs(session) }
            ToolbarButton(Icons.Outlined.Save, "Save") { controller.saveNotebook(session) }
        }
        val listState = rememberLazyListState()
        // Keep the selected cell composed and on screen so it can take focus (Shift+Enter moves on).
        LaunchedEffect(session.selectedCell) {
            val index = session.notebook.cells.indexOfFirst { it.id == session.selectedCell }
            val visible = listState.layoutInfo.visibleItemsInfo
            if (index >= 0 && (visible.none { it.index == index } || visible.last().index == index)) {
                listState.animateScrollToItem(index)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(session.notebook.cells, key = { it.id }) { cell ->
                NotebookCellView(controller, session, cell)
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
    }
}

@Composable
private fun ToolbarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.noKeyFocus()) { Icon(icon, contentDescription = label) }
}

/** The cursor's visual row (wrapped rows count); falls back to logical lines before the first layout. */
private fun visualRow(layout: TextLayoutResult?, text: String, offset: Int): Int =
    layout?.takeIf { it.layoutInput.text.length == text.length }?.getLineForOffset(offset.coerceIn(0, text.length))
        ?: text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' }

private fun visualRowCount(layout: TextLayoutResult?, text: String): Int =
    layout?.takeIf { it.layoutInput.text.length == text.length }?.lineCount ?: (text.count { it == '\n' } + 1)

/** Notebook controls stay tappable but out of the arrow-key path, so Up/Down and Enter can't hit Delete. */
private fun Modifier.noKeyFocus(): Modifier = focusProperties { canFocus = false }

@Composable
private fun NotebookCellView(controller: WorkbenchController, session: NotebookSession, cell: NotebookCell) {
    val colors = MaterialTheme.colorScheme
    val selected = session.selectedCell == cell.id
    val dark = LocalDarkTheme.current
    Row(
        Modifier
            .widthIn(max = 1100.dp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Box(Modifier.width(56.dp).padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
            when {
                cell.type != CellType.Code -> Unit
                // The bridge runs cells one at a time: only the first unfinished one is executing.
                cell.running && session.notebook.cells.firstOrNull { it.running } === cell ->
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                cell.running || cell.queued -> Text(
                    "[*]",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                )
                selected -> IconButton(onClick = { controller.runCell(session, cell) }, modifier = Modifier.size(32.dp).noKeyFocus()) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Run cell", tint = colors.primary)
                }
                else -> Text(
                    "[${cell.executionCount ?: " "}]",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
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
                    MarkdownText(cell.source.ifBlank { "*Empty Markdown cell*" }, Modifier.padding(12.dp))
                } else {
                    CellSourceEditor(controller, session, cell, dark, selected)
                }
            }
            if (selected) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.noKeyFocus()) {
                    TextButton(modifier = Modifier.noKeyFocus(), onClick = {
                        cell.type = if (cell.type == CellType.Code) CellType.Markdown else CellType.Code
                        controller.onCellEdited(session)
                    }) { Text(if (cell.type == CellType.Code) "Markdown" else "Code") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { controller.moveCell(session, cell, -1) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.ArrowUpward, "Move up") }
                    IconButton(onClick = { controller.moveCell(session, cell, 1) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.ArrowDownward, "Move down") }
                    IconButton(onClick = { controller.addCell(session, cell) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.Add, "Insert cell below") }
                    IconButton(onClick = { controller.deleteCell(session, cell) }, modifier = Modifier.noKeyFocus()) { Icon(Icons.Outlined.Delete, "Delete cell") }
                }
            }
            cell.outputs.forEach { output -> CellOutput(output) }
        }
    }
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
    val highlighter = remember(dark, cell.type) {
        if (cell.type == CellType.Code) PythonHighlighter(dark) else VisualTransformation.None
    }
    var field by remember(cell.id) { mutableStateOf(TextFieldValue(cell.source)) }
    var layout by remember(cell.id) { mutableStateOf<TextLayoutResult?>(null) }
    // Selecting a cell (Shift+Enter, + Code, a tap) puts the keyboard in it, as in Jupyter's edit mode.
    LaunchedEffect(selected, session.focusRequest) {
        if (!selected) return@LaunchedEffect
        session.caretAtEnd?.let { atEnd ->
            field = field.copy(selection = TextRange(if (atEnd) field.text.length else 0))
            session.caretAtEnd = null
        }
        runCatching { focus.requestFocus() }
    }
    // Follow changes made elsewhere (cell type toggles, reloads) without losing the cursor.
    if (field.text != cell.source) field = TextFieldValue(cell.source, TextRange(minOf(field.selection.end, cell.source.length)))
    BasicTextField(
        value = field,
        onValueChange = {
            field = it
            if (it.text != cell.source) {
                cell.source = it.text
                controller.onCellEdited(session)
            }
        },
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = controller.settings.editorFontSize.sp,
            color = colors.onSurface,
        ),
        cursorBrush = SolidColor(colors.primary),
        visualTransformation = highlighter,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(12.dp)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isShiftPressed) {
                    return@onPreviewKeyEvent false
                }
                val selection = field.selection
                when (event.key) {
                    // Up on the first visual row / Down on the last one moves between cells, as in Jupyter;
                    // anywhere else the text field moves the cursor (wrapped rows included).
                    Key.DirectionUp -> selection.collapsed && visualRow(layout, field.text, selection.start) == 0 &&
                        controller.selectAdjacentCell(session, cell, -1)
                    Key.DirectionDown -> selection.collapsed &&
                        visualRow(layout, field.text, selection.end) == visualRowCount(layout, field.text) - 1 &&
                        controller.selectAdjacentCell(session, cell, 1)
                    // At the edges Left/Right would hand focus to whatever is beside the cell; keep it here.
                    Key.DirectionLeft -> selection.collapsed && selection.start == 0
                    Key.DirectionRight -> selection.collapsed && selection.end == field.text.length
                    else -> false
                }
            }
            .onFocusChanged { state ->
                if (state.isFocused) {
                    session.selectedCell = cell.id
                    controller.focusArea = FocusArea.Notebook
                } else if (controller.focusArea == FocusArea.Notebook) {
                    controller.focusArea = FocusArea.Other
                }
            },
    )
}

@Composable
private fun CellOutput(output: JsonObject) {
    val colors = MaterialTheme.colorScheme
    val type = output.get("output_type")?.asString
    val modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp)
    when (type) {
        "stream" -> {
            val stderr = output.get("name")?.asString == "stderr"
            OutputText(
                NotebookDocument.multiline(output.get("text")).stripAnsi().trimEnd('\n'),
                if (stderr) colors.error else colors.onSurface,
                modifier.then(if (stderr) Modifier.background(colors.errorContainer.copy(alpha = 0.3f), RoundedCornerShape(6.dp)) else Modifier),
            )
        }
        "error" -> OutputText(
            buildString {
                append(output.get("ename")?.asString.orEmpty())
                append(": ")
                appendLine(output.get("evalue")?.asString.orEmpty())
                output.getAsJsonArray("traceback")?.forEach { appendLine(it.asString.stripAnsi()) }
            }.trimEnd(),
            colors.error,
            modifier.background(colors.errorContainer.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
        )
        "execute_result", "display_data" -> {
            val data = output.getAsJsonObject("data") ?: return
            val image = data.get("image/png") ?: data.get("image/jpeg")
            val bitmap = remember(image) {
                image?.let {
                    runCatching {
                        val bytes = Base64.decode(NotebookDocument.multiline(it), Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
            }
            when {
                bitmap != null -> Image(
                    bitmap,
                    contentDescription = "Cell output image",
                    contentScale = ContentScale.Fit,
                    modifier = modifier.heightIn(max = 520.dp).background(Color.White, RoundedCornerShape(6.dp)),
                )
                data.has("text/markdown") -> MarkdownText(NotebookDocument.multiline(data.get("text/markdown")), modifier)
                data.has("text/plain") -> OutputText(NotebookDocument.multiline(data.get("text/plain")).stripAnsi(), colors.onSurface, modifier)
                else -> OutputText("[${data.keySet().joinToString()} output]", colors.onSurfaceVariant, modifier)
            }
        }
    }
}

@Composable
private fun OutputText(text: String, color: Color, modifier: Modifier) {
    SelectionContainer(modifier) {
        Text(
            text.takeLast(200_000),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
        )
    }
}

/** Headings, bullets, bold, italic and inline code; enough for notebook prose. */
@Composable
private fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        source.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("### ") -> Text(inlineMarkdown(line.drop(4), colors.primary), style = typography.titleSmall)
                line.startsWith("## ") -> Text(inlineMarkdown(line.drop(3), colors.primary), style = typography.titleMedium)
                line.startsWith("# ") -> Text(inlineMarkdown(line.drop(2), colors.primary), style = typography.titleLarge)
                line.startsWith("- ") || line.startsWith("* ") ->
                    Text(inlineMarkdown("•  " + line.drop(2), colors.primary), style = typography.bodyMedium)
                line.isBlank() -> Spacer(Modifier.size(4.dp))
                else -> Text(inlineMarkdown(line, colors.primary), style = typography.bodyMedium)
            }
        }
    }
}

private fun inlineMarkdown(text: String, codeColor: Color): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("""(\*\*[^*]+\*\*|`[^`]+`|\*[^*]+\*)""")
    var index = 0
    pattern.findAll(text).forEach { match ->
        append(text.substring(index, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.removeSurrounding("**")) }
            token.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)) { append(token.removeSurrounding("`")) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.removeSurrounding("*")) }
        }
        index = match.range.last + 1
    }
    append(text.substring(index))
}

private val ansiPattern = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

internal fun String.stripAnsi(): String = replace(ansiPattern, "")

/** Lightweight Python colouring for notebook cells, using the Dark+/Light+ palette. */
private class PythonHighlighter(dark: Boolean) : VisualTransformation {
    private val keyword = Color(if (dark) 0xFFC586C0 else 0xFFAF00DB)
    private val string = Color(if (dark) 0xFFCE9178 else 0xFFA31515)
    private val comment = Color(if (dark) 0xFF6A9955 else 0xFF008000)
    private val number = Color(if (dark) 0xFFB5CEA8 else 0xFF098658)
    private val builtin = Color(if (dark) 0xFFDCDCAA else 0xFF795E26)
    private val decorator = Color(if (dark) 0xFF4EC9B0 else 0xFF267F99)
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
