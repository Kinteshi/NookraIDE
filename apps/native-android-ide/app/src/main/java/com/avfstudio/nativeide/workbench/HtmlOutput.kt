package com.avfstudio.nativeide.workbench

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A `text/html` cell output (pandas tables, styled reprs) with styles that follow the app theme.
 * Scripts stay off: outputs from other people's notebooks can't run code in the app; outputs that
 * need JavaScript show their `text/plain` form instead (see [htmlNeedsScript]).
 */
internal fun htmlOutputPage(html: String, dark: Boolean): String {
    val text = if (dark) "#d4d4d4" else "#1f1f1f"
    val rule = if (dark) "#3c3c3c" else "#e0e0e0"
    val stripe = if (dark) "rgba(255,255,255,0.04)" else "rgba(0,0,0,0.03)"
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        html,body{margin:0;padding:0;background:transparent;color:$text;font:13px/1.4 sans-serif}
        table{border-collapse:collapse;font-variant-numeric:tabular-nums}
        th,td{padding:4px 10px;border-bottom:1px solid $rule;text-align:right;white-space:nowrap}
        thead th{border-bottom:2px solid $rule}
        tbody tr:nth-child(even){background:$stripe}
        a{color:#4fc1ff}
        </style></head><body>$html</body></html>
    """.trimIndent()
}

/** Outputs built around a script (widgets, bokeh, altair) render nothing without JavaScript. */
internal fun htmlNeedsScript(html: String): Boolean = html.contains("<script", ignoreCase = true)

@Composable
internal fun HtmlOutput(html: String, dark: Boolean, modifier: Modifier = Modifier) {
    val page = remember(html, dark) { htmlOutputPage(html, dark) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // Measured to its content, up to the max height below; wide tables scroll sideways.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
            }
        },
        update = { view ->
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
        onRelease = WebView::destroy,
        modifier = modifier.fillMaxWidth().heightIn(max = 520.dp),
    )
}
