package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avfstudio.nativeide.editor.LspPosition
import kotlin.math.roundToInt

/**
 * [content] at a place in the text, [anchor] (a rect in root coordinates, from [AardinkGeometry]): under it, or
 * over it with [above] or when there's no room below, from its left edge shifted by [shiftX] and kept inside. The
 * cards drawn over the Aardink editor (hover, parameter hints, suggestions, Next Edit, the peek) go through this, as
 * sora's are placed at its text positions.
 */
@Composable
internal fun AtText(
    anchor: Rect,
    above: Boolean = false,
    gap: Dp = 4.dp,
    shiftX: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf<Offset?>(null) }
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    val shiftPx = with(LocalDensity.current) { shiftX.roundToPx() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val from = origin ?: return@Box
        Box(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val left = ((anchor.left - from.x).roundToInt() + shiftPx).coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                val below = (anchor.bottom - from.y).roundToInt() + gapPx
                val over = (anchor.top - from.y).roundToInt() - gapPx - placeable.height
                val fitsBelow = below + placeable.height <= constraints.maxHeight
                val top = when {
                    above && over >= 0 -> over
                    fitsBelow -> below
                    over >= 0 -> over
                    else -> (constraints.maxHeight - placeable.height).coerceAtLeast(0)
                }
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(left, top) }
            },
        ) { content() }
    }
}

/** The Aardink editor's geometry while it shows the active file, or null (sora, or Aardink's internals unreachable). */
internal fun WorkbenchController.aardinkGeometry(): AardinkGeometry? = (editorSurface as? AardinkEditorSurface)?.geometry

/** Where [position] is in the Aardink editor, as a caret's rect; null when it isn't on screen. */
internal fun AardinkEditorSurface.rectOnScreen(position: LspPosition): Rect? {
    val geometry = geometry ?: return null
    val line = position.line.coerceIn(0, lineCount - 1)
    val offset = state.document.lineColToOffset(line, position.character.coerceIn(0, lineText(line).length))
    return geometry.caretAt(offset)?.takeIf(geometry::visible)
}

/** VS Code's hover over the Aardink editor: the workbench's card (problems, then the server's Markdown), over its word. */
@Composable
internal fun AardinkHoverCard(controller: WorkbenchController) {
    val surface = controller.editorSurface as? AardinkEditorSurface ?: return
    val card = controller.hoverCard?.takeIf { it.path == controller.activePath } ?: return
    val anchor = surface.rectOnScreen(card.position) ?: return
    AtText(anchor, above = true) { HoverCardSurface(controller, card, Modifier) }
}

/** Parameter hints over the Aardink editor: the workbench's card, over the caret's line (under it near the top). */
@Composable
internal fun AardinkParameterHints(controller: WorkbenchController) {
    val surface = controller.editorSurface as? AardinkEditorSurface ?: return
    val hint = controller.signatureHint ?: return
    surface.state.selection
    val anchor = surface.rectOnScreen(surface.selection.active) ?: return
    AtText(anchor, above = true) { ParameterHintsCard(hint) }
}
