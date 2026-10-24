package com.avfstudio.nativeide.workbench

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.JsonObject

/** The mimetype plotly.py emits with the `plotly_mimetype` renderer (the bridge's default). */
internal const val PLOTLY_MIME = "application/vnd.plotly.v1+json"

/**
 * Same plotly.js as plotly.py 7.1 bundles. Loaded from Plotly's CDN rather than shipped in the APK;
 * the WebView cache keeps it for later offline use.
 */
private const val PLOTLY_JS = "https://cdn.plot.ly/plotly-4.1.1.min.js"

/** Builds the page for one figure; `</` is escaped so figure text can't close the script tag. */
internal fun plotlyPage(figure: JsonObject, dark: Boolean): String {
    val json = figure.toString().replace("</", "<\\/")
    val text = if (dark) "#cccccc" else "#333333"
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;background:transparent;color:$text;font:13px sans-serif}#plot{width:100%;height:100vh}</style>
        </head><body><div id="plot"></div>
        <script src="$PLOTLY_JS" onerror="document.body.textContent='Could not load plotly.js; the first plot needs an internet connection.'"></script>
        <script>
        if (window.Plotly) {
          var figure = $json;
          var layout = Object.assign({}, figure.layout || {}, {autosize: true});
          delete layout.height; delete layout.width;
          Plotly.newPlot('plot', figure.data || [], layout, Object.assign({responsive: true, displaylogo: false}, figure.config || {}));
        }
        </script></body></html>
    """.trimIndent()
}

/** One Plotly figure; interactive (hover, zoom) inside a sandboxed WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PlotlyOutput(figure: JsonObject, dark: Boolean, modifier: Modifier = Modifier) {
    val page = remember(figure, dark) { plotlyPage(figure, dark) }
    // Plotly's layout height is in CSS px, which WebView maps to dp.
    val height = figure.getAsJsonObject("layout")?.get("height")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(200, 1200) ?: 450
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
            }
        },
        update = { view ->
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL("https://avf-studio.invalid/", page, "text/html", "utf-8", null)
            }
        },
        onRelease = WebView::destroy,
        modifier = modifier.fillMaxWidth().height(height.dp).background(Color.Transparent, RoundedCornerShape(6.dp)),
    )
}
