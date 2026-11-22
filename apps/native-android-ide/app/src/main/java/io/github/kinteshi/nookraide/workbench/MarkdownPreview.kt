package io.github.kinteshi.nookraide.workbench

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kinteshi.nookraide.document.DocumentSession
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import kotlinx.coroutines.delay
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Block
import org.commonmark.node.Document
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Markdown as VS Code's preview renders it: CommonMark with GitHub's tables, strikethrough, task
 * lists and autolinks. Block elements carry their first source line (`data-line`), so the
 * preview can scroll to the line the editor shows. Raw HTML passes through, as in VS Code; the
 * page's content security policy keeps any script in it from running.
 */
internal object MarkdownRenderer {
    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
    )
    private val parser = Parser.builder().extensions(extensions).includeSourceSpans(IncludeSourceSpans.BLOCKS).build()
    private val renderer = HtmlRenderer.builder()
        .extensions(extensions)
        .sanitizeUrls(true)
        .attributeProviderFactory {
            AttributeProvider { node, _, attributes ->
                if (node is Block && node !is Document) {
                    node.sourceSpans.firstOrNull()?.let { attributes["data-line"] = it.lineIndex.toString() }
                }
            }
        }
        .build()

    fun render(markdown: String): String = renderer.render(parser.parse(markdown))

    fun parse(markdown: String): org.commonmark.node.Node = parser.parse(markdown)
}

internal fun Color.css(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/**
 * The base URL for a Markdown file in [directory] (guest `/...`, device folder `tree:/...`, or
 * device file `device:`), with each path segment encoded once so percent-encoded SAF roots
 * (`%3A`, `%2F`) and spaces survive URL resolution intact.
 */
internal fun markdownBaseUrl(directory: String): String {
    val clean = directory.trimEnd('/')
    if (clean.isEmpty()) return "https://$MARKDOWN_BASE_HOST/"
    val encoded = clean.split('/').filter { it.isNotEmpty() }.joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, "UTF-8")
            .replace("+", "%20")
            .replace("%3A", ":")
    }
    return "https://$MARKDOWN_BASE_HOST/$encoded/"
}

/**
 * Maps a resolved `https://markdown.avf/...` URL back to its workspace/guest/device path
 * (`/home/...`, `tree:/...`, or `device:/...`), or null when [url] is not on [MARKDOWN_BASE_HOST].
 */
internal fun markdownUrlPath(url: String): String? {
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (parsed.host != MARKDOWN_BASE_HOST) return null
    val path = parsed.path?.takeIf { it.isNotEmpty() } ?: return null
    return if (path.startsWith("/tree:/") || path.startsWith("/device:/")) path.removePrefix("/") else path
}

/** The preview's look in the app's colours (shared with notebook Markdown cells drawn as pages). */
internal fun markdownStyles(colors: ColorScheme): String = """
    html,body{margin:0;background:transparent;color:${colors.onSurface.css()};font:15px/1.6 sans-serif;word-wrap:break-word}
    h1,h2{border-bottom:1px solid ${colors.outlineVariant.css()};padding-bottom:.3em}
    h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.2em 0 .6em}
    a{color:${colors.primary.css()};text-decoration:none}
    code,pre{font-family:monospace;font-size:13px;background:${colors.surfaceContainerHigh.css()};border-radius:6px}
    code{padding:.15em .35em}
    pre{padding:12px;overflow-x:auto}
    pre code{padding:0;background:none}
    blockquote{margin:0;padding:0 1em;color:${colors.onSurfaceVariant.css()};border-left:4px solid ${colors.outlineVariant.css()}}
    table{border-collapse:collapse;display:block;overflow-x:auto}
    th,td{border:1px solid ${colors.outlineVariant.css()};padding:6px 12px}
    img{max-width:100%}
    hr{border:0;border-top:1px solid ${colors.outlineVariant.css()}}
    li>input[type=checkbox]{margin-right:.4em}
""".trimIndent()

