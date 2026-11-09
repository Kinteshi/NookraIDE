package io.github.kinteshi.quirecode.workbench

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val rasterExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif", "avif")

/** Files the image viewer opens instead of the text editor. */
fun isImagePath(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in rasterExtensions

fun isSvgPath(path: String): Boolean = path.substringAfterLast('.', "").lowercase() == "svg"

/** Largest side a decoded image keeps (bigger ones are subsampled, like a GPU texture limit). */
private const val MAX_DECODED_SIDE = 4096
private const val MAX_ZOOM = 32f

/** What the viewer shows: the picture's size in its own pixels and how to draw it at 1:1. */
internal class ViewerContent(
    val width: Float,
    val height: Float,
    val info: String,
    val pixelated: Boolean,
    val draw: DrawScope.(Size) -> Unit,
)

internal sealed interface Loaded {
    data object Loading : Loaded
    data class Failed(val message: String) : Loaded
    class Ready(val content: ViewerContent) : Loaded
}

/** A raster image file from the guest or this device, decoded off the main thread. */
@Composable
fun ImageViewer(controller: WorkbenchController, path: String, modifier: Modifier = Modifier) {
    val loaded by produceState<Loaded>(Loaded.Loading, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeImage(controller.readImageBytes(path), path) }
                .getOrElse { Loaded.Failed(it.cause?.message ?: it.message ?: "The image could not be read") }
        }
    }
    ViewerFrame(controller, path, loaded, modifier)
}

/** An SVG file's text drawn as a picture; edits redraw it shortly after typing stops. */
@Composable
fun SvgPreview(controller: WorkbenchController, path: String, text: () -> String, modifier: Modifier = Modifier) {
    val revision = controller.documentRevision
    val loaded by produceState<Loaded>(Loaded.Loading, path, revision) {
        if (value !is Loaded.Loading) delay(200)
        val source = text()
        value = withContext(Dispatchers.Default) {
            runCatching { svgContent(source) }.getOrElse { Loaded.Failed("Can't draw this SVG: ${it.message ?: it.javaClass.simpleName}") }
        }
    }
    ViewerFrame(controller, path, loaded, modifier)
}

internal fun decodeImage(bytes: ByteArray, path: String): Loaded {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return Loaded.Failed("Android can't decode this ${path.substringAfterLast('.').uppercase()} file")
    }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODED_SIDE) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return Loaded.Failed("The image could not be decoded")
    val image: ImageBitmap = bitmap.asImageBitmap()
    val width = bounds.outWidth.toFloat()
    val height = bounds.outHeight.toFloat()
    val info = listOfNotNull(
        path.substringAfterLast('.').uppercase(),
        "${bounds.outWidth} × ${bounds.outHeight}",
        formatBytes(bytes.size.toLong()),
        "shown at 1/$sample".takeIf { sample > 1 },
    ).joinToString(" · ")
    return Loaded.Ready(
        ViewerContent(width, height, info, pixelated = true) { size ->
            drawImage(
                image,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                // Zoomed in, pixels stay square (VS Code's pixelated rendering); smaller, smooth.
                filterQuality = if (size.width / width >= 2f) FilterQuality.None else FilterQuality.Medium,
            )
        },
    )
}

