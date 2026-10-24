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

    fun route(event: KeyEvent): Boolean = handler?.invoke(event) ?: false
}
