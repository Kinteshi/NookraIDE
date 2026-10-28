package com.avfstudio.nativeide.workbench

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Hands hardware keys to the workbench at the window level. [MainActivity] calls [route] before the
 * IME sees a key (Gboard handles physical keyboards and can swallow Ctrl combinations) and again
 * from Activity.dispatchKeyEvent, which still runs when no view has focus. The workbench dedupes.
 */
object KeyRouter {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null

    fun route(event: KeyEvent): Boolean = handler?.invoke(event) ?: false
}

private val arrowKeys = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
)

/**
 * Some physical keyboards report themselves as a D-pad too (the Book Cover Keyboard EF-DX935 is
 * KEYBOARD | DPAD). Compose text fields treat arrows from a D-pad device as TV-style focus moves
 * (`interceptDPadAndMoveFocus`), so the cursor didn't move and focus jumped to buttons. Re-issue
 * such arrows as coming from the virtual keyboard, which text fields handle as cursor keys; arrows
 * nothing consumes still move focus as before.
 */
fun KeyEvent.asPlainKeyboardArrow(): KeyEvent {
    if (keyCode !in arrowKeys) return this
    val device = device ?: return this
    if (device.isVirtual || !device.supportsSource(InputDevice.SOURCE_DPAD) ||
        device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC
    ) {
        return this
    }
    return KeyEvent(downTime, eventTime, action, keyCode, repeatCount, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, scanCode, flags, InputDevice.SOURCE_KEYBOARD)
}
