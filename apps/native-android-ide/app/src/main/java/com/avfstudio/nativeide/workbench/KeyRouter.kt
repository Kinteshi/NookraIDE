package com.avfstudio.nativeide.workbench

import android.view.KeyEvent

/**
 * Hands hardware keys to the workbench at the window level. [MainActivity] calls [route] before the
 * IME sees a key (Gboard handles physical keyboards and can swallow Ctrl combinations) and again
 * from Activity.dispatchKeyEvent, which still runs when no view has focus. The workbench dedupes.
 */
object KeyRouter {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null

    fun route(event: KeyEvent): Boolean {
        val handled = handler?.invoke(event) ?: false
        // `adb shell setprop log.tag.AVFKEYS DEBUG`: every hardware key as Android delivers it, to
        // compare keyboards (the Book Cover keyboard vs a test HID keyboard).
        if (android.util.Log.isLoggable("AVFKEYS", android.util.Log.DEBUG) && event.repeatCount == 0) {
            android.util.Log.d(
                "AVFKEYS",
                "${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"} ${KeyEvent.keyCodeToString(event.keyCode)} " +
                    "scan=${event.scanCode} meta=0x${Integer.toHexString(event.metaState)} dev=${event.deviceId} " +
                    "'${event.device?.name}' src=0x${Integer.toHexString(event.source)} router=$handled",
            )
        }
        return handled
    }
}
