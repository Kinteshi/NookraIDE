package com.avfstudio.nativeide.workbench

import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import com.aardarch.aardink.core.CodeEditorState
import java.lang.reflect.Method

/**
 * Where text is on screen in the Aardink editor, read from Aardink's internals (backlog A19), until it says so
 * publicly (request 1, A9). Aardink works it out for the input method (`EditorController`, `EditorView`), but keeps
 * it to itself, so this reaches it by reflection through the command handler the layout gives its state, which
 * holds the controller. The version is pinned and each release review checks this still works; when anything is
 * missing, [of] answers null, and the overlays go back to docking at the editor's edge and Aardink's own popups.
 *
 * Rects are in root coordinates. Reads go through Aardink's snapshot state (scroll, the laid-out frame, the
 * selection), so a composable reading them follows scrolling and typing.
 */
internal class AardinkGeometry private constructor(
    private val controller: Any,
    private val view: Any,
    private val coordinates: Method,
    private val caretRect: Method,
    private val offsetAt: Method,
    private val hitOffset: Method,
    private val hoverAt: MutableState<Any?>,
) {
    private fun origin(): Offset? =
        (coordinates.invoke(controller) as? LayoutCoordinates)?.takeIf { it.isAttached }?.localToRoot(Offset.Zero)

    /** The editor's viewport, in root coordinates; null before it is laid out. */
    fun boundsInRoot(): Rect? {
        val layout = (coordinates.invoke(controller) as? LayoutCoordinates)?.takeIf { it.isAttached } ?: return null
        val origin = layout.localToRoot(Offset.Zero)
        return Rect(origin.x, origin.y, origin.x + layout.size.width, origin.y + layout.size.height)
    }

    /** A caret's rect at [offset], in root coordinates, whether or not it is on screen (see [visible]). */
    fun caretAt(offset: Int): Rect? = runCatching {
        val origin = origin() ?: return null
        (caretRect.invoke(view, offset) as Rect).translate(origin)
    }.getOrNull()

    /** Whether [rect] is inside the editor's viewport, top to bottom. */
    fun visible(rect: Rect): Boolean {
        val bounds = boundsInRoot() ?: return false
        return rect.top >= bounds.top - 1 && rect.bottom <= bounds.bottom + 1
    }

    /** The document offset under [rootPoint], or null when it is past the end of its line's text or off the editor. */
    fun offsetAt(rootPoint: Offset): Int? = runCatching {
        val origin = origin() ?: return null
        val local = rootPoint - origin
        val hit = offsetAt.invoke(view, packed(local)) ?: return null
        val offset = hitOffset.invoke(hit) as Int
        // offsetAt clamps to the line: a point past its last character isn't over any.
        val caret = caretAt(offset) ?: return null
        if (rootPoint.x > caret.left + 1 && caretAt(offset + 1)?.let { rootPoint.x <= it.left } != true) return null
        offset
    }.getOrNull()

    /**
     * Where Aardink's own hover tracking has the mouse resting, in root coordinates, which is taken: Aardink then
     * has nothing to show its own hover card for, and the workbench shows its own.
     */
    fun takeHover(): Offset? {
        val at = hoverAt.value ?: return null
        hoverAt.value = null
        val local = unpack(at) ?: return null
        return origin()?.let { local + it }
    }

    companion object {
        private const val CONTROLLER = "com.aardarch.aardink.ui.view.EditorController"

        /** The geometry of the editor [state] is shown in, or null when Aardink's internals aren't as expected. */
        fun of(state: CodeEditorState): AardinkGeometry? = runCatching {
            val handlerField = CodeEditorState::class.java.getDeclaredField("uiCommandHandler").apply { isAccessible = true }
            val handler = handlerField.get(state) ?: return null
            val controller = handler.javaClass.declaredFields.asSequence()
                .onEach { it.isAccessible = true }
                .mapNotNull { it.get(handler) }
                .firstOrNull { it.javaClass.name == CONTROLLER } ?: return null
            val controllerClass = controller.javaClass
            val view = controllerClass.getMethod("getView").invoke(controller) ?: return null
            val viewClass = view.javaClass
            val offsetAt = viewClass.methods.first { it.name.startsWith("offsetAt") && it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) }
            val hitOffset = offsetAt.returnType.getMethod("getOffset")
            @Suppress("UNCHECKED_CAST")
            val hover = controllerClass.getDeclaredField("hoverAt\$delegate").apply { isAccessible = true }.get(controller) as MutableState<Any?>
            AardinkGeometry(
                controller = controller,
                view = view,
                coordinates = controllerClass.getMethod("getCoordinates"),
                caretRect = viewClass.getMethod("caretRect", Int::class.javaPrimitiveType),
                offsetAt = offsetAt,
                hitOffset = hitOffset,
                hoverAt = hover,
            )
        }.onFailure { android.util.Log.w("AardinkGeometry", "Aardink's text positions aren't reachable; overlays dock at the edge", it) }.getOrNull()

        /** An [Offset] as Aardink's methods take it: its packed value, as Compose passes a value class. */
        private fun packed(offset: Offset): Long {
            val x = java.lang.Float.floatToRawIntBits(offset.x).toLong()
            val y = java.lang.Float.floatToRawIntBits(offset.y).toLong()
            return (x shl 32) or (y and 0xFFFFFFFFL)
        }

        /** A boxed [Offset] that came through reflection. */
        private fun unpack(boxed: Any): Offset? = when (boxed) {
            is Offset -> boxed
            is Long -> Offset(java.lang.Float.intBitsToFloat((boxed shr 32).toInt()), java.lang.Float.intBitsToFloat(boxed.toInt()))
            else -> null
        }
    }
}
