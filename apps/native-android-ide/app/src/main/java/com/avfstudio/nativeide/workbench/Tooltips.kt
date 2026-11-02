package com.avfstudio.nativeide.workbench

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Whether buttons show tooltips (`avf.workbench.tooltips`), and the key a command answers to. */
class Tooltips(val enabled: Boolean, val keybindingFor: (String) -> String)

val LocalTooltips = staticCompositionLocalOf { Tooltips(true) { "" } }

/** How long the pointer rests on a button before its tooltip shows (VS Code's `workbench.hover.delay`). */
private const val TOOLTIP_DELAY_MS = 500L

/**
 * A plain Material tooltip over [content] (an icon button): [text], and the key of [command]
 * when it has one ("Find Next (F3)"). It shows when a mouse, touchpad or stylus pointer rests on
 * it, and on a long press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tooltip(text: String, command: String? = null, content: @Composable () -> Unit) {
    val tooltips = LocalTooltips.current
    if (!tooltips.enabled || text.isBlank()) {
        content()
        return
    }
    val state = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            val key = command?.let(tooltips.keybindingFor).orEmpty()
            PlainTooltip { Text(if (key.isEmpty() || text.endsWith(")")) text else "$text ($key)") }
        },
        state = state,
        // Material shows it for a mouse's hover; a touchpad's pointer can come as a finger that
        // hovers, which [hoverTooltip] covers.
        modifier = Modifier.hoverTooltip { show -> scope.launch { if (show) state.show() else state.dismiss() } },
        content = content,
    )
}

/** Calls [onHover] with true once a hovering pointer (any kind but a touching finger) rests, false when it leaves. */
private fun Modifier.hoverTooltip(onHover: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    coroutineScopeHover(onHover)
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.coroutineScopeHover(onHover: (Boolean) -> Unit) {
    kotlinx.coroutines.coroutineScope {
        var pending: Job? = null
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Enter -> if (change.type != PointerType.Touch || !change.pressed) {
                        pending?.cancel()
                        pending = launch {
                            delay(TOOLTIP_DELAY_MS)
                            onHover(true)
                        }
                    }
                    PointerEventType.Exit, PointerEventType.Press -> {
                        pending?.cancel()
                        pending = null
                        onHover(false)
                    }
                }
            }
        }
    }
}
