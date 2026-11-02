package com.avfstudio.nativeide.workbench

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.avfstudio.nativeide.notebook.NotebookCell
import com.avfstudio.nativeide.notebook.NotebookDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A plot among a notebook's outputs: a PNG or JPEG (base64) or an SVG (text). */
data class NotebookPlot(val cellId: String, val outputIndex: Int, val mime: String, val data: String, val executionCount: Int?) {
    val extension: String
        get() = when (mime) {
            "image/jpeg" -> "jpg"
            SVG_MIME -> "svg"
            else -> "png"
        }
}

/** Every plot in [cells], in notebook order; an output with both a raster and an SVG counts once (the raster). */
fun notebookPlots(cells: List<NotebookCell>): List<NotebookPlot> = cells.flatMap { cell ->
    cell.outputs.mapIndexedNotNull { index, output ->
        val data = output.getAsJsonObject("data") ?: return@mapIndexedNotNull null
        val mime = listOf("image/png", "image/jpeg", SVG_MIME).firstOrNull(data::has) ?: return@mapIndexedNotNull null
        NotebookPlot(cell.id, index, mime, NotebookDocument.multiline(data.get(mime)), cell.executionCount)
    }
}

/** The bytes saved for [plot]: the decoded image, or the SVG's text. */
internal fun plotBytes(plot: NotebookPlot): ByteArray =
    if (plot.mime == SVG_MIME) plot.data.toByteArray() else Base64.decode(plot.data, Base64.DEFAULT)

/**
 * The plot viewer (VS Code's for the Interactive window, here for notebooks too): one plot at a
 * time, full screen, to zoom and pan (pinch, double tap, Ctrl and the wheel), go through the
 * notebook's plots (arrows, ← and →), save beside the notebook or to Pictures, share or copy.
 */
@Composable
fun PlotViewerDialog(controller: WorkbenchController) {
    val state = controller.plotViewer ?: return
    val plots = remember(state.session, controller.documentRevision, state.session.notebook.cells.sumOf { it.outputs.size }) {
        notebookPlots(state.session.notebook.cells)
    }
    if (plots.isEmpty()) {
        controller.closePlotViewer()
        return
    }
    val index = state.index.coerceIn(plots.indices)
    val plot = plots[index]
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun go(step: Int) = controller.showPlot((index + step).coerceIn(plots.indices))
    Dialog(
        onDismissRequest = controller::closePlotViewer,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.surfaceContainerLowest)
                // Below the status bar and a desktop window's caption.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.PageUp -> go(-1)
                        Key.DirectionRight, Key.PageDown -> go(1)
                        Key.Escape -> controller.closePlotViewer()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        ) {
            Surface(color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text("Plot ${index + 1} of ${plots.size}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${state.session.notebook.path.substringAfterLast('/').substringBefore(" · ")} · cell [${plot.executionCount ?: " "}] · ${plot.extension.uppercase()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Tooltip("Save beside the notebook") { IconButton(onClick = { controller.savePlotToWorkspace(state.session, plot) }) { Icon(Icons.Outlined.SaveAlt, "Save beside the notebook") } }
                    Tooltip("Save to Pictures") { IconButton(onClick = { controller.savePlotToPictures(plot) }) { Icon(Icons.Outlined.Image, "Save to Pictures") } }
                    Tooltip("Copy image") { IconButton(onClick = { controller.copyPlot(plot) }) { Icon(Icons.Outlined.ContentCopy, "Copy image") } }
                    Tooltip("Share") { IconButton(onClick = { controller.sharePlot(plot) }) { Icon(Icons.Outlined.Share, "Share") } }
                    Tooltip("Close (Escape)") { IconButton(onClick = controller::closePlotViewer) { Icon(Icons.Outlined.Close, "Close (Escape)") } }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val key = "plot:${plot.cellId}:${plot.outputIndex}:${plot.data.hashCode()}"
                val loaded by produceState<Loaded>(Loaded.Loading, key) {
                    value = withContext(Dispatchers.Default) {
                        runCatching {
                            if (plot.mime == SVG_MIME) svgContent(plot.data) else decodeImage(plotBytes(plot), "plot.${plot.extension}")
                        }.getOrElse { Loaded.Failed("Can't draw this plot: ${it.message ?: it.javaClass.simpleName}") }
                    }
                }
                ViewerFrame(controller, key, loaded, Modifier.fillMaxSize())
                if (index > 0) {
                    Tooltip("Previous plot (←)") { FilledTonalIconButton(onClick = { go(-1) }, shape = CircleShape, modifier = Modifier.align(Alignment.CenterStart).padding(12.dp).size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous plot (←)")
                    } }
                }
                if (index < plots.lastIndex) {
                    Tooltip("Next plot (→)") { FilledTonalIconButton(onClick = { go(1) }, shape = CircleShape, modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp).size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next plot (→)")
                    } }
                }
            }
        }
    }
}
