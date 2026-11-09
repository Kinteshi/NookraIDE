package com.avfstudio.nativeide

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.avfstudio.nativeide.workbench.KeyRouter
import com.avfstudio.nativeide.workbench.asPlainKeyboardArrow
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
import com.avfstudio.nativeide.workbench.NativeWorkbench
import com.avfstudio.nativeide.assets.AssetPackGrammarLoader
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
                com.avfstudio.nativeide.workbench.Perf.keyDown(event)
                if (KeyRouter.route(event)) return true
                if (KeyRouter.bypassesIme?.invoke(event) == true) return super.dispatchKeyEvent(event.asPlainKeyboardArrow())
                return super.dispatchKeyEventPreIme(event.asPlainKeyboardArrow())
            }
        }
        root.addView(ComposeView(this).apply { setContent { NativeWorkbench() } })
        setContentView(root)
        com.avfstudio.nativeide.workbench.Perf.watchFrames(window)
        // A restored activity already handled the intent it started with.
        if (savedInstanceState == null) com.avfstudio.nativeide.workbench.IntentRouter.deliver(intent)
    }

    /** Compose drops touchpad pinches before they reach the views inside it; they go to the workbench here. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.classification == android.view.MotionEvent.CLASSIFICATION_PINCH &&
            com.avfstudio.nativeide.workbench.TouchpadPinch.handler?.invoke(ev) == true
        ) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        com.avfstudio.nativeide.workbench.IntentRouter.deliver(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyRouter.route(event) || super.dispatchKeyEvent(event.asPlainKeyboardArrow())
}

internal const val LIGHT_SYNTAX_THEME = "avf-light.tmTheme.json"
internal const val DARK_SYNTAX_THEME = "avf-dark.tmTheme.json"
