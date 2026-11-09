package io.github.kinteshi.quirecode.workbench

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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

/** Figure JSON safe inside a script tag: `</` is escaped so figure text can't close it. */
private fun scriptJson(figure: JsonObject): String = figure.toString().replace("</", "<\\/")

/**
 * Builds the page for one figure. `avfRender` draws with Plotly.react, so a figure updated in
 * place (`display_id` updates, e.g. a loss chart per epoch) redraws without reloading the page.
 */
internal fun plotlyPage(figure: JsonObject, dark: Boolean): String {
    val text = if (dark) "#cccccc" else "#333333"
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;background:transparent;color:$text;font:13px sans-serif}#plot{width:100%;height:100vh}</style>
        </head><body><div id="plot"></div>
        <script src="$PLOTLY_JS" onerror="document.body.textContent='Could not load plotly.js; the first plot needs an internet connection.'"></script>
        <script>
        function avfRender(figure) {
          if (!window.Plotly) return;
          var layout = Object.assign({}, figure.layout || {}, {autosize: true});
          delete layout.height; delete layout.width;
          Plotly.react('plot', figure.data || [], layout, Object.assign({responsive: true, displaylogo: false}, figure.config || {}));
        }
        avfRender(${scriptJson(figure)});
        </script></body></html>
    """.trimIndent()
}

/** What a figure's WebView shows: the page is loaded for [dark]; [figure] is the latest one asked for. */
private class PlotState(val dark: Boolean, var figure: JsonObject, var drawn: JsonObject?, var loaded: Boolean = false)

/** One Plotly figure; interactive (hover, zoom) inside a sandboxed WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PlotlyOutput(figure: JsonObject, dark: Boolean, modifier: Modifier = Modifier) {
    // Plotly's layout height is in CSS px, which WebView maps to dp.
    val height = figure.getAsJsonObject("layout")?.get("height")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(200, 1200) ?: 450
    fun redraw(view: WebView, state: PlotState) {
        if (!state.loaded || state.drawn === state.figure) return
        state.drawn = state.figure
        view.evaluateJavascript("avfRender(${scriptJson(state.figure)})", null)
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val state = view.tag as? PlotState ?: return
                        state.loaded = true
                        // A figure that changed while the page was loading.
                        redraw(view, state)
                    }
                }
            }
        },
        update = { view ->
            val state = view.tag as? PlotState
            if (state == null || state.dark != dark) {
                view.tag = PlotState(dark, figure, drawn = figure)
                view.loadDataWithBaseURL("https://avf-studio.invalid/", plotlyPage(figure, dark), "text/html", "utf-8", null)
            } else if (state.figure !== figure) {
                state.figure = figure
                redraw(view, state)
            }
        },
        onRelease = WebView::destroy,
        modifier = modifier.fillMaxWidth().height(height.dp).background(Color.Transparent, RoundedCornerShape(6.dp)),
    )
}
