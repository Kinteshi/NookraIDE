package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val HEX_ROW = 16

/**
 * A binary file, read-only, as VS Code's Hex Editor shows it: offset, 16 bytes in hex, and the
 * same bytes as text (`.` for the unprintable). Tap a byte to see it decoded (the data
 * inspector); "Go to offset" jumps. Pages of 64 KB are read from the guest as they scroll in.
 */
@Composable
internal fun HexView(controller: WorkbenchController, path: String) {
    val colors = MaterialTheme.colorScheme
    val file = controller.hexFiles[path]
    if (file == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val rows = ((file.size + HEX_ROW - 1) / HEX_ROW).toInt()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var goTo by remember(path) { mutableStateOf("") }
    val fontSize = (controller.shownFontSize * 0.9f).sp
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Read-only hex view · ${"%,d".format(file.size)} bytes", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = goTo,
                onValueChange = { goTo = it.trim() },
                placeholder = { Text("Go to offset (0x…)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = {
                    parseOffset(goTo)?.takeIf { it in 0 until file.size }?.let { offset ->
                        file.selected = offset
                        scope.launch { state.scrollToItem((offset / HEX_ROW).toInt()) }
                    }
                }),
                modifier = Modifier.width(220.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(rows) { row ->
                    val offset = row.toLong() * HEX_ROW
                    val page = (offset / HEX_PAGE_BYTES).toInt()
                    if (!file.pages.containsKey(page)) controller.loadHexPage(file, page)
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("%08X".format(offset), fontFamily = FontFamily.Monospace, fontSize = fontSize, color = colors.onSurfaceVariant)
                        Row {
                            for (column in 0 until HEX_ROW) {
                                val at = offset + column
                                val byte = if (at < file.size) file.byteAt(at) else null
                                val text = when {
                                    at >= file.size -> "  "
                                    byte == null -> "··"
                                    else -> "%02X".format(byte.toInt() and 0xFF)
                                }
                                Text(
                                    text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize,
                                    color = when {
                                        byte == null -> colors.onSurfaceVariant
                                        byte.toInt() == 0 -> colors.onSurfaceVariant.copy(alpha = 0.6f)
                                        else -> colors.onSurface
                                    },
                                    modifier = Modifier
                                        .background(if (file.selected == at) colors.primaryContainer else colors.surface.copy(alpha = 0f))
                                        .clickable(enabled = byte != null) { file.selected = at }
                                        .padding(horizontal = 3.dp)
                                        .padding(start = if (column == 8) 8.dp else 0.dp),
                                )
                            }
                        }
                        Text(
                            buildString {
                                for (column in 0 until HEX_ROW) {
                                    val at = offset + column
                                    if (at >= file.size) break
                                    val value = file.byteAt(at)?.toInt()?.and(0xFF)
                                    append(if (value != null && value in 0x20..0x7E) value.toChar() else '.')
                                }
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize,
                            color = colors.tertiary,
                        )
                    }
                }
            }
        }
        file.selected?.let { DataInspector(file, it) }
    }
}

/** The selected byte read as VS Code's data inspector reads it (little-endian). */
@Composable
private fun DataInspector(file: HexFile, offset: Long) {
    val colors = MaterialTheme.colorScheme
    val bytes = ByteArray(8) { index -> file.byteAt(offset + index) ?: 0 }
    val available = (0 until 8).count { offset + it < file.size }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val values = buildList {
        add("offset" to "0x%X (%d)".format(offset, offset))
        add("uint8" to (bytes[0].toInt() and 0xFF).toString())
        add("int8" to bytes[0].toString())
        if (available >= 2) add("uint16" to (buffer.getShort(0).toInt() and 0xFFFF).toString())
        if (available >= 4) add("int32" to buffer.getInt(0).toString())
        if (available >= 4) add("float32" to buffer.getFloat(0).toString())
        if (available >= 8) add("int64" to buffer.getLong(0).toString())
        if (available >= 8) add("float64" to buffer.getDouble(0).toString())
        add("binary" to Integer.toBinaryString(bytes[0].toInt() and 0xFF).padStart(8, '0'))
    }
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        values.forEach { (name, value) ->
            Column {
                Text(name, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
            }
        }
    }
}

/** "1234", "0x4d2" or "4D2h" as a byte offset. */
internal fun parseOffset(text: String): Long? {
    val value = text.trim().lowercase().replace("_", "")
    return when {
        value.startsWith("0x") -> value.drop(2).toLongOrNull(16)
        value.endsWith("h") -> value.dropLast(1).toLongOrNull(16)
        value.any { it in 'a'..'f' } -> value.toLongOrNull(16)
        else -> value.toLongOrNull()
    }
}
