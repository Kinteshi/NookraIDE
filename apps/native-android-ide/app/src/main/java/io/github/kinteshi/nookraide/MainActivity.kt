package io.github.kinteshi.nookraide

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.kinteshi.nookraide.workbench.KeyRouter
import io.github.kinteshi.nookraide.workbench.asPlainKeyboardArrow
import androidx.compose.ui.platform.ComposeView
import android.widget.FrameLayout
import android.view.KeyEvent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kinteshi.nookraide.workbench.NativeWorkbench
import io.github.kinteshi.nookraide.assets.AssetPackGrammarLoader
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
                io.github.kinteshi.nookraide.workbench.Perf.keyDown(event)
                if (KeyRouter.route(event)) return true
                if (KeyRouter.bypassesIme?.invoke(event) == true) return super.dispatchKeyEvent(event.asPlainKeyboardArrow())
                return super.dispatchKeyEventPreIme(event.asPlainKeyboardArrow())
            }
        }
        root.addView(ComposeView(this).apply { setContent { NativeWorkbench() } })
        setContentView(root)
        io.github.kinteshi.nookraide.workbench.Perf.watchFrames(window)
        // A restored activity already handled the intent it started with.
        if (savedInstanceState == null) io.github.kinteshi.nookraide.workbench.IntentRouter.deliver(intent)
        root.post(::keepWindowOnScreen)
    }

    /**
     * In desktop windowing (Samsung's Desk mode, Android's desktop mode), the system reopens a floating window at its last
     * bounds, which after an install or a restart can reach past the screen (backlog G6); the app can't move its own window,
     * so a window opened partly off-screen asks to be full screen. Checked once as the app opens: a window the user drags
     * partly off-screen later stays where they put it.
     */
    private fun keepWindowOnScreen() {
        if (!isInMultiWindowMode) return
        val window = windowManager.currentWindowMetrics.bounds
        val screen = windowManager.maximumWindowMetrics.bounds
        // A few pixels of slack for window shadows and rounding.
        val slack = (4 * resources.displayMetrics.density).toInt()
        val inside = window.left >= screen.left - slack && window.top >= screen.top - slack &&
            window.right <= screen.right + slack && window.bottom <= screen.bottom + slack
        if (inside) return
        android.util.Log.i("MainActivity", "Window $window reaches past the screen $screen; asking for full screen")
        runCatching { requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, null) }
            .onFailure { android.util.Log.w("MainActivity", "Full screen refused", it) }
    }

    /** Compose drops touchpad pinches before they reach the views inside it; they go to the workbench here. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.classification == android.view.MotionEvent.CLASSIFICATION_PINCH &&
            io.github.kinteshi.nookraide.workbench.TouchpadPinch.handler?.invoke(ev) == true
        ) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        io.github.kinteshi.nookraide.workbench.IntentRouter.deliver(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyRouter.route(event) || super.dispatchKeyEvent(event.asPlainKeyboardArrow())
}

internal const val LIGHT_SYNTAX_THEME = "avf-light.tmTheme.json"
internal const val DARK_SYNTAX_THEME = "avf-dark.tmTheme.json"
