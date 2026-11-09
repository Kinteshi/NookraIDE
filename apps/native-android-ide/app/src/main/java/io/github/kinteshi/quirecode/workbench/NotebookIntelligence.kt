package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.kinteshi.quirecode.notebook.CellType
import io.github.kinteshi.quirecode.notebook.NotebookCell
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Wavy lines under a cell's problems, in each problem's colour (VS Code's squiggles). */
@Composable
internal fun Modifier.cellProblemSquiggles(problems: List<CellProblem>, layout: TextLayoutResult?): Modifier {
    if (problems.isEmpty() || layout == null) return this
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val amplitude = with(density) { 1.5.dp.toPx() }
    val stroke = with(density) { 1.dp.toPx() }
    return drawWithContent {
        drawContent()
        val length = layout.layoutInput.text.length
        if (length == 0) return@drawWithContent
        problems.forEach { problem ->
            val start = problem.start.coerceIn(0, length - 1)
            val end = problem.end.coerceIn(start + 1, length)
            val first = layout.getLineForOffset(start)
            val last = layout.getLineForOffset(end - 1)
            for (line in first..last) {
                val left = if (line == first) layout.getHorizontalPosition(start, usePrimaryDirection = true) else layout.getLineLeft(line)
                var right = if (line == last) layout.getHorizontalPosition(end, usePrimaryDirection = true) else layout.getLineRight(line)
                if (right - left < amplitude * 4) right = left + amplitude * 4
                squiggle(left, right, layout.getLineBottom(line) - amplitude, amplitude, colors.problemColor(problem.severity), stroke)
            }
        }
    }
}

private fun DrawScope.squiggle(left: Float, right: Float, y: Float, amplitude: Float, color: Color, stroke: Float) {
    val path = Path().apply {
        moveTo(left, y)
        var x = left
        var up = true
        while (x < right) {
            x = minOf(x + amplitude * 2, right)
            lineTo(x, if (up) y - amplitude else y + amplitude)
            up = !up
        }
    }
    drawPath(path, color, style = Stroke(width = stroke))
}

/** The character under [position] in a cell's text, or null past a line's end or below the text. */
private fun cellOffsetAt(layout: TextLayoutResult, position: Offset): Int? {
    if (position.y < 0 || position.y > layout.size.height) return null
    val line = layout.getLineForVerticalPosition(position.y)
    if (position.x > layout.getLineRight(line) || position.x < layout.getLineLeft(line)) return null
    val length = layout.layoutInput.text.length
    if (length == 0) return null
    return layout.getOffsetForPosition(position).coerceIn(0, length - 1)
}

/** Whether [other] is in the same word (identifier) as [offset] in [source]. */
internal fun sameWord(source: String, offset: Int, other: Int): Boolean {
    if (offset !in source.indices || other !in source.indices) return offset == other
    var start = offset
    var end = offset + 1
    while (start > 0 && isIdentifierCharacter(source[start - 1])) start--
    while (end < source.length && isIdentifierCharacter(source[end - 1]) && isIdentifierCharacter(source[end])) end++
    return other in start until end
}

/** Where the word (identifier) at [offset] in [source] starts. */
internal fun wordStart(source: String, offset: Int): Int {
    var start = offset.coerceIn(0, source.length)
    while (start > 0 && isIdentifierCharacter(source[start - 1])) start--
    return start
}

/**
 * A mouse, touchpad or stylus pointer resting on a code cell's text asks for the hover card
 * there (`editor.hover.delay`). Off the word the card stays for `editor.hover.hidingDelay`, so the
 * pointer can go onto it, as in the code editor; resting on another word replaces it, a press closes it.
 */
internal fun Modifier.cellHover(
    controller: WorkbenchController,
    session: NotebookSession,
    cell: NotebookCell,
    layout: () -> TextLayoutResult?,
): Modifier = if (cell.type != CellType.Code) this else pointerInput(cell.id) {
    coroutineScope {
        var pending: Job? = null
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> {
                        if (change.pressed || !controller.settings.editorHover) continue
                        pending?.cancel()
                        val shown = layout() ?: continue
                        val offset = cellOffsetAt(shown, change.position)
                        val hover = session.cellHover?.takeIf { it.cellId == cell.id }
                        val onItsWord = hover != null && offset != null && sameWord(cell.source, hover.offset, offset)
                        if (onItsWord) {
                            controller.keepCellHover()
                            continue
                        }
                        if (hover != null) controller.scheduleCellHoverHide(session)
                        if (offset != null) {
                            pending = launch {
                                delay(controller.settings.editorHoverDelay.toLong())
                                controller.showCellHover(session, cell, offset, fromPointer = true)
                            }
                        }
                    }
                    // Also when the pointer goes onto the card, a window of its own: the card's Enter keeps it.
                    PointerEventType.Exit -> {
                        pending?.cancel()
                        if (session.cellHover?.cellId == cell.id) controller.scheduleCellHoverHide(session)
                    }
                    PointerEventType.Press -> {
                        pending?.cancel()
                        if (session.cellHover != null) controller.hideCellHover(session)
                    }
                }
            }
        }
    }
}

