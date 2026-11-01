package com.avfstudio.nativeide.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.avfstudio.nativeide.notebook.NotebookCell
import kotlinx.coroutines.launch

/** Where a dragged item should go: one step up or down once it passes its neighbour's middle. */
internal enum class DragStep { Up, Down }

/**
 * The step for an item at [top] (its laid-out position) with [size], dragged by [offset], between
 * [previous] and [next] (their top and size). Moving down needs the item's bottom past the next
 * item's middle, moving up its top above the previous one's, so a tall and a short cell don't
 * swap back and forth.
 */
internal fun dragStep(top: Int, size: Int, offset: Float, previous: Pair<Int, Int>?, next: Pair<Int, Int>?): DragStep? = when {
    next != null && top + offset + size > next.first + next.second / 2f -> DragStep.Down
    previous != null && top + offset < previous.first + previous.second / 2f -> DragStep.Up
    else -> null
}

/**
 * The selected cell's drag handle (in its gutter): drag it to move the cell; the list scrolls
 * when the cell reaches its top or bottom edge.
 */
@Composable
internal fun CellDragHandle(controller: WorkbenchController, session: NotebookSession, cell: NotebookCell, listState: LazyListState) {
    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    fun cellItems(): List<LazyListItemInfo> = listState.layoutInfo.visibleItemsInfo.filter { it.key is String && it.index < session.notebook.cells.size }
    fun reorder() {
        val items = cellItems()
        val dragged = items.firstOrNull { it.key == cell.id } ?: return
        val previous = items.firstOrNull { it.index == dragged.index - 1 }
        val next = items.firstOrNull { it.index == dragged.index + 1 }
        val step = dragStep(dragged.offset, dragged.size, session.dragOffset, previous?.let { it.offset to it.size }, next?.let { it.offset to it.size }) ?: return
        val first = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        when (step) {
            DragStep.Down -> {
                controller.moveCellTo(session, cell, dragged.index + 1)
                session.dragOffset -= next!!.size
            }
            DragStep.Up -> {
                controller.moveCellTo(session, cell, dragged.index - 1)
                session.dragOffset += previous!!.size
            }
        }
        // The list keeps its first visible item in place by key; moving that item would scroll it.
        if (dragged.index == first || (step == DragStep.Up && dragged.index - 1 == first)) {
            scope.launch { listState.scrollToItem(first, firstOffset) }
        }
    }
    if (session.draggedCell == cell.id) {
        LaunchedEffect(cell.id) {
            while (session.draggedCell == cell.id) {
                withFrameNanos { }
                val dragged = cellItems().firstOrNull { it.key == cell.id } ?: continue
                val top = dragged.offset + session.dragOffset
                val viewport = listState.layoutInfo.viewportEndOffset
                val speed = when {
                    top < listState.layoutInfo.viewportStartOffset + edge && listState.canScrollBackward -> -12f
                    top + minOf(dragged.size.toFloat(), edge * 2) > viewport - edge && listState.canScrollForward -> 12f
                    else -> 0f
                }
                if (speed != 0f) {
                    val scrolled = listState.scrollBy(speed)
                    session.dragOffset += scrolled
                    reorder()
                }
            }
        }
    }
    Icon(
        Icons.Outlined.DragIndicator,
        contentDescription = "Drag to move the cell",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 4.dp)
            .size(28.dp)
            .noKeyFocus()
            .pointerInput(cell.id) {
                detectDragGestures(
                    onDragStart = {
                        session.selectedCell = cell.id
                        session.draggedCell = cell.id
                        session.dragOffset = 0f
                    },
                    onDragEnd = { session.draggedCell = null; session.dragOffset = 0f },
                    onDragCancel = { session.draggedCell = null; session.dragOffset = 0f },
                ) { change, amount ->
                    change.consume()
                    session.dragOffset += amount.y
                    reorder()
                }
            }
            .padding(2.dp),
    )
}

/** "Cell deleted · Undo" at the bottom of the notebook for a few seconds after a delete. */
@Composable
internal fun UndoDeleteBar(controller: WorkbenchController, session: NotebookSession, modifier: Modifier = Modifier) {
    AnimatedVisibility(session.lastDeleted != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = MaterialTheme.shapes.small,
            shadowElevation = 4.dp,
            modifier = Modifier.padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                Box(Modifier.padding(vertical = 12.dp)) { Text("Cell deleted", style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { controller.undoDeleteCell(session) }, modifier = Modifier.noKeyFocus()) {
                    Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}
