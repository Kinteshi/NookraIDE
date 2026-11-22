package io.github.kinteshi.nookraide.workbench

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.kinteshi.nookraide.notebook.WidgetCustomEvent
import io.github.kinteshi.nookraide.notebook.WidgetModel
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.Base64
import kotlin.math.roundToInt

/** CSS named colors supported cleanly by [ColorPicker] and [parseCssColor]. */
private val NAMED_CSS_COLORS = mapOf(
    "black" to Color(0xFF000000),
    "white" to Color(0xFFFFFFFF),
    "red" to Color(0xFFFF0000),
    "green" to Color(0xFF008000),
    "lime" to Color(0xFF00FF00),
    "blue" to Color(0xFF0000FF),
    "yellow" to Color(0xFFFFFF00),
    "cyan" to Color(0xFF00FFFF),
    "aqua" to Color(0xFF00FFFF),
    "magenta" to Color(0xFFFF00FF),
    "fuchsia" to Color(0xFFFF00FF),
    "orange" to Color(0xFFFFA500),
    "purple" to Color(0xFF800080),
    "rebeccapurple" to Color(0xFF663399),
    "pink" to Color(0xFFFFC0CB),
    "gray" to Color(0xFF808080),
    "grey" to Color(0xFF808080),
    "silver" to Color(0xFFC0C0C0),
    "maroon" to Color(0xFF800000),
    "navy" to Color(0xFF000080),
    "teal" to Color(0xFF008080),
    "olive" to Color(0xFF808000),
    "transparent" to Color(0x00000000),
)

/** Parses CSS hex (`#rgb`, `#rrggbb`, `#rrggbbaa`), `rgb(...)`/`rgba(...)`, or named colors. */
internal fun parseCssColor(raw: String): Color? {
    val s = raw.trim().lowercase()
    if (s.isEmpty()) return null
    NAMED_CSS_COLORS[s]?.let { return it }
    if (s.startsWith("#")) {
        val hex = s.substring(1)
        val argb = when (hex.length) {
            3 -> {
                val r = hex[0].toString().repeat(2)
                val g = hex[1].toString().repeat(2)
                val b = hex[2].toString().repeat(2)
                "ff$r$g$b"
            }
            4 -> {
                val r = hex[0].toString().repeat(2)
                val g = hex[1].toString().repeat(2)
                val b = hex[2].toString().repeat(2)
                val a = hex[3].toString().repeat(2)
                "$a$r$g$b"
            }
            6 -> "ff$hex"
            8 -> "${hex.substring(6, 8)}${hex.substring(0, 6)}"
            else -> return null
        }
        val parsed = argb.toLongOrNull(16) ?: return null
        return Color(parsed.toInt())
    }
    if (s.startsWith("rgb(") || s.startsWith("rgba(")) {
        val inner = s.substringAfter('(').substringBeforeLast(')').split(',')
        if (inner.size < 3) return null
        val r = inner[0].trim().toIntOrNull()?.coerceIn(0, 255) ?: return null
        val g = inner[1].trim().toIntOrNull()?.coerceIn(0, 255) ?: return null
        val b = inner[2].trim().toIntOrNull()?.coerceIn(0, 255) ?: return null
        val a = inner.getOrNull(3)?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f)?.let { (it * 255f).roundToInt() } ?: 255
        return Color(red = r, green = g, blue = b, alpha = a)
    }
    return null
}

/** Lowercase `#rrggbb` representation matching `ipywidgets.ColorPicker`. */
internal fun formatCssHex(color: Color): String {
    val rgb = color.toArgb() and 0xFFFFFF
    return "#%06x".format(rgb)
}

/** Parses optional CSS pixel/number dimensions such as `"320px"` or `"240"`; returns `null` for `auto`/`%`. */
internal fun parseCssPxOrDp(raw: String): Float? {
    val s = raw.trim().lowercase()
    if (s.isEmpty() || s == "auto" || s.endsWith("%")) return null
    val num = when {
        s.endsWith("px") -> s.removeSuffix("px").trim()
        s.endsWith("pt") -> return s.removeSuffix("pt").trim().toFloatOrNull()?.times(1.333f)?.takeIf { it > 0f }
        else -> s
    }
    return num.toFloatOrNull()?.takeIf { it > 0f }
}

