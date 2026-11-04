package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.avfstudio.nativeide.notebook.NotebookDocument
import com.caverock.androidsvg.SVG
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image as MdImage
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak

private const val KATEX = "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist"

/** Markdown with its math taken out ([math], in order) and `@@n@@` in its place, as Jupyter does before rendering. */
internal data class ProtectedMath(val text: String, val math: List<String>)

private val fence = Regex("""^\s{0,3}(`{3,}|~{3,})""")

/**
 * Takes `$$…$$`, `$…$`, `\[…\]`, `\(…\)` and `\begin{…}…\end{…}` out of [source] so Markdown
 * doesn't eat their backslashes, underscores and stars; code spans and fenced code are left alone.
 * A `$` only opens math when a non-space follows it, and only closes it after a non-space and
 * before a non-digit, so prices ("$5 and $10") stay text.
 */
internal fun protectMath(source: String): ProtectedMath {
    val math = mutableListOf<String>()
    val out = StringBuilder()
    var index = 0
    var fenceMarker: String? = null
    var lineStart = true
    fun take(end: Int) {
        out.append("@@").append(math.size).append("@@")
        math += source.substring(index, end)
        index = end
    }
    while (index < source.length) {
        if (lineStart) {
            val lineEnd = source.indexOf('\n', index).let { if (it < 0) source.length else it }
            val marker = fence.find(source.substring(index, lineEnd))?.groupValues?.get(1)
            if (fenceMarker != null) {
                if (marker != null && marker[0] == fenceMarker[0] && marker.length >= fenceMarker.length) fenceMarker = null
                out.append(source, index, (lineEnd + 1).coerceAtMost(source.length))
                index = lineEnd + 1
                continue
            }
            if (marker != null) {
                fenceMarker = marker
                out.append(source, index, (lineEnd + 1).coerceAtMost(source.length))
                index = lineEnd + 1
                continue
            }
            lineStart = false
        }
        val char = source[index]
        when {
            char == '\n' -> {
                out.append(char)
                index++
                lineStart = true
            }
            char == '`' -> {
                var ticks = index
                while (ticks < source.length && source[ticks] == '`') ticks++
                val run = source.substring(index, ticks)
                val close = source.indexOf(run, ticks)
                val end = if (close < 0) ticks else close + run.length
                out.append(source, index, end)
                index = end
            }
            source.startsWith("$$", index) -> {
                val close = source.indexOf("$$", index + 2)
                if (close < 0) {
                    out.append("$$")
                    index += 2
                } else {
                    take(close + 2)
                }
            }
            char == '$' && index + 1 < source.length && !source[index + 1].isWhitespace() -> {
                var close = index + 1
                var found = -1
                while (close < source.length) {
                    val c = source[close]
                    if (c == '\\') {
                        close += 2
                        continue
                    }
                    if (c == '\n' && source.startsWith("\n\n", close)) break
                    if (c == '$' && !source[close - 1].isWhitespace() && source.getOrNull(close + 1)?.isDigit() != true) {
                        found = close
                        break
                    }
                    close++
                }
                if (found < 0) {
                    out.append(char)
                    index++
                } else {
                    take(found + 1)
                }
            }
            source.startsWith("\\[", index) || source.startsWith("\\(", index) -> {
                val closer = if (source[index + 1] == '[') "\\]" else "\\)"
                val close = source.indexOf(closer, index + 2)
                if (close < 0) {
                    out.append(source, index, index + 2)
                    index += 2
                } else {
                    take(close + 2)
                }
            }
            source.startsWith("\\begin{", index) -> {
                val nameEnd = source.indexOf('}', index + 7)
                val name = if (nameEnd < 0) null else source.substring(index + 7, nameEnd)
                val close = name?.let { source.indexOf("\\end{$it}", nameEnd) } ?: -1
                if (close < 0) {
                    out.append(char)
                    index++
                } else {
                    take(close + "\\end{$name}".length)
                }
            }
            char == '\\' && index + 1 < source.length -> {
                out.append(source, index, index + 2)
                index += 2
            }
            else -> {
                out.append(char)
                index++
            }
        }
    }
    return ProtectedMath(out.toString(), math)
}

/**
 * Puts the math back into rendered HTML as spans KaTeX draws one by one (`data-tex` without the
 * delimiters); the span's text is the source, shown as is when KaTeX can't load. Only math found
 * here is drawn, so a stray `$` elsewhere stays text.
 */
