package com.avfstudio.nativeide.workbench

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avfstudio.nativeide.notebook.NotebookCell
import com.avfstudio.nativeide.notebook.NotebookDocument
import com.avfstudio.nativeide.notebook.OutputPresentation
import com.avfstudio.nativeide.notebook.OutputTable
import com.avfstudio.nativeide.notebook.OutputView
import com.avfstudio.nativeide.notebook.SortDirection
import com.avfstudio.nativeide.notebook.TableCell
import com.avfstudio.nativeide.notebook.decodeHtmlText
import com.avfstudio.nativeide.notebook.isEllipsisRow
import com.avfstudio.nativeide.notebook.parseDataFrameHtml
import com.avfstudio.nativeide.notebook.sortedBody
import com.avfstudio.nativeide.notebook.tableNumber
import com.avfstudio.nativeide.notebook.toTsv
import com.google.gson.JsonObject

/** The cells' gutter width; outputs line up with the source beside it. */
internal val CELL_GUTTER = 56.dp

/**
 * A code cell's outputs. The gutter beside them folds them away (saved with the notebook) and
 * opens each output's menu (copy, which view to draw it with, clear), shown on the selected cell
 * and wherever a mouse or touchpad pointer rests over an output.
 */
@Composable
internal fun CellOutputs(controller: WorkbenchController, session: NotebookSession, cell: NotebookCell, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    if (cell.outputs.isEmpty()) return
    if (cell.outputsHidden) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutputsFoldButton(cell, hidden = true) { controller.setOutputsHidden(session, cell, false) }
            Text(
                if (cell.outputs.size == 1) "1 output hidden" else "${cell.outputs.size} outputs hidden",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceContainerLow)
                    .noKeyFocus()
                    .clickable { controller.setOutputsHidden(session, cell, false) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        return
    }
    cell.outputs.forEachIndexed { index, output ->
        // A script output runs inside the HTML output before it (Bokeh's div, then its embed code).
        if (attachedToPrevious(cell.outputs, index)) return@forEachIndexed
        key(index) {
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            // Kept here: the open menu takes the pointer, which ends the hover that showed its button.
            var menuOpen by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().hoverable(hover)) {
                Row(Modifier.width(CELL_GUTTER).padding(top = 4.dp)) {
                    Box(Modifier.size(28.dp)) {
                        if (index == 0) OutputsFoldButton(cell, hidden = false) { controller.setOutputsHidden(session, cell, true) }
                    }
                    if (selected || hovered || menuOpen) {
                        OutputMenuButton(controller, session, cell, index, output, menuOpen) { menuOpen = it }
                    }
                }
                Box(Modifier.weight(1f)) { CellOutput(controller, session, cell, index, output, attachedScript(cell.outputs, index)) }
            }
        }
    }
}