/**
 * Decodes `ipywidgets` `DatePicker` / `Datetime` trait values (`{"year": Y, "month": 0..11, "date": D}`
 * per `ipywidgets.widgets.trait_types.date_to_json`) or ISO-8601 date strings.
 */
internal fun parseIpyWidgetDate(element: JsonElement?): LocalDate? {
    if (element == null || element.isJsonNull) return null
    if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
        return runCatching { LocalDate.parse(element.asString.trim().take(10)) }.getOrNull()
    }
    val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val year = obj.get("year")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
    val monthZeroBased = obj.get("month")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
    val day = (obj.get("date") ?: obj.get("day"))?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
    return runCatching { LocalDate.of(year, monthZeroBased + 1, day) }.getOrNull()
}

/** Encodes a [LocalDate] into `ipywidgets` `DatePicker` JSON (`month` is 0-indexed). */
internal fun toIpyWidgetDateJson(date: LocalDate): JsonObject = JsonObject().apply {
    addProperty("year", date.year)
    addProperty("month", date.monthValue - 1)
    addProperty("date", date.dayOfMonth)
}

internal fun mediaWidgetMime(kind: String, format: String): String = when (format.trim().lowercase()) {
    "svg+xml", "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpeg", "jpg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "mp3", "mpeg" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "ogg" -> if (kind == "Video") "video/ogg" else "audio/ogg"
    "aac" -> "audio/aac"
    "flac" -> "audio/flac"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    else -> if (kind == "Audio") "audio/mpeg" else if (kind == "Video") "video/mp4" else "image/png"
}

/** Pure byte-range slicing metadata for HTTP `200`/`206` responses. */
internal data class ByteRangeSlice(
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: Map<String, String>,
    val offset: Int,
    val length: Int,
)

internal fun computeByteRangeSlice(total: Int, rangeHeader: String?): ByteRangeSlice {
    val match = rangeHeader?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
    if (match != null && total > 0) {
        val rawStart = match.groupValues[1]
        val rawEnd = match.groupValues[2]
        val start = rawStart.toIntOrNull()?.coerceIn(0, total - 1) ?: 0
        val end = rawEnd.toIntOrNull()?.coerceIn(start, total - 1) ?: (total - 1)
        val length = (end - start + 1).coerceAtLeast(0)
        return ByteRangeSlice(
            statusCode = 206,
            reasonPhrase = "Partial Content",
            headers = mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Range" to "bytes $start-$end/$total",
                "Content-Length" to length.toString(),
            ),
            offset = start,
            length = length,
        )
    }
    return ByteRangeSlice(
        statusCode = 200,
        reasonPhrase = "OK",
        headers = mapOf("Accept-Ranges" to "bytes", "Content-Length" to total.toString()),
        offset = 0,
        length = total,
    )
}

/** Builds a `200` or `206 Partial Content` response for HTML5 `<audio>` / `<video>` byte-range seeking. */
internal fun buildByteRangeResponse(bytes: ByteArray, mimeType: String, rangeHeader: String?): WebResourceResponse {
    val slice = computeByteRangeSlice(bytes.size, rangeHeader)
    return WebResourceResponse(
        mimeType,
        null,
        slice.statusCode,
        slice.reasonPhrase,
        slice.headers,
        ByteArrayInputStream(bytes, slice.offset, slice.length),
    )
}