internal fun restoreMath(html: String, math: List<String>): String =
    Regex("@@(\\d+)@@").replace(html) { match ->
        val source = math.getOrNull(match.groupValues[1].toInt()) ?: return@replace match.value
        val (tex, display) = mathBody(source)
        "<span class=\"math\" data-display=\"$display\" data-tex=\"${escapeHtml(tex)}\">${escapeHtml(source)}</span>"
    }

/** The formula without its delimiters, and whether it is display math. */
internal fun mathBody(source: String): Pair<String, Boolean> = when {
    source.startsWith("$$") -> source.removeSurrounding("$$") to true
    source.startsWith("\\[") -> source.removePrefix("\\[").removeSuffix("\\]") to true
    source.startsWith("\\(") -> source.removePrefix("\\(").removeSuffix("\\)") to false
    source.startsWith("\\begin") -> source to true
    else -> source.removeSurrounding("$") to false
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Whether the Markdown has raw HTML (`<img>`, `<div>`, `<br>`), which only a page can show. */
internal fun hasRawHtml(document: Node): Boolean {
    var node: Node? = document.firstChild
    while (node != null) {
        if (node is HtmlBlock || node is HtmlInline || hasRawHtml(node)) return true
        node = node.next
    }
    return false
}

/** `attachment:name` images as `data:` URIs, from the cell's `attachments` (nbformat). */
internal fun inlineAttachments(source: String, attachments: JsonObject?): String {
    if (attachments == null || "attachment:" !in source) return source
    return Regex("""attachment:([^\s)"'>]+)""").replace(source) { match ->
        attachmentData(attachments, match.groupValues[1])?.let { (mime, data) -> "data:$mime;base64,$data" } ?: match.value
    }
}

private fun attachmentData(attachments: JsonObject?, name: String): Pair<String, String>? {
    val bundle = attachments?.get(java.net.URLDecoder.decode(name, "UTF-8"))?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val (mime, value) = bundle.entrySet().firstOrNull { it.key.startsWith("image/") } ?: return null
    return mime to NotebookDocument.multiline(value).replace("\n", "")
}

/** How a notebook's Markdown finds its links and images: relative to the notebook, or in its attachments. */
internal class MarkdownSource(
    val baseUrl: String,
    val attachments: JsonObject?,
    val onLink: (String) -> Unit,
    val readWorkspaceImage: (String) -> ByteArray?,
) {
    fun resolve(href: String): String =
        runCatching { URI(baseUrl).resolve(href.trim().replace(" ", "%20")).toString() }.getOrDefault(href)

    /** An image's bytes: attached, inline (`data:`), from the web, or from the workspace. Blocks; call off the main thread. */
    fun imageBytes(src: String): ByteArray? = when {
        src.startsWith("attachment:") -> attachmentData(attachments, src.removePrefix("attachment:"))?.let { Base64.decode(it.second, Base64.DEFAULT) }
        src.startsWith("data:") -> src.substringAfter(',', "").takeIf { ";base64" in src.substringBefore(',') }?.let { Base64.decode(it, Base64.DEFAULT) }
        src.startsWith("http://") || src.startsWith("https://") -> download(src)
        else -> URI(resolve(src)).takeIf { it.host == MARKDOWN_BASE_HOST }?.path?.let(readWorkspaceImage)
    }

    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_PREVIEW_IMAGE_BYTES) return null
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** A decoded image and the width it is drawn at (its CSS pixels, as dp). */
private class MarkdownPicture(val bitmap: ImageBitmap, val width: Float)

private fun decodePicture(bytes: ByteArray): MarkdownPicture? {
    val head = String(bytes, 0, minOf(bytes.size, 512)).trimStart()
    if (head.startsWith("<svg") || (head.startsWith("<?xml") && "<svg" in String(bytes))) {
        val svg = SVG.getFromString(String(bytes))
        val box = svg.documentViewBox
        val width = svg.documentWidth.takeIf { it > 0 } ?: box?.width()?.takeIf { it > 0 } ?: 300f
        val height = svg.documentHeight.takeIf { it > 0 } ?: box?.height()?.takeIf { it > 0 } ?: 150f
        if (box == null) svg.setDocumentViewBox(0f, 0f, width, height)
        val scale = minOf(2f, 2048f / maxOf(width, height))
        val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(android.graphics.Canvas(bitmap), android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
        return MarkdownPicture(bitmap.asImageBitmap(), width)
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return MarkdownPicture(bitmap.asImageBitmap(), bitmap.width.toFloat())
}

/** Colours the inline text uses. */
internal data class InlineColors(val link: Color, val code: Color, val codeBackground: Color, val muted: Color)

/** A paragraph's (or heading's, or cell's) inline Markdown as styled text; links call [onLink] with their target. */
internal fun inlineMarkdown(parent: Node, colors: InlineColors, onLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString { appendInlines(parent, colors, onLink) }

private fun AnnotatedString.Builder.appendInlines(parent: Node, colors: InlineColors, onLink: (String) -> Unit) {
    var child = parent.firstChild
    while (child != null) {
        val node = child
        when (node) {
            is MdText -> append(node.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.code, background = colors.codeBackground)) {
                append('\u2009')
                append(node.literal)
                append('\u2009')
            }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(node, colors, onLink) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(node, colors, onLink) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(node, colors, onLink) }
            is Link -> withLink(
                LinkAnnotation.Clickable(node.destination, TextLinkStyles(SpanStyle(color = colors.link))) { onLink(node.destination) },
            ) { appendInlines(node, colors, onLink) }
            is MdImage -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.muted)) {
                append(plainText(node).ifEmpty { node.destination })
            }
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            is HtmlInline -> append(node.literal)
            else -> appendInlines(node, colors, onLink)
        }
        child = node.next
    }
}

