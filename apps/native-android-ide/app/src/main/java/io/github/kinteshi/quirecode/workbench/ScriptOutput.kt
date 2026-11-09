package io.github.kinteshi.quirecode.workbench

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.JsonObject

/** Vega-Lite (Altair's default renderer) and Vega specs, by major version. */
internal val VEGA_MIME = Regex("""application/vnd\.(vega|vegalite)\.v(\d+)\+json""")

/** The JavaScript `bokeh.io.output_notebook()` publishes to load BokehJS. */
internal const val BOKEH_LOAD_MIME = "application/vnd.bokehjs_load.v0+json"

/** An ipywidgets view; drawing it needs the kernel's widget messages (comms), which the bridge doesn't carry. */
internal const val WIDGET_VIEW_MIME = "application/vnd.jupyter.widget-view+json"

/** The first Vega or Vega-Lite spec in a bundle: its kind, major version and spec. */
internal fun vegaSpec(data: JsonObject): Triple<String, Int, JsonObject>? =
    data.keySet().firstNotNullOfOrNull { key ->
        val match = VEGA_MIME.matchEntire(key) ?: return@firstNotNullOfOrNull null
        val spec = data.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@firstNotNullOfOrNull null
        Triple(match.groupValues[1], match.groupValues[2].toInt(), spec)
    }

/** JSON or JavaScript safe inside a script tag: `</` is escaped so the text can't close it. */
private fun inScript(text: String): String = text.replace("</", "<\\/")

/**
 * A page that draws one Vega(-Lite) spec with vega-embed from jsDelivr. Specs from Vega-Lite 6
 * (Vega 6) get the newer libraries; earlier ones the 5.x line, which also reads Vega-Lite 4.
 */
internal fun vegaPage(kind: String, version: Int, spec: JsonObject, dark: Boolean): String {
    val (vega, vegaLite, embed) = if (version >= 6) Triple("vega@6", "vega-lite@6", "vega-embed@7") else Triple("vega@5", "vega-lite@5", "vega-embed@6")
    val options = """{"mode":"${if (kind == "vega") "vega" else "vega-lite"}","actions":false${if (dark) ""","theme":"dark"""" else ""}}"""
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;background:transparent;font:13px sans-serif;color:${if (dark) "#cccccc" else "#333333"}}</style>
        <script src="https://cdn.jsdelivr.net/npm/$vega"></script>
        <script src="https://cdn.jsdelivr.net/npm/$vegaLite"></script>
        <script src="https://cdn.jsdelivr.net/npm/$embed"></script>
        </head><body><div id="vis"></div><script>
        var spec = ${inScript(spec.toString())};
        // Vega's dark theme paints its own background; keep the app's.
        if (${dark}) { spec.background = "transparent"; }
        if (window.vegaEmbed) { vegaEmbed('#vis', spec, $options).catch(function (e) { document.body.textContent = String(e); }); }
        else { document.body.textContent = 'Could not load Vega; the first chart needs an internet connection.'; }
        </script></body></html>
    """.trimIndent()
}

private val bokehVersion = Regex(""""version"\s*:\s*"(\d+\.\d+\.\d+)"""")

/**
 * A page for HTML that runs scripts. [preamble] runs first: for Bokeh, the loader that
 * `output_notebook()` published. Bokeh output without one loads BokehJS from its CDN at the
 * version the output names.
 */
internal fun scriptHtmlPage(html: String, dark: Boolean, preamble: String? = null): String {
    val loader = when {
        preamble != null -> "<script>${inScript(preamble)}</script>"
        "Bokeh" in html -> bokehVersion.find(html)?.groupValues?.get(1)?.let { version ->
            listOf("bokeh", "bokeh-gl", "bokeh-widgets", "bokeh-tables").joinToString("") { name ->
                """<script src="https://cdn.bokeh.org/bokeh/release/$name-$version.min.js"></script>"""
            }
        }.orEmpty()
        else -> ""
    }
    // Same theme rules as static HTML outputs, so tables inside script output look alike.
    return htmlOutputPage(loader + html, dark)
}

/**
 * Output that needs JavaScript (Altair/Vega charts, Bokeh, folium maps, pyvis), as VS Code runs
 * it: in its own WebView with scripts and network on, but no bridge into the app and no file or
 * content access. The view grows with its content, up to [maxHeightDp].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ScriptOutput(page: String, modifier: Modifier = Modifier, minHeightDp: Int = 60, maxHeightDp: Int = 900) {
    var height by remember(page) { mutableIntStateOf(minHeightDp) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) = measure(view, page) { height = it.coerceIn(minHeightDp, maxHeightDp) }
                }
            }
        },
        update = { view ->
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL("https://avf-studio.invalid/", page, "text/html", "utf-8", null)
            }
        },
        onRelease = WebView::destroy,
        modifier = modifier.fillMaxWidth().height(height.dp),
    )
}

/**
 * Libraries draw after the page loads (Bokeh polls for BokehJS, Vega fetches its scripts), so the
 * height is read a few times over the first seconds. CSS pixels are dp in a WebView.
 */
internal fun measure(view: WebView, page: String, onHeight: (Int) -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    listOf(0L, 300L, 800L, 1500L, 3000L, 6000L).forEach { delay ->
        handler.postDelayed({
            if (view.tag != page) return@postDelayed
            view.evaluateJavascript("Math.ceil(document.documentElement.scrollHeight)") { value ->
                value?.trim('"')?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }?.let(onHeight)
            }
        }, delay)
    }
}
