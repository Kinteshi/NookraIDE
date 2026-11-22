package io.github.kinteshi.nookraide.workbench

import io.github.kinteshi.nookraide.editor.LspPosition

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private fun LanguageLocation.startLine(): Int = range.getAsJsonObject("start")?.get("line")?.asInt ?: 0
private fun LanguageLocation.endLine(): Int = range.getAsJsonObject("end")?.get("line")?.asInt ?: startLine()

/**
 * VS Code's peek, under the cursor's line (above it near the bottom): the selected location's
 * file around it, its lines marked, and the list of locations when there are several. Tap a
 * location to show it, double tap (or Open) to go there; Escape closes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PeekOverlay(controller: WorkbenchController) {
    controller.editorViewportTick
    val peek = controller.peek?.takeIf { it.path == controller.activePath } ?: return
    val aardink = controller.editorSurface as? AardinkEditorSurface ?: return
    val line = peek.position.line.coerceIn(0, aardink.lineCount - 1)
    val height = 280.dp
    val selected = peek.locations.getOrNull(peek.selected) ?: return
    val fontSize = (controller.settings.editorFontSize * 0.9f).sp
    // Under its line, from Aardink's geometry (A19); without it, at the editor's edge away from the line.
    val under = aardink.rectOnScreen(LspPosition(line, 0))
    if (under != null) {
        AtText(under, gap = 2.dp) { PeekCard(controller, peek, selected, fontSize, Modifier.fillMaxWidth().height(height).padding(horizontal = 8.dp)) }
        return
    }
    val dockBottom = aardink.visibleLines.let { visible -> line - visible.first < (visible.last - visible.first) / 2 }
    PeekCard(
        controller,
        peek,
        selected,
        fontSize,
        Modifier
            // At the editor's top or bottom edge, so the height below is the peek's, not the editor's.
            .fillMaxSize()
            .wrapContentHeight(if (dockBottom) Alignment.Bottom else Alignment.Top)
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 8.dp),
    )
}

/** The peek's card, wherever it's placed ([modifier]): the location's file, and the list when there are several. */
@Composable
private fun PeekCard(
    controller: WorkbenchController,
    peek: PeekView,
    selected: LanguageLocation,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.6f)),
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(colors.surfaceContainerHighest).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val path = controller.locationPath(selected.uri)
                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                Text(
                    path.substringBeforeLast('/', "").substringAfterLast('/'),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${peek.kind.title}${if (peek.locations.size > 1) " (${peek.locations.size})" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                )
                Tooltip("Open") { IconButton(onClick = controller::openPeekLocation, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.OpenInNew, contentDescription = "Open", modifier = Modifier.size(18.dp)) } }
                Tooltip("Close", "closeReferenceSearch") { IconButton(onClick = controller::closePeek, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(18.dp)) } }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.weight(1f)) {
                PeekPreview(controller, selected, fontSize, Modifier.weight(1f).fillMaxHeight())
                if (peek.locations.size > 1) {
                    VerticalDivider(color = colors.outlineVariant)
                    LazyColumn(Modifier.width(300.dp).fillMaxHeight()) {
                        var lastUri: String? = null
                        peek.locations.forEachIndexed { index, location ->
                            if (location.uri != lastUri) {
                                lastUri = location.uri
                                val count = peek.locations.count { it.uri == location.uri }
                                item(key = "file:${location.uri}") {
                                    Text(
                                        "${controller.locationPath(location.uri).substringAfterLast('/')}  $count",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp),
                                    )
                                }
                            }
                            item(key = index) {
                                val text = controller.locationTexts[location.uri]?.getOrNull(location.startLine())?.trim().orEmpty()
                                Text(
                                    text.ifEmpty { "line ${location.startLine() + 1}" },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = colors.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (index == peek.selected) colors.secondaryContainer else colors.surfaceContainerHigh)
                                        .combinedClickable(
                                            onClick = { controller.selectPeekLocation(index) },
                                            onDoubleClick = {
                                                controller.selectPeekLocation(index)
                                                controller.openPeekLocation()
                                            },
                                        )
                                        .padding(start = 20.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The location's file with line numbers, its lines marked, scrolled to it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeekPreview(controller: WorkbenchController, location: LanguageLocation, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val lines = controller.locationTexts[location.uri]
    if (lines == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Loading…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        return
    }
    val first = location.startLine()
    val last = location.endLine()
    val state = rememberLazyListState()
    LaunchedEffect(location) { state.scrollToItem((first - 3).coerceAtLeast(0)) }
    val gutter = lines.size.toString().length
    LazyColumn(modifier.background(colors.surfaceContainerLowest), state = state) {
        itemsIndexed(lines) { index, text ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (index in first..last) colors.primary.copy(alpha = 0.16f) else colors.surfaceContainerLowest)
                    .combinedClickable(onClick = {}, onDoubleClick = controller::openPeekLocation),
            ) {
                Text(
                    (index + 1).toString().padStart(gutter),
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, end = 12.dp),
                )
                Text(text.removeSuffix("\r").replace("\t", " ".repeat(controller.settings.tabSize)), fontFamily = FontFamily.Monospace, fontSize = fontSize, color = colors.onSurface, maxLines = 1, softWrap = false)
            }
        }
    }
}