private fun plainText(node: Node): String = buildString {
    var child = node.firstChild
    while (child != null) {
        if (child is MdText) append(child.literal) else append(plainText(child))
        child = child.next
    }
}

/**
 * A notebook's Markdown (cells and `text/markdown` outputs) as VS Code shows it: headings,
 * lists and task lists, quotes, code (Python coloured), tables, rules, links and images, drawn
 * natively. A cell with math (when "Draw LaTeX" is on) or raw HTML is drawn as a small page
 * instead, with KaTeX for the math.
 */
@Composable
internal fun NotebookMarkdown(controller: WorkbenchController, markdown: String, source: MarkdownSource, modifier: Modifier = Modifier) {
    val renderLatex = controller.settings.renderLatex
    val protected = remember(markdown) { protectMath(markdown) }
    val document = remember(markdown) { MarkdownRenderer.parse(markdown) }
    val asPage = remember(document, protected, renderLatex) { hasRawHtml(document) || (renderLatex && protected.math.isNotEmpty()) }
    if (asPage) {
        MarkdownCellPage(controller, markdown, protected, source, katex = renderLatex && protected.math.isNotEmpty(), modifier = modifier)
        return
    }
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val context = MarkdownContext(
        source,
        InlineColors(colors.primary, colors.onSurface, colors.surfaceContainerHigh, colors.onSurfaceVariant),
        syntaxPalette(controller.settings.syntaxColors, colors, dark),
    )
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { MarkdownBlocks(document, context, 0) }
    }
}

/**
 * An agent's reply: the same native Markdown as a notebook, with smaller headings for the narrow
 * pane and code blocks that can be copied. Never a page (math or raw HTML stay text): the reply
 * arrives in chunks, and a page would reload at each one.
 */
@Composable
internal fun ChatMarkdown(controller: WorkbenchController, markdown: String, modifier: Modifier = Modifier) {
    val document = remember(markdown) { MarkdownRenderer.parse(markdown) }
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val root = controller.workspaceRoot.orEmpty()
    val source = remember(root) { controller.markdownSource("$root/", null) }
    val context = MarkdownContext(
        source,
        InlineColors(colors.primary, colors.onSurface, colors.surfaceContainerHigh, colors.onSurfaceVariant),
        syntaxPalette(controller.settings.syntaxColors, colors, dark),
        compact = true,
        onCopyCode = controller::copyChatCode,
    )
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { MarkdownBlocks(document, context, 0) }
    }
}

private class MarkdownContext(
    val source: MarkdownSource,
    val colors: InlineColors,
    val palette: SyntaxPalette,
    val compact: Boolean = false,
    val onCopyCode: ((String) -> Unit)? = null,
) {
    fun inline(node: Node): AnnotatedString = inlineMarkdown(node, colors) { href ->
        if (!href.startsWith("#")) source.onLink(source.resolve(href))
    }
}