/** HTML5 `<audio>` / `<video>` player backed by a local byte-range intercept (or `url` format). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MediaWidgetView(
    model: WidgetModel,
    isVideo: Boolean,
    bytes: ByteArray?,
    format: String,
    autoplay: Boolean,
    loop: Boolean,
    controls: Boolean,
    widthDp: Float?,
    heightDp: Float?,
    modifier: Modifier = Modifier,
) {
    val kind = if (isVideo) "Video" else "Audio"
    val isUrl = format.equals("url", ignoreCase = true)
    val mimeType = remember(kind, format) { mediaWidgetMime(kind, format) }
    val remoteUrl = remember(bytes, isUrl) { if (isUrl && bytes != null) bytes.toString(Charsets.UTF_8).trim() else "" }
    val mediaUri = remember(model.id, model.revision, isUrl, remoteUrl) {
        if (isUrl) remoteUrl else "https://avf-studio.invalid/widget-media/${model.id}/${model.revision}"
    }
    if (!isUrl && (bytes == null || bytes.isEmpty()) || (isUrl && remoteUrl.isEmpty())) {
        Text(
            "[$kind widget: empty media buffer]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val page = remember(mediaUri, mimeType, isVideo, autoplay, loop, controls, widthDp, heightDp) {
        val attrs = buildList {
            if (controls) add("controls")
            if (autoplay) add("autoplay")
            if (loop) add("loop")
            if (isVideo) add("playsinline")
        }.joinToString(" ")
        val tag = if (isVideo) {
            val style = buildString {
                append("max-width:100%;border-radius:8px;")
                if (widthDp != null) append("width:${widthDp.toInt()}px;") else append("width:100%;")
                if (heightDp != null) append("height:${heightDp.toInt()}px;") else append("max-height:360px;")
            }
            """<video $attrs style="$style" src="${escapeHtmlAttr(mediaUri)}"></video>"""
        } else {
            """<audio $attrs style="width:100%;" src="${escapeHtmlAttr(mediaUri)}"></audio>"""
        }
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
           <style>html,body{margin:0;padding:0;background:transparent;}</style></head>
           <body>$tag</body></html>""".trimIndent()
    }
    val minHeight = if (isVideo) (heightDp?.toInt()?.coerceIn(120, 380) ?: 200) else 58
    val maxWidthModifier = if (widthDp != null) modifier.widthIn(max = widthDp.dp) else modifier.fillMaxWidth()
    var height by remember(page) { mutableIntStateOf(minHeight) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = !autoplay
                settings.cacheMode = WebSettings.LOAD_DEFAULT
            }
        },
        update = { view ->
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    if (!isUrl && request.url.toString() == mediaUri && bytes != null) {
                        return buildByteRangeResponse(bytes, mimeType, request.requestHeaders["Range"])
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    measure(view, page) { h -> height = h.coerceIn(minHeight, 420) }
                }
            }
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL("https://avf-studio.invalid/", page, "text/html", "utf-8", null)
            }
        },
        onRelease = WebView::destroy,
        modifier = maxWidthModifier.height(height.dp),
    )
}

/**
 * Sandboxed WebView renderer for binary-protocol (`ipycanvas`, `bqplot`) and third-party
 * widget libraries (`ipyleaflet`, `anywidget`, etc.) using `@jupyter-widgets/html-manager`
 * (or inline ESM for `AnyModel`) with a bidirectional comm bridge back to [WorkbenchController].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ThirdPartyWidgetView(
    controller: WorkbenchController,
    session: NotebookSession,
    model: WidgetModel,
    modifier: Modifier = Modifier,
) {
    val label = model.name.removeSuffix("Model").ifEmpty { "Third-party" }
    if (!controller.settings.scriptOutputs) {
        Text(
            "Interactive widget ($label): turn on Settings → Notebooks → Scripts in outputs to render",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 2.dp),
        )
        return
    }

    val dark = LocalDarkTheme.current
    val snapshot = remember(model.id, model.name) { session.widgets.exportStateJson(model.id) }
    val initSeq = remember(model.id) { session.widgets.eventSeq }
    val initialSubtreeIds = remember(model.id) { session.widgets.collectSubtreeIds(model.id) }
    val initialCustomEvents = remember(model.id) {
        session.widgets.commLog
            .filter { it.method == "custom" && it.seq <= initSeq && it.commId in initialSubtreeIds }
    }
    val page = remember(model.id, model.name, dark) {
        thirdPartyWidgetPage(
            rootId = model.id,
            modelName = model.name,
            stateBundle = snapshot,
            initialCustom = initialCustomEvents.map { Triple(it.commId, it.payload, it.buffers) },
            dark = dark,
        )
    }

    var height by remember(page) { mutableIntStateOf(60) }
    var webViewRef by remember(page) { mutableStateOf<WebView?>(null) }
    var lastDeliveredSeq by remember(page) { mutableStateOf(initSeq) }

    val currentSeq = session.widgets.eventSeq
    LaunchedEffect(currentSeq, webViewRef) {
        val view = webViewRef ?: return@LaunchedEffect
        if (currentSeq <= lastDeliveredSeq) return@LaunchedEffect
        val subtreeIds = session.widgets.collectSubtreeIds(model.id)
        val pending = session.widgets.commLog.filter { it.seq > lastDeliveredSeq && it.commId in subtreeIds }
        lastDeliveredSeq = currentSeq
        val encoder = Base64.getEncoder()
        pending.forEach { entry ->
            if (entry.method == "update" || entry.method == "custom") {
                val payloadStr = JsonObject().apply {
                    addProperty("method", entry.method)
                    if (entry.method == "update") {
                        add("state", entry.payload.get("state") ?: JsonObject())
                        entry.payload.get("buffer_paths")?.let { add("buffer_paths", it) }
                    } else {
                        add("content", entry.payload)
                    }
                }.toString()
                val bufArray = com.google.gson.JsonArray().apply {
                    entry.buffers.forEach { bytes -> add(encoder.encodeToString(bytes)) }
                }.toString()
                val jsCall = "window.__quirePushComm && window.__quirePushComm(${jsonLiteral(entry.commId)}, $payloadStr, $bufArray);"
                view.evaluateJavascript(jsCall, null)
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun sendCommMsg(commId: String, dataJson: String, buffersJson: String) {
                                Handler(Looper.getMainLooper()).post {
                                    controller.sendRawWidgetComm(session, commId, dataJson, buffersJson)
                                }
                            }

                            @JavascriptInterface
                            fun reportHeight(heightPx: Int) {
                                val next = heightPx.coerceIn(36, 900)
                                Handler(Looper.getMainLooper()).post { height = next }
                            }
                        },
                        "QuireWidgets",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript("window.__quireReportSize && window.__quireReportSize()", null)
                        }
                    }
                    webViewRef = this
                }
            },
            update = { view ->
                webViewRef = view
                if (view.tag != page) {
                    view.tag = page
                    view.loadDataWithBaseURL("https://avf-studio.invalid/", page, "text/html", "utf-8", null)
                }
            },
            onRelease = { view ->
                if (webViewRef === view) webViewRef = null
                view.destroy()
            },
            modifier = Modifier.fillMaxWidth().height(height.dp),
        )
    }
}

private fun escapeHtmlAttr(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

private fun jsonLiteral(value: String): String =
    com.google.gson.JsonPrimitive(value).toString().replace("</", "<\\/")

internal fun thirdPartyWidgetPage(
    rootId: String,
    modelName: String,
    stateBundle: JsonObject,
    initialCustom: List<Triple<String, JsonObject, List<ByteArray>>>,
    dark: Boolean,
): String {
    val encoder = Base64.getEncoder()
    val customArray = com.google.gson.JsonArray().apply {
        initialCustom.forEach { (commId, content, bufs) ->
            add(JsonObject().apply {
                addProperty("commId", commId)
                add("content", content)
                add("buffers", com.google.gson.JsonArray().apply {
                    bufs.forEach { add(encoder.encodeToString(it)) }
                })
            })
        }
    }
    val textColor = if (dark) "#d4d4d4" else "#1f1f1f"
    val bundleJson = stateBundle.toString().replace("</", "<\\/")
    val customJson = customArray.toString().replace("</", "<\\/")
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        html,body{margin:0;padding:0;background:transparent;color:$textColor;font:13px/1.4 sans-serif;overflow-x:auto;}
        #root{width:100%;}
        canvas{max-width:100%;height:auto;}
        .quire-fallback{font-size:12px;opacity:.75;padding:6px 0;}
        </style>
        <script src="https://cdn.jsdelivr.net/npm/requirejs@2.3.6/require.min.js"></script>
        <script data-jupyter-widgets-cdn="https://cdn.jsdelivr.net/npm/" src="https://cdn.jsdelivr.net/npm/@jupyter-widgets/html-manager@1/dist/embed-amd.js"></script>
        </head><body><div id="root"></div><script>
        (function(){
          var ROOT_ID = ${jsonLiteral(rootId)};
          var MODEL_NAME = ${jsonLiteral(modelName)};
          var BUNDLE = $bundleJson;
          var QUEUED_CUSTOM = $customJson;
          var rootEl = document.getElementById('root');
          var activeManager = null;
          var anyModels = {};

          function reportSize() {
            var h = Math.ceil(rootEl.getBoundingClientRect().height || rootEl.scrollHeight);
            if (h > 0 && window.QuireWidgets) { window.QuireWidgets.reportHeight(h); }
          }
          window.__quireReportSize = reportSize;
          if (typeof ResizeObserver !== 'undefined') {
            new ResizeObserver(reportSize).observe(rootEl);
          }

          function b64ToDataView(b64) {
            var bin = atob(b64);
            var arr = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
            return new DataView(arr.buffer);
          }
          function bufToB64(buf) {
            var bytes = buf instanceof Uint8Array ? buf : (ArrayBuffer.isView(buf) ? new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength) : new Uint8Array(buf));
            var chunks = [];
            for (var i = 0; i < bytes.length; i++) chunks.push(String.fromCharCode(bytes[i]));
            return btoa(chunks.join(''));
          }

          function patchComm(model, id) {
            if (!model || model.__quirePatched) return;
            model.__quirePatched = true;
            var listeners = {};
            var comm = {
              send: function(data, callbacks, metadata, buffers) {
                var encoded = (buffers || []).map(bufToB64);
                if (window.QuireWidgets) {
                  window.QuireWidgets.sendCommMsg(id, JSON.stringify(data || {}), JSON.stringify(encoded));
                }
              }
            };
            model.comm = comm;
            model.save_changes = function(callbacks) {
              var changed = model.changedAttributes ? model.changedAttributes() : {};
              if (changed && Object.keys(changed).length > 0) {
                comm.send({ method: 'update', state: changed, buffer_paths: [] }, callbacks, {}, []);
              }
            };
            if (typeof model.on === 'function') {
              model.on('msg:custom', function() {});
            }
          }

          function applyCommToModel(model, data, views) {
            if (!model) return;
            if (data.method === 'update') {
              var state = Object.assign({}, data.state || {});
              var paths = data.buffer_paths || [];
              for (var i = 0; i < paths.length; i++) {
                var p = paths[i];
                if (p && p.length && views[i]) {
                  var target = state;
                  for (var j = 0; j < p.length - 1; j++) {
                    if (!target[p[j]] || typeof target[p[j]] !== 'object') target[p[j]] = {};
                    target = target[p[j]];
                  }
                  target[p[p.length - 1]] = views[i];
                }
              }
              if (typeof model.set === 'function') { model.set(state); }
            } else if (data.method === 'custom' && typeof model.trigger === 'function') {
              model.trigger('msg:custom', data.content || {}, views);
            }
            reportSize();
          }

          window.__quirePushComm = function(commId, data, buffersB64) {
            var views = (buffersB64 || []).map(b64ToDataView);
            if (anyModels[commId]) {
              anyModels[commId].__apply(data, views);
              return;
            }
            if (activeManager && typeof activeManager.get_model === 'function') {
              Promise.resolve(activeManager.get_model(commId)).then(function(m) {
                patchComm(m, commId);
                applyCommToModel(m, data, views);
              }).catch(function(){});
            }
          };

          async function renderAnyWidget() {
            var entry = (BUNDLE.state || {})[ROOT_ID];
            var st = Object.assign({}, entry && entry.state ? entry.state : {});
            var esm = st._esm;
            if (!esm || typeof esm !== 'string') return false;
            if (st._css) {
              var styleEl = document.createElement('style');
              styleEl.textContent = st._css;
              document.head.appendChild(styleEl);
            }
            var url = /^https?:\/\//.test(esm.trim())
              ? esm.trim()
              : URL.createObjectURL(new Blob([esm], { type: 'text/javascript' }));
            var mod = await import(url);
            var plugin = mod && mod.default ? (typeof mod.default === 'function' ? mod.default() : mod.default) : mod;
            if (!plugin || typeof plugin.render !== 'function') return false;
            var handlers = {};
            var proxy = {
              get: function(k) { return st[k]; },
              set: function(k, v) { st[k] = v; (handlers['change:' + k] || []).forEach(function(fn){ fn(); }); },
              save_changes: function() {
                if (window.QuireWidgets) {
                  window.QuireWidgets.sendCommMsg(ROOT_ID, JSON.stringify({ method: 'update', state: st, buffer_paths: [] }), '[]');
                }
              },
              send: function(content, buffers) {
                var encoded = (buffers || []).map(bufToB64);
                if (window.QuireWidgets) {
                  window.QuireWidgets.sendCommMsg(ROOT_ID, JSON.stringify({ method: 'custom', content: content }), JSON.stringify(encoded));
                }
              },
              on: function(ev, fn) { (handlers[ev] = handlers[ev] || []).push(fn); },
              off: function(ev, fn) { handlers[ev] = (handlers[ev] || []).filter(function(f){ return f !== fn; }); },
              __apply: function(data, views) {
                if (data.method === 'update') {
                  Object.keys(data.state || {}).forEach(function(k) { proxy.set(k, data.state[k]); });
                } else if (data.method === 'custom') {
                  (handlers['msg:custom'] || []).forEach(function(fn){ fn(data.content, views); });
                }
                reportSize();
              }
            };
            anyModels[ROOT_ID] = proxy;
            await plugin.render({ model: proxy, el: rootEl });
            reportSize();
            return true;
          }

          async function start() {
            try {
              if (MODEL_NAME === 'AnyModel' && await renderAnyWidget()) return;
            } catch (e) {}
            if (typeof window.require !== 'function') {
              rootEl.innerHTML = '<div class="quire-fallback">Could not load widget runtime (' + MODEL_NAME.replace(/Model$/, '') + '); an internet connection is needed on first render.</div>';
              reportSize();
              return;
            }
            window.require(['@jupyter-widgets/html-manager'], async function(hm) {
              try {
                var manager = new hm.HTMLManager();
                activeManager = manager;
                await manager.set_state(BUNDLE);
                Object.keys(BUNDLE.state || {}).forEach(function(id) {
                  Promise.resolve(manager.get_model(id)).then(function(m){ patchComm(m, id); }).catch(function(){});
                });
                var rootModel = await manager.get_model(ROOT_ID);
                patchComm(rootModel, ROOT_ID);
                var view = await manager.create_view(rootModel);
                await manager.display_view(undefined, view, { el: rootEl });
                for (var i = 0; i < QUEUED_CUSTOM.length; i++) {
                  var q = QUEUED_CUSTOM[i];
                  window.__quirePushComm(q.commId, { method: 'custom', content: q.content }, q.buffers);
                }
                setTimeout(reportSize, 150);
                setTimeout(reportSize, 600);
              } catch (err) {
                rootEl.innerHTML = '<div class="quire-fallback">' + (err && err.message ? err.message : String(err)) + '</div>';
                reportSize();
              }
            }, function() {
              rootEl.innerHTML = '<div class="quire-fallback">Could not initialize @jupyter-widgets/html-manager for ' + MODEL_NAME + '.</div>';
              reportSize();
            });
          }
          start();
        })();
        </script></body></html>
    """.trimIndent()
}