internal fun svgContent(source: String): Loaded {
    val svg = SVG.getFromString(source)
    val viewBox = svg.documentViewBox
    val width = svg.documentWidth.takeIf { it > 0 } ?: viewBox?.width()?.takeIf { it > 0 } ?: 300f
    val height = svg.documentHeight.takeIf { it > 0 } ?: viewBox?.height()?.takeIf { it > 0 } ?: 150f
    if (viewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    val info = "SVG · ${width.roundToInt()} × ${height.roundToInt()}"
    return Loaded.Ready(
        ViewerContent(width, height, info, pixelated = false) { size ->
            // A picture recorded at 1:1 and scaled by the canvas stays sharp at any zoom.
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.save()
                canvas.nativeCanvas.scale(size.width / width, size.height / height)
                svg.renderToCanvas(canvas.nativeCanvas, android.graphics.RectF(0f, 0f, width, height))
                canvas.nativeCanvas.restore()
            }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * Zoom and pan: pinch (or Ctrl and the mouse wheel) zooms around the fingers, a drag pans, a
 * double tap switches between fitting the pane and actual size (one image pixel per dp, as
 * VS Code's CSS pixels). Small images start at actual size, large ones fitted.
 */
@Composable
internal fun ViewerFrame(controller: WorkbenchController, path: String, loaded: Loaded, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(colors.surfaceContainerLowest).clipToBounds(), contentAlignment = Alignment.Center) {
        when (loaded) {
            Loaded.Loading -> Text("Loading ${path.substringAfterLast('/')}…", color = colors.onSurfaceVariant)
            is Loaded.Failed -> Text(loaded.message, color = colors.error, modifier = Modifier.padding(24.dp))
            is Loaded.Ready -> ZoomableContent(controller, path, loaded.content)
        }
    }
}

@Composable
private fun ZoomableContent(controller: WorkbenchController, path: String, content: ViewerContent) {
    val density = LocalDensity.current.density
    var view by remember { mutableStateOf(IntSize.Zero) }
    // Device pixels per picture pixel, and the picture centre's offset from the pane's centre.
    var zoom by remember(path) { mutableFloatStateOf(0f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    val fit = if (view.width == 0) 1f else min(view.width / content.width, view.height / content.height) * 0.95f
    val actual = density
    if (zoom == 0f && view.width > 0) zoom = min(fit, actual)
    val minZoom = min(fit, actual) / 4f
    fun zoomTo(next: Float, focus: Offset = Offset(view.width / 2f, view.height / 2f)) {
        if (zoom == 0f) return
        val target = next.coerceIn(minZoom, MAX_ZOOM * density)
        val center = Offset(view.width / 2f, view.height / 2f)
        offset = focus - center - (focus - center - offset) * (target / zoom)
        zoom = target
    }
    // Ctrl+= / Ctrl+- (Editor Font Zoom) zoom the image while its tab is active.
    LaunchedEffect(controller.imageZoomRequest) {
        val (requestPath, step) = controller.imageZoomRequest ?: return@LaunchedEffect
        if (requestPath == path) zoomTo(zoom * if (step > 0) 1.25f else 0.8f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { view = it }
            .pointerInput(path) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    zoomTo(zoom * gestureZoom, centroid)
                    offset += pan
                }
            }
            .pointerInput(path) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (zoom < actual * 0.99f || zoom > actual * 1.01f) zoomTo(actual, tap) else {
                        zoom = fit
                        offset = Offset.Zero
                    }
                })
            }
            .pointerInput(path) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val change = event.changes.first()
                            val delta = change.scrollDelta
                            if (event.keyboardModifiers.isCtrlPressed || delta.x == 0f) {
                                zoomTo(zoom * if (delta.y < 0) 1.15f else 1 / 1.15f, change.position)
                            } else {
                                offset -= Offset(delta.x, delta.y) * 40f
                            }
                            change.consume()
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (zoom == 0f) return@Canvas
            drawCheckerboard(content, zoom, offset)
            val size = Size(content.width * zoom, content.height * zoom)
            val topLeft = Offset((this.size.width - size.width) / 2f, (this.size.height - size.height) / 2f) + offset
            withTransform({ translate(topLeft.x, topLeft.y) }) { content.draw(this, size) }
        }
        ViewerToolbar(
            info = content.info,
            percent = (zoom / actual * 100).roundToInt(),
            onZoomOut = { zoomTo(zoom * 0.8f) },
            onZoomIn = { zoomTo(zoom * 1.25f) },
            onFit = {
                zoom = fit
                offset = Offset.Zero
            },
            onActual = { zoomTo(actual) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        )
    }
}

/** Transparent areas show a checkerboard, as image editors do. */
private fun DrawScope.drawCheckerboard(content: ViewerContent, zoom: Float, offset: Offset) {
    val width = content.width * zoom
    val height = content.height * zoom
    val left = (size.width - width) / 2f + offset.x
    val top = (size.height - height) / 2f + offset.y
    val cell = 8.dp.toPx()
    val light = Color(0xFFE6E6E6)
    val dark = Color(0xFFCCCCCC)
    val x0 = max(left, 0f)
    val y0 = max(top, 0f)
    val x1 = min(left + width, size.width)
    val y1 = min(top + height, size.height)
    if (x1 <= x0 || y1 <= y0) return
    drawRect(light, Offset(x0, y0), Size(x1 - x0, y1 - y0))
    var row = ((y0 - top) / cell).toInt()
    var y = top + row * cell
    while (y < y1) {
        var column = ((x0 - left) / cell).toInt()
        var x = left + column * cell
        while (x < x1) {
            if ((row + column) % 2 == 1) {
                val cx = max(x, x0)
                val cy = max(y, y0)
                drawRect(dark, Offset(cx, cy), Size(min(x + cell, x1) - cx, min(y + cell, y1) - cy))
            }
            x += cell
            column++
        }
        y += cell
        row++
    }
}

@Composable
private fun ViewerToolbar(
    info: String,
    percent: Int,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onFit: () -> Unit,
    onActual: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh, tonalElevation = 3.dp, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(info, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            Tooltip("Zoom out") { IconButton(onClick = onZoomOut) { Icon(Icons.Outlined.Remove, contentDescription = "Zoom out", modifier = Modifier.size(18.dp)) } }
            Text(
                "$percent%",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onActual).padding(horizontal = 6.dp, vertical = 8.dp),
            )
            Tooltip("Zoom in") { IconButton(onClick = onZoomIn) { Icon(Icons.Outlined.Add, contentDescription = "Zoom in", modifier = Modifier.size(18.dp)) } }
            Tooltip("Fit to the pane") { IconButton(onClick = onFit) { Icon(Icons.Outlined.FitScreen, contentDescription = "Fit to the pane", modifier = Modifier.size(18.dp)) } }
        }
    }
}