@Composable
private fun MarkdownBlocks(parent: Node, context: MarkdownContext, depth: Int) {
    var child = parent.firstChild
    while (child != null) {
        if (child !is TaskListItemMarker) MarkdownBlock(child, context, depth)
        child = child.next
    }
}

@Composable
private fun MarkdownBlock(node: Node, context: MarkdownContext, depth: Int) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    when (node) {
        is Heading -> Column {
            val style = when {
                context.compact -> if (node.level <= 2) typography.titleMedium else typography.titleSmall
                node.level == 1 -> typography.headlineSmall
                node.level == 2 -> typography.titleLarge
                node.level == 3 -> typography.titleMedium
                else -> typography.titleSmall
            }
            Text(remember(node) { context.inline(node) }, style = style.copy(fontWeight = FontWeight.SemiBold))
            if (node.level <= 2 && !context.compact) HorizontalDivider(Modifier.padding(top = 4.dp), color = colors.outlineVariant)
        }
        is Paragraph -> {
            val image = node.firstChild as? MdImage
            if (image != null && image.next == null) {
                MarkdownImage(image.destination, plainText(image), context)
            } else {
                Text(remember(node) { context.inline(node) }, style = typography.bodyMedium)
            }
        }
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outlineVariant, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurfaceVariant) {
                    MarkdownBlocks(node, context, depth)
                }
            }
        }
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var number = (node as? OrderedList)?.markerStartNumber ?: 1
            var item = node.firstChild
            while (item != null) {
                if (item is ListItem) {
                    val task = item.firstChild as? TaskListItemMarker
                    val marker = when {
                        task != null -> if (task.isChecked) "☑" else "☐"
                        node is OrderedList -> "${number++}."
                        else -> listOf("•", "◦", "▪")[depth % 3]
                    }
                    Row {
                        Text(marker, style = typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 22.dp).padding(end = 4.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { MarkdownBlocks(item, context, depth + 1) }
                    }
                }
                item = item.next
            }
        }
        is FencedCodeBlock -> MarkdownCode(node.literal.trimEnd('\n'), node.info.orEmpty(), context)
        is IndentedCodeBlock -> MarkdownCode(node.literal.trimEnd('\n'), "", context)
        is ThematicBreak -> HorizontalDivider(color = colors.outlineVariant)
        is TableBlock -> MarkdownTable(node, context)
        is HtmlBlock -> Text(node.literal.trimEnd(), fontFamily = FontFamily.Monospace, style = typography.bodySmall)
        else -> MarkdownBlocks(node, context, depth)
    }
}

@Composable
private fun MarkdownCode(code: String, info: String, context: MarkdownContext) {
    val colors = MaterialTheme.colorScheme
    val language = info.trim().substringBefore(' ').lowercase()
    val text = remember(code, language, context.palette) {
        if (language in setOf("python", "py", "python3", "ipython", "ipython3")) {
            PythonHighlighter(context.palette).filter(AnnotatedString(code)).text
        } else {
            AnnotatedString(code)
        }
    }
    val copy = context.onCopyCode
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh, RoundedCornerShape(6.dp))) {
        if (copy != null) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Tooltip("Copy code") {
                    androidx.compose.material3.IconButton(onClick = { copy(code) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = if (copy != null) 0.dp else 10.dp),
        )
    }
}