/** The hover card above the hovered word (below it near the top of the window). */
@Composable
internal fun CellHoverCard(controller: WorkbenchController, session: NotebookSession, hover: CellHover, layout: TextLayoutResult) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // The text sits 12 dp inside the box the popup is anchored to.
    val inset = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 4.dp.roundToPx() }
    // At the word's start, as in VS Code: from anywhere on the word, straight up lands on the card.
    val offset = wordStart(layout.layoutInput.text.text, hover.offset.coerceIn(0, layout.layoutInput.text.length))
    val line = layout.getLineForOffset(offset)
    val x = layout.getHorizontalPosition(offset, usePrimaryDirection = true).roundToInt() + inset
    val top = layout.getLineTop(line).roundToInt() + inset
    val bottom = layout.getLineBottom(line).roundToInt() + inset
    val position = object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val left = (anchorBounds.left + x).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
            val above = anchorBounds.top + top - popupContentSize.height - gap
            return IntOffset(left, if (above >= 0) above else anchorBounds.top + bottom + gap)
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = { controller.hideCellHover(session) }, properties = PopupProperties(focusable = false)) {
        Surface(
            color = colors.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            shadowElevation = 4.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.widthIn(max = 560.dp).heightIn(max = 320.dp).pointerInput(hover) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            PointerEventType.Enter -> controller.enterCellHover()
                            PointerEventType.Exit -> controller.leaveCellHover(session)
                        }
                    }
                }
            },
        ) {
            val problems = hover.problems.map { HoverProblem(it.severity, it.message, it.origin) }
            val expand = hover.markdown?.let { markdown ->
                { controller.expandHover(ExpandedDocs(session.document.path, session.document.path.substringAfterLast('/'), markdown)) }
            }
            HoverCardContent(controller, session.document.path, problems, hover.markdown, expand)
        }
    }
}

/** A code cell's problems under it (the first three); a tap puts the caret on one. */
@Composable
internal fun CellProblemsList(controller: WorkbenchController, session: NotebookSession, cell: NotebookCell) {
    val problems = session.cellProblems[cell.id].orEmpty()
    if (problems.isEmpty() || cell.type != CellType.Code) return
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp)) {
        problems.take(3).forEach { problem ->
            val (line, column) = cellPosition(cell.source, problem.start)
            Row(
                Modifier.fillMaxWidth().noKeyFocus().clickable { controller.revealCellOffset(session, cell, problem.start) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(severityIcon(problem.severity), contentDescription = null, tint = colors.problemColor(problem.severity), modifier = Modifier.size(14.dp))
                Text(problem.message, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    listOf(problem.origin, "Ln ${line + 1}, Col ${column + 1}").filter(String::isNotBlank).joinToString("  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (problems.size > 3) {
            Text("${problems.size - 3} more", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp))
        }
    }
}
