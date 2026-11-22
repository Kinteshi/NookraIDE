package io.github.kinteshi.nookraide.workbench

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.kinteshi.nookraide.notebook.OutputPresentation
import com.caverock.androidsvg.SVG
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser

internal const val SVG_MIME = "image/svg+xml"
internal const val LATEX_MIME = "text/latex"
internal const val JSON_MIME = "application/json"

private const val KATEX = "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist"

/** JSON as text, indented, for Copy. A string value is the JSON text itself (some kernels send it so). */
internal fun prettyJson(value: JsonElement): String {
    val element = if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        runCatching { JsonParser.parseString(value.asString) }.getOrDefault(value)
    } else value
    return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(element)
}

/**
 * A page that draws `text/latex` with KaTeX from jsDelivr, as JupyterLab does with MathJax. The
 * text keeps its delimiters (`$…$`, `$$…$$`, `\[…\]`, `\begin{align}`); text without any is
 * drawn as one display formula. Offline, the source is shown instead.
 */
internal fun latexPage(latex: String, dark: Boolean): String {
    val color = if (dark) "#d4d4d4" else "#1f1f1f"
    val source = com.google.gson.JsonPrimitive(latex.trim()).toString().replace("</", "<\\/")
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="$KATEX/katex.min.css">
        <script src="$KATEX/katex.min.js"></script>
        <script src="$KATEX/contrib/auto-render.min.js"></script>
        <style>html,body{margin:0;padding:2px 0;background:transparent;color:$color;font:14px sans-serif}
        #m{overflow-x:auto;white-space:pre-wrap}.katex-display{margin:4px 0}</style>
        </head><body><div id="m"></div><script>
        var source = $source;
        var box = document.getElementById('m');
        box.textContent = source;
        if (window.katex && window.renderMathInElement) {
          var delimited = /\$|\\\[|\\\(|\\begin\{/.test(source);
          if (delimited) {
            renderMathInElement(box, {throwOnError: false, delimiters: [
              {left: '$$', right: '$$', display: true}, {left: '\\[', right: '\\]', display: true},
              {left: '\\begin{equation}', right: '\\end{equation}', display: true},
              {left: '\\begin{align}', right: '\\end{align}', display: true},
              {left: '\\begin{align*}', right: '\\end{align*}', display: true},
              {left: '\\begin{gather}', right: '\\end{gather}', display: true},
              {left: '$', right: '$', display: false}, {left: '\\(', right: '\\)', display: false}]});
          } else {
            katex.render(source, box, {throwOnError: false, displayMode: true});
          }
          box.style.whiteSpace = 'normal';
        }
        </script></body></html>
    """.trimIndent()
}

/** An `image/svg+xml` output (matplotlib's svg format, graphviz), fitted to the width; [fallback] when it can't be read. */
@Composable
internal fun SvgOutput(source: String, modifier: Modifier, fallback: @Composable () -> Unit) {
    val svg = remember(source) {
        runCatching {
            SVG.getFromString(source).also { svg ->
                val box = svg.documentViewBox
                val width = svg.documentWidth.takeIf { it > 0 } ?: box?.width()?.takeIf { it > 0 } ?: 300f
                val height = svg.documentHeight.takeIf { it > 0 } ?: box?.height()?.takeIf { it > 0 } ?: 150f
                if (box == null) svg.setDocumentViewBox(0f, 0f, width, height)
                svg.setDocumentWidth(width)
                svg.setDocumentHeight(height)
            }
        }.getOrNull()
    }
    if (svg == null) {
        fallback()
        return
    }
    val width = svg.documentWidth
    val height = svg.documentHeight
    // One SVG unit per dp at most, as a browser draws CSS pixels; wider ones shrink to fit.
    val maxWidth = width.dp
    // The output's modifier fills the width; the picture inside keeps its own size.
    Box(modifier) {
        Canvas(
            Modifier
                .widthIn(max = maxWidth.coerceAtLeast(48.dp))
                .aspectRatio(width / height)
                .heightIn(max = 520.dp)
                .background(Color.White, RoundedCornerShape(6.dp)),
        ) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.save()
                canvas.nativeCanvas.scale(size.width / width, size.height / height)
                svg.renderToCanvas(canvas.nativeCanvas, android.graphics.RectF(0f, 0f, width, height))
                canvas.nativeCanvas.restore()
            }
        }
    }
}

/** A row of a JSON tree: a key (or index) and a value, or a summary for an object or array. */
internal data class JsonRow(
    val path: String,
    val depth: Int,
    val key: String?,
    val value: JsonElement?,
    val children: Int,
    val expanded: Boolean,
    /** "… 120 more" after the first [JSON_CHILD_LIMIT] children. */
    val moreCount: Int = 0,
)

internal const val JSON_CHILD_LIMIT = 200

/** The visible rows of [root] with the nodes in [expanded] open (by path), VS Code's JSON output tree. */
internal fun jsonTreeRows(root: JsonElement, expanded: Set<String>): List<JsonRow> = buildList {
    fun visit(element: JsonElement, path: String, depth: Int, key: String?) {
        val entries: List<Pair<String, JsonElement>>? = when {
            element.isJsonObject -> element.asJsonObject.entrySet().map { it.key to it.value }
            element.isJsonArray -> element.asJsonArray.mapIndexed { index, item -> "$index" to item }
            else -> null
        }
        val open = entries != null && path in expanded
        add(JsonRow(path, depth, key, element, entries?.size ?: 0, open))
        if (!open) return
        entries!!.take(JSON_CHILD_LIMIT).forEach { (name, child) ->
            val childPath = if (element.isJsonArray) "$path[$name]" else "$path.$name"
            visit(child, childPath, depth + 1, name)
        }
        if (entries.size > JSON_CHILD_LIMIT) add(JsonRow("$path…", depth + 1, null, null, 0, false, moreCount = entries.size - JSON_CHILD_LIMIT))
    }
    visit(root, "$", 0, null)
}

/** A value as one line: strings quoted, objects and arrays as their size. */
internal fun jsonPreview(value: JsonElement): String = when {
    value.isJsonObject -> value.asJsonObject.size().let { if (it == 1) "{1 key}" else "{$it keys}" }
    value.isJsonArray -> value.asJsonArray.size().let { if (it == 1) "[1 item]" else "[$it items]" }
    value.isJsonNull -> "null"
    else -> value.toString().let { if (it.length > 300) it.take(300) + "…" else it }
}

/** `application/json` (IPython's `JSON()`) as a tree: tap an object or array to open it. */
@Composable
internal fun JsonTree(data: JsonElement, presentation: OutputPresentation, modifier: Modifier, onChange: (OutputPresentation) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val root = remember(data) {
        if (data.isJsonPrimitive && data.asJsonPrimitive.isString) runCatching { JsonParser.parseString(data.asString) }.getOrDefault(data) else data
    }
    val rows = remember(root, presentation.jsonExpanded) { jsonTreeRows(root, presentation.jsonExpanded) }
    val style = MaterialTheme.typography.bodySmall
    val keyColor = colors.primary
    val stringColor = colors.tertiary
    Column(modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        SelectionContainer {
            Column {
                rows.forEach { row ->
                    val container = row.children > 0 || (row.value != null && (row.value.isJsonObject || row.value.isJsonArray))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .then(
                                if (container && row.children > 0) Modifier.noKeyFocus().clickable {
                                    val next = if (row.expanded) presentation.jsonExpanded - row.path else presentation.jsonExpanded + row.path
                                    onChange(presentation.copy(jsonExpanded = next))
                                } else Modifier,
                            )
                            .padding(start = (row.depth * 16).dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
                    ) {
                        if (container && row.children > 0) {
                            Icon(
                                if (row.expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = if (row.expanded) "Close" else "Open",
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
                        }
                        val text = buildAnnotatedString {
                            if (row.moreCount > 0) {
                                withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append("… ${row.moreCount} more") }
                                return@buildAnnotatedString
                            }
                            if (row.key != null) {
                                withStyle(SpanStyle(color = keyColor)) { append(row.key) }
                                append(": ")
                            }
                            val value = row.value ?: return@buildAnnotatedString
                            val preview = jsonPreview(value)
                            val tone = when {
                                value.isJsonObject || value.isJsonArray || value.isJsonNull -> colors.onSurfaceVariant
                                value.asJsonPrimitive.isString -> stringColor
                                else -> colors.onSurface
                            }
                            withStyle(SpanStyle(color = tone)) { append(preview) }
                        }
                        Text(text, style = style, fontFamily = FontFamily.Monospace, softWrap = false)
                    }
                }
            }
        }
    }
}