/** Column by column, so every column is as wide as its widest cell; wide tables scroll sideways. */
@Composable
private fun MarkdownTable(table: TableBlock, context: MarkdownContext) {
    val colors = MaterialTheme.colorScheme
    val rows = remember(table) {
        buildList {
            var section = table.firstChild
            while (section != null) {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        val cells = generateSequence(row.firstChild) { it.next }.filterIsInstance<TableCell>().toList()
                        add((section is TableHead) to cells)
                    }
                    row = row.next
                }
                section = section.next
            }
        }
    }
    val columns = rows.maxOfOrNull { it.second.size } ?: 0
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(6.dp)),
    ) {
        for (column in 0 until columns) {
            Column(Modifier.width(IntrinsicSize.Max)) {
                rows.forEachIndexed { index, (header, cells) ->
                    val cell = cells.getOrNull(column)
                    val alignment = when (cell?.alignment) {
                        TableCell.Alignment.CENTER -> Alignment.Center
                        TableCell.Alignment.RIGHT -> Alignment.CenterEnd
                        else -> Alignment.CenterStart
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    header -> colors.surfaceContainerHigh
                                    index % 2 == 0 -> colors.surfaceContainerLow
                                    else -> Color.Transparent
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = alignment,
                    ) {
                        Text(
                            if (cell != null) remember(cell) { context.inline(cell) } else AnnotatedString(""),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (header) FontWeight.SemiBold else null,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownImage(src: String, alt: String, context: MarkdownContext) {
    val colors = MaterialTheme.colorScheme
    val picture by produceState<MarkdownPicture?>(null, src) {
        value = withContext(Dispatchers.IO) { runCatching { context.source.imageBytes(src)?.let(::decodePicture) }.getOrNull() }
    }
    val shown = picture
    if (shown == null) {
        Text("🖼 ${alt.ifEmpty { src.substringAfterLast('/') }}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        return
    }
    Image(
        shown.bitmap,
        contentDescription = alt.ifEmpty { null },
        contentScale = ContentScale.Fit,
        modifier = Modifier.widthIn(max = shown.width.dp).fillMaxWidth().aspectRatio(shown.bitmap.width.toFloat() / shown.bitmap.height.coerceAtLeast(1)),
    )
}

private val random = SecureRandom()

/** A Markdown cell with math or raw HTML: rendered as the preview renders Markdown, with KaTeX for its math. */
internal fun markdownCellPage(body: String, colors: ColorScheme, nonce: String, katex: Boolean): String {
    val math = if (!katex) "" else """
        <link rel="stylesheet" href="$KATEX/katex.min.css">
        <script nonce="$nonce" src="$KATEX/katex.min.js"></script>
        <script nonce="$nonce" src="$KATEX/contrib/auto-render.min.js"></script>
    """.trimIndent()
    val render = if (!katex) "" else """
        <script nonce="$nonce">
        if (window.katex) document.querySelectorAll('span.math').forEach(function (el) {
          var display = el.getAttribute('data-display') === 'true';
          try { katex.render(el.getAttribute('data-tex'), el, {throwOnError: false, displayMode: display}); } catch (e) {}
          if (display) el.style.display = 'block';
        });
        </script>
    """.trimIndent()
    return """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; style-src 'unsafe-inline' https://cdn.jsdelivr.net; font-src https://cdn.jsdelivr.net; script-src 'nonce-$nonce'">
        $math
        <style>${markdownStyles(colors)}
        body{font-size:14px}main{padding:0}main>:first-child{margin-top:0}main>:last-child{margin-bottom:0}.katex-display{overflow-x:auto;overflow-y:hidden}</style>
        </head><body><main>$body</main>$render</body></html>
    """.trimIndent()
}

/**
 * The content's own height (the page's scrollHeight is never less than the view, so it can't
 * shrink), read again as KaTeX and images arrive. CSS pixels are dp in a WebView.
 */
private fun measureContent(view: WebView, onHeight: (Int) -> Unit) {
    val page = view.tag
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    listOf(0L, 250L, 700L, 1500L, 3000L).forEach { delay ->
        handler.postDelayed({
            if (view.tag != page) return@postDelayed
            view.evaluateJavascript("Math.ceil(document.querySelector('main').getBoundingClientRect().height)") { value ->
                value?.trim('"')?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }?.let(onHeight)
            }
        }, delay)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownCellPage(
    controller: WorkbenchController,
    markdown: String,
    protected: ProtectedMath,
    source: MarkdownSource,
    katex: Boolean,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val nonce = remember { ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) } }
    val page = remember(markdown, katex, colors.onSurface.toArgb(), colors.primary.toArgb(), colors.surfaceContainerHigh.toArgb()) {
        val text = inlineAttachments(if (katex) protected.text else markdown, source.attachments)
        val html = MarkdownRenderer.render(text).let { if (katex) restoreMath(it, protected.math) else it }
        markdownCellPage(html, colors, nonce, katex)
    }
    var height by remember(page) { mutableIntStateOf(24) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) = measureContent(view) { height = it.coerceAtLeast(16) }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        controller.post { controller.openMarkdownLink(url) }
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url
                        if (url.host != MARKDOWN_BASE_HOST) return null
                        val bytes = url.path?.let(source.readWorkspaceImage)
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
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL(source.baseUrl, page, "text/html", "utf-8", null)
            }
        },
        onRelease = WebView::destroy,
        modifier = modifier.fillMaxWidth().height(height.dp),
    )
}
