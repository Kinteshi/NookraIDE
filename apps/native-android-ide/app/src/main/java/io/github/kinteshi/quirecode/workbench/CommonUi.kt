package io.github.kinteshi.quirecode.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What a pane shows with nothing in it, the same everywhere: an icon, what's missing, and what to
 * do about it. Scrolls when the pane is too short for it.
 */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(32.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, textAlign = TextAlign.Center)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center) }
            action?.invoke()
        }
    }
}

/**
 * Thin scrollbars over a scrolling area, which otherwise doesn't show that there's more. Goes on
 * a container of the viewports; the vertical bar keeps below what doesn't scroll at its top.
 */
internal fun Modifier.scrollbars(horizontal: ScrollState? = null, vertical: ScrollState? = null): Modifier = composed {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val thickness = with(LocalDensity.current) { 3.dp.toPx() }
    val inset = with(LocalDensity.current) { 2.dp.toPx() }
    val shortest = with(LocalDensity.current) { 24.dp.toPx() }
    drawWithContent {
        drawContent()
        fun thumb(state: ScrollState, length: Float): Pair<Float, Float>? {
            if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) return null
            val viewport = state.viewportSize.toFloat()
            val size = (length * viewport / (viewport + state.maxValue)).coerceIn(shortest.coerceAtMost(length), length)
            return (length - size) * state.value / state.maxValue to size
        }
        val radius = CornerRadius(thickness / 2)
        horizontal?.let { state ->
            thumb(state, size.width)?.let { (start, length) ->
                drawRoundRect(color, Offset(start, size.height - thickness - inset), Size(length, thickness), radius)
            }
        }
        vertical?.let { state ->
            // Rows above the scrolling part (a table's header) stay out of the bar's way.
            val top = (size.height - state.viewportSize).coerceAtLeast(0f)
            thumb(state, size.height - top)?.let { (start, length) ->
                drawRoundRect(color, Offset(size.width - thickness - inset, top + start), Size(thickness, length), radius)
            }
        }
    }
}

/** The gap between two panes, which resizes them when dragged; [onStopped] is the moment to save the size. */
@Composable
internal fun PaneGutter(onDrag: (Float) -> Unit, onStopped: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    Box(
        modifier
            .width(12.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> onDrag(delta / density) },
                onDragStopped = { onStopped() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 3.dp, height = 32.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
    }
}

/** Smoothly reveals or collapses vertical content (G40), snapping immediately when [animate] is false. */
@Composable
internal fun VerticalReveal(
    visible: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    expandFrom: Alignment.Vertical = Alignment.Top,
    content: @Composable () -> Unit,
) {
    val enterMs = if (animate) 150 else 0
    val exitMs = if (animate) 120 else 0
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(enterMs)) + expandVertically(tween(enterMs), expandFrom = expandFrom),
        exit = fadeOut(tween(exitMs)) + shrinkVertically(tween(exitMs), shrinkTowards = expandFrom),
    ) { content() }
}

/** An expand chevron that smoothly rotates 180 degrees when [expanded]. */
@Composable
internal fun ExpandChevron(expanded: Boolean, animate: Boolean, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(if (animate) 150 else 0), label = "chevron")
    Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = modifier.graphicsLayer { rotationZ = rotation })
}

/** A tree chevron that smoothly rotates 90 degrees when [expanded]. */
@Composable
internal fun TreeChevron(
    expanded: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(if (animate) 140 else 0), label = "treeChevron")
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = tint,
        modifier = modifier.graphicsLayer { rotationZ = rotation },
    )
}