/** The page around the rendered Markdown, in the app's colours; only its own nonce'd script runs. */
internal fun markdownPage(body: String, colors: ColorScheme, nonce: String): String = """
    <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; script-src 'nonce-$nonce'">
    <style>
    ${markdownStyles(colors)}
    main{padding:12px 20px 40vh;max-width:880px}
    </style></head><body><main id="content">$body</main>
    <script nonce="$nonce">
    function setContent(html) { document.getElementById('content').innerHTML = html; }
    // Scrolls to source line [line]: between the blocks around it, in proportion.
    function scrollToLine(line) {
      var blocks = document.querySelectorAll('[data-line]'), before = null, after = null;
      for (var i = 0; i < blocks.length; i++) {
        if (+blocks[i].getAttribute('data-line') <= line) before = blocks[i]; else { after = blocks[i]; break; }
      }
      if (!before) { window.scrollTo(0, 0); return; }
      var top = before.getBoundingClientRect().top + window.scrollY, from = +before.getAttribute('data-line');
      if (after) {
        var to = +after.getAttribute('data-line'), next = after.getBoundingClientRect().top + window.scrollY;
        top += (next - top) * Math.min(1, (line - from) / Math.max(1, to - from));
      }
      window.scrollTo(0, Math.max(0, top - 8));
    }
    </script></body></html>
""".trimIndent()

private val gson = Gson()
private val random = SecureRandom()

/**
 * The preview of [document], following its edits. Relative links and images resolve against
 * its folder ([MARKDOWN_BASE_HOST]); images come from the guest, links go through
 * [WorkbenchController.openMarkdownLink]. With [followEditor] it scrolls with the editor.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MarkdownPreview(controller: WorkbenchController, document: DocumentSession, followEditor: Boolean, modifier: Modifier = Modifier) {
    controller.editRevision
    controller.documentRevision
    val colors = MaterialTheme.colorScheme
    val text = document.text
    // A short pause while typing, so a long file isn't rendered on every key.
    val html by produceState(remember(document.path) { MarkdownRenderer.render(text) }, text) {
        delay(150)
        value = MarkdownRenderer.render(text)
    }
    val nonce = remember { ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) } }
    val directory = document.path.substringBeforeLast('/', "")
    val baseUrl = markdownBaseUrl(directory)
    val holder = remember { arrayOfNulls<WebView>(1) }
    // What the page should show; an edit before the page finished loading is applied then.
    val latest = remember { arrayOfNulls<String>(1) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                holder[0] = this
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        latest[0]?.let { view.evaluateJavascript("setContent(${gson.toJson(it)})", null) }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        // Anchors within the page scroll as usual.
                        if (url.host == MARKDOWN_BASE_HOST && url.path == android.net.Uri.parse(baseUrl).path && url.fragment != null) return false
                        controller.post { controller.openMarkdownLink(url.toString()) }
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url
                        if (url.host != MARKDOWN_BASE_HOST) return null
                        val bytes = markdownUrlPath(url.toString())?.let(controller::readPreviewImage)
                            ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        val type = android.webkit.MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(url.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty())
                            ?: "application/octet-stream"
                        return WebResourceResponse(type, null, ByteArrayInputStream(bytes))
                    }
                }
            }
        },
        update = { view ->
            // A new theme or folder loads the page again; edits only replace its content, so
            // the scroll position stays.
            val pageKey = "$baseUrl|${colors.onSurface.toArgb()}|${colors.primary.toArgb()}|${colors.surfaceContainerHigh.toArgb()}"
            latest[0] = html
            if (view.tag != pageKey) {
                view.tag = pageKey
                view.loadDataWithBaseURL(baseUrl, markdownPage(html, colors, nonce), "text/html", "utf-8", null)
            } else {
                view.evaluateJavascript("setContent(${gson.toJson(html)})", null)
            }
        },
        onRelease = { view ->
            holder[0] = null
            view.destroy()
        },
        modifier = modifier,
    )
    if (followEditor) {
        LaunchedEffect(controller.editorViewportTick, html) {
            val first = controller.editorSurface?.visibleLines?.first ?: return@LaunchedEffect
            holder[0]?.evaluateJavascript("scrollToLine($first)", null)
        }
    }
}