@Composable
private fun OutputsFoldButton(cell: NotebookCell, hidden: Boolean, onClick: () -> Unit) {
    Tooltip(if (hidden) "Show outputs" else "Hide outputs") { IconButton(onClick = onClick, modifier = Modifier.size(28.dp).noKeyFocus()) {
        Icon(
            if (hidden) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            contentDescription = if (hidden) "Show outputs" else "Hide outputs",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    } }
}

@Composable
private fun OutputMenuButton(
    controller: WorkbenchController,
    session: NotebookSession,
    cell: NotebookCell,
    index: Int,
    output: JsonObject,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
) {
    val presentation = cell.outputPresentation[index] ?: OutputPresentation()
    val views = remember(output) { outputViews(output) }
    val shown = shownView(views, presentation, controller.settings, remember(output) { outputHtmlScripted(output) })
    Box {
        Tooltip("Output actions") { IconButton(onClick = { onOpenChange(true) }, modifier = Modifier.size(28.dp).noKeyFocus()) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Output actions", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        } }
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            val text = outputCopyText(output, shown, presentation)
            DropdownMenuItem(
                text = { Text(if (shown == OutputView.Table) "Copy table (tab-separated)" else "Copy output") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                enabled = text != null,
                onClick = { onOpenChange(false); text?.let { controller.copyOutputText(it) } },
            )
            if (views.size > 1) {
                HorizontalDivider()
                views.forEach { view ->
                    DropdownMenuItem(
                        text = { Text("Show as ${view.label}") },
                        leadingIcon = { if (view == shown) Icon(Icons.Outlined.Check, contentDescription = "Shown") },
                        onClick = {
                            onOpenChange(false)
                            cell.outputPresentation[index] = presentation.copy(view = view)
                        },
                    )
                }
            }
            if (shown == OutputView.Image || shown == OutputView.Svg) {
                DropdownMenuItem(
                    text = { Text("Open in plot viewer") },
                    leadingIcon = { Icon(Icons.Outlined.OpenInFull, contentDescription = null) },
                    onClick = { onOpenChange(false); controller.openPlotViewer(session, cell, index) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Clear this output") }, onClick = { onOpenChange(false); controller.clearOutput(session, cell, index) })
            DropdownMenuItem(text = { Text("Clear cell outputs") }, onClick = { onOpenChange(false); controller.clearCellOutputs(session, cell) })
        }
    }
}

/** The views a display output can be drawn with, in the order the default is picked. */
internal fun outputViews(output: JsonObject): List<OutputView> {
    val data = output.getAsJsonObject("data") ?: return emptyList()
    val html = data.get("text/html")?.let(NotebookDocument::multiline)
    return buildList {
        if (data.get(PLOTLY_MIME)?.isJsonObject == true) add(OutputView.Plotly)
        if (vegaSpec(data) != null) add(OutputView.Vega)
        if (data.has("image/png") || data.has("image/jpeg")) add(OutputView.Image)
        if (data.has(SVG_MIME)) add(OutputView.Svg)
        if (html != null && parseDataFrameHtml(html) != null) add(OutputView.Table)
        if (html != null) add(OutputView.Html)
        if (data.has(LATEX_MIME)) add(OutputView.Latex)
        if (data.has(JSON_MIME)) add(OutputView.Json)
        if (data.has("text/markdown")) add(OutputView.Markdown)
        if (data.has("text/plain")) add(OutputView.Text)
    }
}

/**
 * The view picked from the menu, else the first one Settings allow; null when Settings allow
 * none. HTML that runs scripts ([scripted]) needs "Outputs with scripts" as well.
 */
internal fun shownView(views: List<OutputView>, presentation: OutputPresentation, settings: WorkbenchSettings, scripted: Boolean = false): OutputView? =
    presentation.view?.takeIf { it in views } ?: views.firstOrNull { view ->
        when (view) {
            OutputView.Plotly -> settings.renderPlotly
            OutputView.Vega -> settings.scriptOutputs
            OutputView.Latex -> settings.renderLatex
            OutputView.Table -> settings.nativeTables
            OutputView.Html -> settings.renderHtmlOutputs && (!scripted || settings.scriptOutputs)
            else -> true
        }
    }

/** Whether [output]'s HTML runs scripts (Bokeh, folium, Altair's html renderer). */
internal fun outputHtmlScripted(output: JsonObject): Boolean =
    output.getAsJsonObject("data")?.get("text/html")?.let { htmlNeedsScript(NotebookDocument.multiline(it)) } == true

/** What Copy puts on the clipboard: the table as TSV (in its sort order), or the output's text. */
internal fun outputCopyText(output: JsonObject, view: OutputView?, presentation: OutputPresentation): String? {
    when (output.get("output_type")?.asString) {
        "stream" -> return NotebookDocument.multiline(output.get("text")).stripAnsi().trimEnd('\n')
        "error" -> return errorText(output)
    }
    val data = output.getAsJsonObject("data") ?: return null
    fun mime(name: String) = data.get(name)?.let(NotebookDocument::multiline)
    return when (view) {
        OutputView.Table -> mime("text/html")?.let(::parseDataFrameHtml)?.let { table ->
            val column = presentation.sortColumn
            table.toTsv(if (column != null) table.sortedBody(column, presentation.sortDirection) else table.body)
        }
        OutputView.Markdown -> mime("text/markdown")
        OutputView.Svg -> mime(SVG_MIME)
        OutputView.Latex -> mime(LATEX_MIME)
        OutputView.Json -> data.get(JSON_MIME)?.let(::prettyJson)
        OutputView.Html -> mime("text/plain") ?: mime("text/html")?.replace(Regex("<[^>]*>"), " ")?.let(::decodeHtmlText)
        else -> mime("text/plain")?.stripAnsi()
    }
}

/** The error as IPython printed it: its traceback with colours, or the name and message alone. */
private fun errorDisplayText(output: JsonObject): String {
    val traceback = output.getAsJsonArray("traceback")?.map { it.asString }.orEmpty()
    return if (traceback.isNotEmpty()) traceback.joinToString("\n") else errorText(output)
}

private fun errorText(output: JsonObject): String = buildString {
    append(output.get("ename")?.asString.orEmpty())
    append(": ")
    appendLine(output.get("evalue")?.asString.orEmpty())
    output.getAsJsonArray("traceback")?.forEach { appendLine(it.asString.stripAnsi()) }
}.trimEnd()

@Composable
internal fun CellOutput(
    controller: WorkbenchController,
    session: NotebookSession,
    cell: NotebookCell,
    index: Int,
    output: JsonObject,
    attachedScript: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val settings = controller.settings
    val presentation = cell.outputPresentation[index] ?: OutputPresentation()
    val modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp)
    val lineLimit = settings.outputLineLimit
    fun expand(expanded: Boolean) {
        cell.outputPresentation[index] = presentation.copy(expanded = expanded)
    }
    when (output.get("output_type")?.asString) {
        "stream" -> {
            val stderr = output.get("name")?.asString == "stderr"
            OutputText(
                NotebookDocument.multiline(output.get("text")).trimEnd('\n'),
                if (stderr) colors.error else colors.onSurface,
                modifier.then(if (stderr) Modifier.background(colors.errorContainer.copy(alpha = 0.3f), RoundedCornerShape(6.dp)) else Modifier),
                lineLimit, presentation.expanded, follow = cell.running, onExpand = ::expand,
                onTarget = { controller.openTracebackTarget(session, it) },
            )
        }
        "error" -> {
            // IPython colours its tracebacks; without colours the whole error is red.
            val traceback = errorDisplayText(output)
            OutputText(
                traceback,
                if ('\u001b' in traceback) colors.onSurface else colors.error,
                modifier.background(colors.errorContainer.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
                lineLimit, presentation.expanded, follow = false, onExpand = ::expand,
                onTarget = { controller.openTracebackTarget(session, it) },
            )
        }
        "execute_result", "display_data" -> {
            val data = output.getAsJsonObject("data") ?: return
            val views = remember(output) { outputViews(output) }
            val scripted = remember(output, attachedScript) { attachedScript != null || outputHtmlScripted(output) }
            val image = data.get("image/png") ?: data.get("image/jpeg")
            val bitmap = remember(image) {
                image?.let {
                    runCatching {
                        val bytes = Base64.decode(NotebookDocument.multiline(it), Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
            }
            fun mime(name: String) = NotebookDocument.multiline(data.get(name))
            val dark = LocalDarkTheme.current
            // "Open plots with a tap": the plot viewer, to zoom, browse, save and share.
            val openPlot = if (settings.plotViewerOnTap) {
                Modifier.noKeyFocus().clickable { controller.openPlotViewer(session, cell, index) }
            } else {
                Modifier
            }
            if (data.has(WIDGET_VIEW_MIME)) {
                val modelId = data.get(WIDGET_VIEW_MIME)?.takeIf { it.isJsonObject }?.asJsonObject?.get("model_id")?.asString
                val model = modelId?.let { session.widgets.models[it] }
                if (model != null && settings.liveWidgets) {
                    WidgetView(controller, session, model, modifier)
                    return
                }
                // Not live: the kernel that made it is gone (a reopened notebook), or Settings say text.
                Column(modifier) {
                    if (data.has("text/plain")) OutputText(mime("text/plain").stripAnsi(), colors.onSurface, Modifier)
                    Text(
                        if (settings.liveWidgets) "Interactive widget (ipywidgets): run the cell to use it" else "Interactive widget (ipywidgets): drawn live when Settings → Notebooks → Interactive widgets is on",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    )
                }
                return
            }
            when (shownView(views, presentation, settings, scripted)) {
                OutputView.Plotly -> PlotlyOutput(data.getAsJsonObject(PLOTLY_MIME), dark, modifier)
                OutputView.Vega -> {
                    val page = remember(output, dark) { vegaSpec(data)?.let { (kind, version, spec) -> vegaPage(kind, version, spec, dark) } }
                    if (page != null) ScriptOutput(page, modifier, minHeightDp = 120) else OutputText(mime("text/plain"), colors.onSurface, modifier)
                }
                OutputView.Image -> if (bitmap != null) {
                    Image(
                        bitmap,
                        contentDescription = "Cell output image",
                        contentScale = ContentScale.Fit,
                        modifier = modifier.heightIn(max = 520.dp).background(Color.White, RoundedCornerShape(6.dp)).then(openPlot),
                    )
                } else {
                    OutputText("[image that could not be decoded]", colors.onSurfaceVariant, modifier)
                }
                OutputView.Table -> {
                    val table = remember(output) { parseDataFrameHtml(mime("text/html")) }
                    if (table != null) {
                        OutputTableView(table, presentation, modifier) { cell.outputPresentation[index] = it }
                    } else {
                        OutputText(mime("text/plain"), colors.onSurface, modifier)
                    }
                }
                OutputView.Html -> if (scripted && settings.scriptOutputs) {
                    val html = mime("text/html") + attachedScript?.let { "<script>${it.replace("</", "<\\/")}</script>" }.orEmpty()
                    // The cell that ran output_notebook() carries the loader itself.
                    val loader = remember(output, attachedScript) {
                        if ("Bokeh" in html && cell.outputs.none { it.getAsJsonObject("data")?.has(BOKEH_LOAD_MIME) == true }) bokehLoader(session) else null
                    }
                    val page = remember(output, dark, loader) { scriptHtmlPage(html, dark, loader) }
                    ScriptOutput(page, modifier)
                } else {
                    HtmlOutput(mime("text/html"), dark, modifier)
                }
                OutputView.Svg -> SvgOutput(mime(SVG_MIME), modifier.then(openPlot)) { OutputText(mime("text/plain"), colors.onSurface, modifier) }
                OutputView.Latex -> {
                    val page = remember(output, dark) { latexPage(mime(LATEX_MIME), dark) }
                    ScriptOutput(page, modifier, minHeightDp = 24)
                }
                OutputView.Json -> JsonTree(data.get(JSON_MIME), presentation, modifier) { cell.outputPresentation[index] = it }
                OutputView.Markdown -> {
                    val source = remember(session.notebook.path) { controller.markdownSource(session.notebook.path, null) }
                    NotebookMarkdown(controller, mime("text/markdown"), source, modifier)
                }
                OutputView.Text -> OutputText(mime("text/plain"), colors.onSurface, modifier, lineLimit, presentation.expanded, follow = false, onExpand = ::expand)
                null -> OutputText("[${data.keySet().joinToString()} output]", colors.onSurfaceVariant, modifier)
            }
        }
    }
}

/**
 * Monospace output text. Past [lineLimit] lines it scrolls in a box of that height (VS Code's
 * `notebook.output.textLineLimit`) until expanded; while the cell runs ([follow]) the box stays
 * at the newest line unless scrolled up.
 */
@Composable
private fun OutputText(
    text: String,
    color: Color,
    modifier: Modifier,
    lineLimit: Int = 0,
    expanded: Boolean = true,
    follow: Boolean = false,
    onExpand: ((Boolean) -> Unit)? = null,
    /** Traceback references (Cell In[3], line 5; File x.py:12) become links to it. */
    onTarget: ((TracebackTarget) -> Unit)? = null,
) {
    val shown = text.takeLast(200_000)
    val dark = LocalDarkTheme.current
    val linkColor = MaterialTheme.colorScheme.primary
    // ANSI colours as the terminal shows them (VS Code renders them in outputs too).
    val styled = remember(shown, dark, color, onTarget != null) { ansiAnnotated(shown, terminalPalette(dark), color, onTarget, linkColor) }
    val lines = remember(shown) { shown.count { it == '\n' } + 1 }
    val long = lineLimit > 0 && lines > lineLimit && onExpand != null
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val vertical = rememberScrollState()
    if (long && !expanded) {
        LaunchedEffect(vertical, follow) {
            if (!follow) return@LaunchedEffect
            var lastMax = vertical.maxValue
            vertical.scrollTo(lastMax)
            snapshotFlow { vertical.maxValue }.collect { max ->
                if (vertical.value >= lastMax - 8) vertical.scrollTo(max)
                lastMax = max
            }
        }
    }
    Column(modifier) {
        SelectionContainer {
            val lineHeight = style.lineHeight.takeIf { it.isSp } ?: 16.sp
            val boxed = if (long && !expanded) {
                Modifier.heightIn(max = with(density) { (lineHeight * lineLimit).toDp() } + 16.dp).verticalScroll(vertical)
            } else Modifier
            Text(
                styled,
                fontFamily = FontFamily.Monospace,
                style = style,
                color = color,
                softWrap = false,
                modifier = boxed.horizontalScroll(rememberScrollState()).padding(8.dp),
            )
        }
        if (long) {
            TextButton(onClick = { onExpand?.invoke(!expanded) }, modifier = Modifier.noKeyFocus()) {
                Text(
                    if (expanded) "Scroll in a box of $lineLimit lines" else "Show all ${"%,d".format(lines)} lines",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Rows drawn at most; pandas shows 60 unless told otherwise. */
private const val TABLE_ROW_LIMIT = 2000

/** Rows measured for column widths. */
private const val TABLE_MEASURED_ROWS = 200

/**
 * A DataFrame as a native table: theme colours, header rows that stay while the rows scroll,
 * numbers aligned right, and a tap on a column header sorts by it (ascending, descending, off).
 */
/** The Variables view's data viewer: the notebook's native table, taller, with its own sort state. */
@Composable
internal fun DataViewerTable(table: OutputTable) {
    var presentation by remember(table) { mutableStateOf(OutputPresentation()) }
    OutputTableView(table, presentation, Modifier, maxBodyHeight = 560.dp) { presentation = it }
}

/** The data viewer's table when the kernel sorts (all rows, not just the page): [onSort] asks for a column, or null for none. */
@Composable
internal fun DataViewerTable(table: OutputTable, sortColumn: Int?, ascending: Boolean, maxBodyHeight: Dp, onSort: (Int?, Boolean) -> Unit) {
    val presentation = OutputPresentation(sortColumn = sortColumn, sortDirection = if (ascending) SortDirection.Ascending else SortDirection.Descending)
    OutputTableView(table, presentation, Modifier, maxBodyHeight = maxBodyHeight, sortLocally = false) { next ->
        onSort(next.sortColumn, next.sortDirection == SortDirection.Ascending)
    }
}

@Composable
private fun OutputTableView(
    table: OutputTable,
    presentation: OutputPresentation,
    modifier: Modifier,
    maxBodyHeight: Dp = 420.dp,
    /** False when the rows come sorted (the data viewer's kernel sorts them all). */
    sortLocally: Boolean = true,
    onPresentation: (OutputPresentation) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    val headerStyle = style.copy(fontWeight = FontWeight.SemiBold)
    val sortColumn = presentation.sortColumn?.takeIf { it < table.columnCount }
    val rows = remember(table, sortColumn, presentation.sortDirection) {
        if (sortColumn != null && sortLocally) table.sortedBody(sortColumn, presentation.sortDirection) else table.body
    }
    val widths = columnWidths(table, style, headerStyle)
    val numeric = remember(table) {
        List(table.columnCount) { column ->
            table.body.filterNot(::isEllipsisRow).mapNotNull { it.getOrNull(column) }
                .filter { !it.header && it.text.isNotBlank() }
                .let { cells -> cells.isNotEmpty() && cells.all { tableNumber(it.text) != null || it.text == "NaN" } }
        }
    }
    fun sortBy(column: Int) {
        val next = when {
            sortColumn != column -> presentation.copy(sortColumn = column, sortDirection = SortDirection.Ascending)
            presentation.sortDirection == SortDirection.Ascending -> presentation.copy(sortDirection = SortDirection.Descending)
            else -> presentation.copy(sortColumn = null)
        }
        onPresentation(next)
    }
    Column(modifier) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp)),
        ) {
            SelectionContainer {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    table.head.forEachIndexed { r, line ->
                        Row(Modifier.background(colors.surfaceContainerHigh)) {
                            line.forEachIndexed { column, cell ->
                                val arrow = if (r == table.head.lastIndex && column == sortColumn) {
                                    if (presentation.sortDirection == SortDirection.Ascending) " ▲" else " ▼"
                                } else ""
                                TableCellText(
                                    (if (cell.spanned) "" else cell.text) + arrow,
                                    headerStyle,
                                    colors.onSurface,
                                    widths.getOrElse(column) { 48.dp },
                                    if (numeric.getOrElse(column) { false }) TextAlign.End else TextAlign.Start,
                                    Modifier.noKeyFocus().clickable { sortBy(column) },
                                )
                            }
                        }
                    }
                    Column(Modifier.heightIn(max = maxBodyHeight).verticalScroll(rememberScrollState())) {
                        rows.take(TABLE_ROW_LIMIT).forEachIndexed { r, line ->
                            Row(Modifier.background(if (r % 2 == 0) colors.surface else colors.surfaceContainerLow)) {
                                line.forEachIndexed { column, cell ->
                                    TableCellText(
                                        if (cell.spanned && sortColumn == null) "" else cell.text,
                                        if (cell.header) headerStyle else style,
                                        if (cell.header) colors.onSurfaceVariant else colors.onSurface,
                                        widths.getOrElse(column) { 48.dp },
                                        if (numeric.getOrElse(column) { false } && !cell.header) TextAlign.End else TextAlign.Start,
                                        if (cell.header) Modifier.background(colors.surfaceContainer) else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val notes = listOfNotNull(
            table.caption.takeIf { it.isNotEmpty() },
            "first $TABLE_ROW_LIMIT rows shown".takeIf { rows.size > TABLE_ROW_LIMIT },
            sortColumn?.takeIf { sortLocally }?.let { "sorted by ${table.head.lastOrNull()?.getOrNull(it)?.text?.ifBlank { null } ?: "column ${it + 1}"} (the rows shown)" },
        )
        if (notes.isNotEmpty()) {
            Text(
                notes.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun TableCellText(text: String, style: TextStyle, color: Color, width: Dp, align: TextAlign, modifier: Modifier) {
    Text(
        text,
        style = style,
        color = color,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.width(width).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/** Each column as wide as its widest text (of the first rows), between 40 and 280 dp. */
@Composable
private fun columnWidths(table: OutputTable, style: TextStyle, headerStyle: TextStyle): List<Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(table, style, density) {
        val lines: List<List<TableCell>> = table.head + table.body.take(TABLE_MEASURED_ROWS)
        List(table.columnCount) { column ->
            val widest = lines.withIndex().maxOfOrNull { (row, line) ->
                val head = row < table.head.size
                val cell = line.getOrNull(column) ?: return@maxOfOrNull 0
                // A header spanning several columns doesn't widen the first of them.
                if (cell.text.isEmpty() || (head && (cell.spanned || line.getOrNull(column + 1)?.let { it.spanned && it.text == cell.text } == true))) {
                    return@maxOfOrNull 0
                }
                // Room for the sort arrow beside a header.
                val text = if (head) cell.text + " ▲" else cell.text
                measurer.measure(text, if (cell.header || head) headerStyle else style, maxLines = 1).size.width
            } ?: 0
            with(density) { (widest.toDp() + 17.dp).coerceIn(40.dp, 280.dp) }
        }
    }
}

/** The BokehJS loader `output_notebook()` published most recently in the notebook, if any. */
private fun bokehLoader(session: NotebookSession): String? =
    session.notebook.cells.asReversed().asSequence()
        .flatMap { it.outputs.asReversed().asSequence() }
        .firstNotNullOfOrNull { output -> output.getAsJsonObject("data")?.get(BOKEH_LOAD_MIME)?.let(NotebookDocument::multiline) }

private fun isScriptOnly(output: JsonObject): Boolean {
    val data = output.getAsJsonObject("data") ?: return false
    return data.has("application/javascript") && data.keySet().none { it.startsWith("text/") || it.startsWith("image/") }
}

/** The JavaScript of the output after [index] when that output is only a script (Bokeh's embed code). */
internal fun attachedScript(outputs: List<JsonObject>, index: Int): String? {
    val output = outputs.getOrNull(index) ?: return null
    if (output.getAsJsonObject("data")?.has("text/html") != true) return null
    val next = outputs.getOrNull(index + 1)?.takeIf(::isScriptOnly) ?: return null
    return NotebookDocument.multiline(next.getAsJsonObject("data").get("application/javascript"))
}

/** A script-only output that [attachedScript] runs with the HTML output before it. */
internal fun attachedToPrevious(outputs: List<JsonObject>, index: Int): Boolean =
    index > 0 && attachedScript(outputs, index - 1) != null
