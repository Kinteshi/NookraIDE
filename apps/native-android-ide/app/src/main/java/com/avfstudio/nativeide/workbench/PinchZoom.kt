package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** Where the activity sends touchpad pinches (which Compose doesn't deliver); set while the workbench shows. */
object TouchpadPinch {
    var handler: ((android.view.MotionEvent) -> Boolean)? = null
    internal var lastSpan = 0f
}

/**
 * How much a touchpad pinch event scales since the last one. The gesture's scale axis reads 0 on
 * the Tab S11 (its touchpads don't declare it), so the span between the pinch's two pointers
 * gives it instead.
 */
fun touchpadPinchFactor(event: android.view.MotionEvent): Float? {
    if (event.pointerCount < 2) {
        TouchpadPinch.lastSpan = 0f
        return null
    }
    val span = kotlin.math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    val last = TouchpadPinch.lastSpan
    TouchpadPinch.lastSpan = if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE) span else 0f
    if (event.actionMasked != android.view.MotionEvent.ACTION_MOVE || last <= 0f || span <= 0f) return null
    val axis = event.getAxisValue(android.view.MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR)
    return if (axis > 0f) axis else span / last
}

/**
 * Pinch (two fingers on the screen or the touchpad) and Ctrl with the wheel over a Compose view
 * change the editor font size, as they do in the code editor. Seen before the list scrolls, so
 * two fingers zoom instead of scrolling; one finger is left alone.
 */
@Composable
fun Modifier.editorFontZoom(controller: WorkbenchController): Modifier {
    val pinch = controller.settings.pinchToZoom
    val wheel = controller.settings.mouseWheelZoom
    if (!pinch && !wheel) return this
    val current by rememberUpdatedState(controller)
    // A touchpad pinch over this area (see [TouchpadPinch]) zooms it too.
    val area = remember { Any() }
    DisposableEffect(controller) { onDispose { controller.fontZoomAreas.remove(area) } }
    return onGloballyPositioned { controller.fontZoomAreas[area] = it.boundsInWindow() }.pointerInput(pinch, wheel) {
        awaitPointerEventScope {
            var zooming = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll) {
                    if (wheel && event.keyboardModifiers.isCtrlPressed) {
                        val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }
                        if (delta != 0.0) current.zoomEditor(if (delta < 0) 1f else -1f)
                        event.changes.forEach { it.consume() }
                    }
                    continue
                }
                val fingers = event.changes.count { it.pressed }
                if (pinch && fingers >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) current.pinchZoom(zoom)
                    zooming = true
                    event.changes.forEach { it.consume() }
                } else if (zooming) {
                    zooming = false
                    current.endPinchZoom()
                }
            }
        }
    }
}
