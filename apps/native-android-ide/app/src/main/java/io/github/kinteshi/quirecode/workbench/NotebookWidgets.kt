package io.github.kinteshi.quirecode.workbench

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Stop
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt
import io.github.kinteshi.quirecode.notebook.CellType
import io.github.kinteshi.quirecode.notebook.NotebookCell
import io.github.kinteshi.quirecode.notebook.WidgetModel
import io.github.kinteshi.quirecode.notebook.widgetChildren
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/** How long a dragged slider or typed text waits before the kernel hears of it (it may rerun interact()). */
private const val SEND_DELAY_MS = 150L

/**
 * A live ipywidget, drawn natively from its model's state: what changes is sent to the kernel,
 * and what the kernel changes is shown. Boxes draw their children; an Output widget draws the
 * outputs it captured, as a cell's.
 */
@Composable
internal fun WidgetView(controller: WorkbenchController, session: NotebookSession, model: WidgetModel, modifier: Modifier = Modifier) {
    val state = model.state
    val layout = session.widgets.model(state.text("layout"))?.state
    if (layout?.text("display") == "none" || layout?.text("visibility") == "hidden") return
    fun change(key: String, value: Any?) = controller.changeWidget(session, model, JsonObject().apply { add(key, json(value)) })
    when (model.name) {
        "VBoxModel", "BoxModel", "GridBoxModel" -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WidgetChildren(controller, session, state)
        }
        "HBoxModel" -> HorizontalChildren(controller, session, state, modifier)
        "TabModel", "AccordionModel", "StackModel" -> SelectedChild(controller, session, model, modifier, ::change)
        "IntSliderModel", "FloatSliderModel", "FloatLogSliderModel" -> SliderWidget(model, modifier) { change("value", it) }
        "IntRangeSliderModel", "FloatRangeSliderModel" -> RangeSliderWidget(model, modifier) { change("value", it) }
        "DropdownModel", "SelectModel" -> Described(state, modifier) { DropdownWidget(model) { change("index", it) } }
        "RadioButtonsModel", "ToggleButtonsModel" -> Described(state, modifier) { ChoiceChips(model) { change("index", it) } }
        "CheckboxModel" -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.flag("value"), onCheckedChange = { change("value", it) }, enabled = !state.flag("disabled"))
            Text(state.text("description"), style = MaterialTheme.typography.bodyMedium)
        }
        "ToggleButtonModel" -> FilterChip(
            selected = state.flag("value"),
            onClick = { change("value", !state.flag("value")) },
            label = { Text(state.text("description").ifEmpty { " " }) },
            enabled = !state.flag("disabled"),
            modifier = modifier,
        )
        "ButtonModel" -> {
            val label = state.text("description").ifEmpty { " " }
            val onClick = { controller.widgetEvent(session, model, "click") }
            when (state.text("button_style")) {
                "" -> OutlinedButton(onClick = onClick, enabled = !state.flag("disabled"), modifier = modifier) { Text(label) }
                "danger" -> Button(
                    onClick = onClick,
                    enabled = !state.flag("disabled"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = modifier,
                ) { Text(label) }
                else -> Button(onClick = onClick, enabled = !state.flag("disabled"), modifier = modifier) { Text(label) }
            }
        }
        "TextModel", "PasswordModel", "TextareaModel", "ComboboxModel" -> Described(state, modifier) {
            TextWidget(model, onChange = { change("value", it) }, onSubmit = { controller.widgetEvent(session, model, "submit") })
        }
        "IntTextModel", "FloatTextModel", "BoundedIntTextModel", "BoundedFloatTextModel" -> Described(state, modifier) {
            NumberTextWidget(model) { change("value", it) }
        }
        "LabelModel" -> Text(state.text("value"), style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        "HTMLModel", "HTMLMathModel" -> Described(state, modifier) { HtmlOutput(state.text("value"), LocalDarkTheme.current, Modifier.fillMaxWidth()) }
        "IntProgressModel", "FloatProgressModel" -> Described(state, modifier) { ProgressWidget(state) }
        "ValidModel" -> Described(state, modifier) {
            val valid = state.flag("value")
            Icon(
                if (valid) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                contentDescription = if (valid) "Valid" else "Invalid",
                tint = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        "PlayModel" -> PlayWidget(model, modifier, ::change)
        "ColorPickerModel" -> Described(state, modifier) { ColorPickerWidget(model) { change("value", it) } }
        "DatePickerModel" -> Described(state, modifier) { DatePickerWidget(model) { change("value", it) } }
        "ImageModel" -> ImageWidget(model, modifier)
        "AudioModel" -> AudioVideoWidget(model, isVideo = false, modifier)
        "VideoModel" -> AudioVideoWidget(model, isVideo = true, modifier)
        "LinkModel", "DirectionalLinkModel" -> Unit
        "OutputModel" -> OutputWidget(controller, session, model, modifier)
        else -> ThirdPartyWidgetView(controller, session, model, modifier)
    }
}

@Composable
private fun WidgetChildren(controller: WorkbenchController, session: NotebookSession, state: JsonObject) {
    widgetChildren(state).forEach { reference ->
        session.widgets.model(reference)?.let { child -> key(child.id) { WidgetView(controller, session, child) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HorizontalChildren(controller: WorkbenchController, session: NotebookSession, state: JsonObject, modifier: Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WidgetChildren(controller, session, state)
    }
}

/** Tab, Accordion and Stack: their titles as chips, the selected child below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedChild(
    controller: WorkbenchController,
    session: NotebookSession,
    model: WidgetModel,
    modifier: Modifier,
    change: (String, Any?) -> Unit,
) {
    val state = model.state
    val children = widgetChildren(state)
    val selected = state.number("selected_index")?.toInt()
    // ipywidgets 8 has `titles`; 7 had `_titles`, a map by index.
    val titles = state.getAsJsonArray("titles")?.map { if (it.isJsonPrimitive) it.asString else "" }
        ?: state.getAsJsonObject("_titles")?.let { map -> children.indices.map { map.get(it.toString())?.asString.orEmpty() } }
        ?: emptyList()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (model.name != "StackModel") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                children.indices.forEach { index ->
                    FilterChip(
                        selected = index == selected,
                        onClick = { change("selected_index", if (index == selected && model.name == "AccordionModel") null else index) },
                        label = { Text(titles.getOrNull(index)?.ifEmpty { null } ?: "Tab ${index + 1}") },
                    )
                }
            }
        }
        selected?.let(children::getOrNull)?.let(session.widgets::model)?.let { child -> key(child.id) { WidgetView(controller, session, child) } }
    }
}

/** The widget's `description` before it, as ipywidgets lays its controls out. */
@Composable
private fun Described(state: JsonObject, modifier: Modifier, content: @Composable () -> Unit) {
    val description = state.text("description")
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (description.isNotEmpty()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(min = 72.dp, max = 180.dp).padding(end = 8.dp),
            )
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun SliderWidget(model: WidgetModel, modifier: Modifier, send: (Any) -> Unit) {
    val state = model.state
    val integer = model.name == "IntSliderModel"
    val log = model.name == "FloatLogSliderModel"
    val base = state.number("base") ?: 10.0
    val min = state.number("min") ?: 0.0
    val max = (state.number("max") ?: 100.0).coerceAtLeast(min)
    val step = state.number("step")?.takeIf { it > 0 } ?: if (integer) 1.0 else 0.1
    val value = state.number("value") ?: min
    // A log slider moves along the exponent.
    val position = if (log) kotlin.math.log(value.coerceAtLeast(Double.MIN_VALUE), base) else value
    var dragged by remember(model.id) { mutableStateOf<Double?>(null) }
    var sent by remember(model.id) { mutableStateOf<Double?>(null) }
    val continuous = state.get("continuous_update")?.asBoolean ?: true
    fun valueAt(at: Double): Any {
        val snapped = (min + ((at - min) / step).roundToLong() * step).coerceIn(min, max)
        return when {
            log -> base.pow(snapped)
            integer -> snapped.roundToLong()
            else -> snapped
        }
    }
    LaunchedEffect(dragged) {
        val at = dragged ?: return@LaunchedEffect
        if (!continuous) return@LaunchedEffect
        delay(SEND_DELAY_MS)
        if (at != sent) {
            sent = at
            send(valueAt(at))
        }
    }
    val steps = ((max - min) / step).roundToLong() - 1
    Described(state, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = (dragged ?: position).toFloat(),
                onValueChange = { dragged = it.toDouble() },
                onValueChangeFinished = {
                    dragged?.let { at -> if (at != sent) send(valueAt(at)) }
                    sent = null
                    dragged = null
                },
                valueRange = min.toFloat()..max.toFloat(),
                steps = if (steps in 1..200) steps.toInt() else 0,
                enabled = !state.flag("disabled"),
                modifier = Modifier.weight(1f),
            )
            if (state.get("readout")?.asBoolean != false) {
                val shown = dragged?.let(::valueAt) ?: value
                Text(
                    readout(shown, integer, state.text("readout_format")),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(min = 48.dp).padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RangeSliderWidget(model: WidgetModel, modifier: Modifier, send: (List<Any>) -> Unit) {
    val state = model.state
    val integer = model.name == "IntRangeSliderModel"
    val min = state.number("min") ?: 0.0
    val max = (state.number("max") ?: 100.0).coerceAtLeast(min)
    val step = state.number("step")?.takeIf { it > 0 } ?: if (integer) 1.0 else 0.1
    val values = state.getAsJsonArray("value")?.mapNotNull { it.takeIf { v -> v.isJsonPrimitive }?.asDouble }
    val low = values?.getOrNull(0) ?: min
    val high = values?.getOrNull(1) ?: max
    var dragged by remember(model.id) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    fun snap(at: Float): Any {
        val snapped = (min + ((at - min) / step).roundToLong() * step).coerceIn(min, max)
        return if (integer) snapped.roundToLong() else snapped
    }
    Described(state, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RangeSlider(
                value = dragged ?: low.toFloat()..high.toFloat(),
                onValueChange = { dragged = it },
                onValueChangeFinished = {
                    dragged?.let { send(listOf(snap(it.start), snap(it.endInclusive))) }
                    dragged = null
                },
                valueRange = min.toFloat()..max.toFloat(),
                enabled = !state.flag("disabled"),
                modifier = Modifier.weight(1f),
            )
            val shown = dragged?.let { listOf(snap(it.start), snap(it.endInclusive)) } ?: listOf(low, high)
            Text(
                shown.joinToString("–") { readout(it, integer, state.text("readout_format")) },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private fun readout(value: Any, integer: Boolean, format: String): String {
    val number = (value as? Number)?.toDouble() ?: return value.toString()
    if (integer) return number.roundToLong().toString()
    // Python's format spec as ipywidgets uses it (".2f" by default); others fall back to two decimals.
    val decimals = Regex("""^\.(\d+)f$""").find(format)?.groupValues?.get(1)?.toIntOrNull() ?: 2
    return String.format(Locale.ROOT, "%.${decimals}f", number)
}

@Composable
private fun DropdownWidget(model: WidgetModel, send: (Int) -> Unit) {
    val state = model.state
    val labels = state.labels()
    val index = state.number("index")?.toInt()
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = !state.flag("disabled")) {
            Text(index?.let(labels::getOrNull) ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEachIndexed { i, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    if (i != index) send(i)
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(model: WidgetModel, send: (Int) -> Unit) {
    val state = model.state
    val index = state.number("index")?.toInt()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.labels().forEachIndexed { i, label ->
            FilterChip(
                selected = i == index,
                onClick = { if (i != index) send(i) },
                label = { Text(label) },
                enabled = !state.flag("disabled"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextWidget(model: WidgetModel, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    val state = model.state
    val value = state.text("value")
    var text by remember(model.id) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    // What the kernel sets shows unless the user is typing.
    LaunchedEffect(value) { if (!focused) text = value }
    val continuous = state.get("continuous_update")?.asBoolean ?: true
    LaunchedEffect(text) {
        if (!continuous || text == value) return@LaunchedEffect
        delay(SEND_DELAY_MS * 2)
        onChange(text)
    }
    val multiLine = model.name == "TextareaModel"
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = state.text("placeholder").takeIf { it.isNotEmpty() }?.let { hint -> { Text(hint) } },
        singleLine = !multiLine,
        minLines = if (multiLine) (state.number("rows")?.toInt() ?: 3) else 1,
        enabled = !state.flag("disabled"),
        visualTransformation = if (model.name == "PasswordModel") PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (multiLine) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (text != value) onChange(text)
            onSubmit()
        }),
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().onFocusChanged { change ->
            if (focused && !change.isFocused && !continuous && text != value) onChange(text)
            focused = change.isFocused
        },
    )
}

@Composable
private fun NumberTextWidget(model: WidgetModel, send: (Any) -> Unit) {
    val state = model.state
    val integer = model.name.contains("Int")
    val value = state.number("value")
    fun shown(number: Double?) = when {
        number == null -> ""
        integer -> number.roundToLong().toString()
        else -> number.toString()
    }
    var text by remember(model.id) { mutableStateOf(shown(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) text = shown(value) }
    fun commit() {
        val parsed = text.trim().toDoubleOrNull() ?: run {
            text = shown(value)
            return
        }
        val min = state.number("min")
        val max = state.number("max")
        val bounded = if (model.name.startsWith("Bounded")) parsed.coerceIn(min ?: parsed, max ?: parsed) else parsed
        text = shown(bounded)
        if (bounded != value) send(if (integer) bounded.roundToLong() else bounded)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        enabled = !state.flag("disabled"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.width(160.dp).onFocusChanged { change ->
            if (focused && !change.isFocused) commit()
            focused = change.isFocused
        },
    )
}

@Composable
private fun ProgressWidget(state: JsonObject) {
    val min = state.number("min") ?: 0.0
    val max = state.number("max") ?: 100.0
    val value = state.number("value") ?: min
    val colors = MaterialTheme.colorScheme
    val color = when (state.text("bar_style")) {
        "danger" -> colors.error
        "warning" -> colors.tertiary
        "success" -> colors.secondary
        else -> colors.primary
    }
    LinearProgressIndicator(
        progress = { if (max > min) ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f) else 0f },
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

/** `ipywidgets.Play`: timer-driven integer playback control (`jslink`-aware via [NotebookWidgets.changeAndPropagate]). */
@Composable
private fun PlayWidget(model: WidgetModel, modifier: Modifier, change: (String, Any?) -> Unit) {
    val state = model.state
    val minVal = (state.number("min")?.roundToLong() ?: 0L).toInt()
    val maxVal = (state.number("max")?.roundToLong() ?: 100L).toInt().coerceAtLeast(minVal)
    val stepVal = (state.number("step")?.roundToLong() ?: 1L).toInt().coerceAtLeast(1)
    val intervalMs = (state.number("interval")?.roundToLong() ?: 100L).toInt().coerceAtLeast(16)
    val value = (state.number("value")?.roundToLong() ?: minVal.toLong()).toInt().coerceIn(minVal, maxVal)
    val playing = state.flag("playing")
    val repeat = state.flag("repeat")
    val showRepeat = state.get("show_repeat")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
    val disabled = state.flag("disabled")

    LaunchedEffect(model.id, playing, repeat, intervalMs, minVal, maxVal, stepVal, disabled) {
        if (!playing || disabled) return@LaunchedEffect
        var current = value
        while (true) {
            delay(intervalMs.toLong())
            val next = current + stepVal
            if (next <= maxVal) {
                current = next
                change("value", next)
            } else if (repeat) {
                current = minVal
                change("value", minVal)
            } else {
                change("playing", false)
                break
            }
        }
    }

    Described(state, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
                onClick = { change("playing", !playing) },
                enabled = !disabled,
            ) {
                Icon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }
            IconButton(
                onClick = {
                    if (playing) change("playing", false)
                    if (value != minVal) change("value", minVal)
                },
                enabled = !disabled,
            ) {
                Icon(Icons.Outlined.Stop, contentDescription = "Stop")
            }
            if (showRepeat) {
                FilterChip(
                    selected = repeat,
                    onClick = { change("repeat", !repeat) },
                    enabled = !disabled,
                    leadingIcon = { Icon(Icons.Outlined.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Loop", style = MaterialTheme.typography.labelMedium) },
                )
            }
            Text(
                "$value / $maxVal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

private val COLOR_PICKER_PRESETS = listOf(
    Color(0xFF000000), Color(0xFF424242), Color(0xFF9E9E9E), Color(0xFFFFFFFF),
    Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835), Color(0xFF43A047),
    Color(0xFF00897B), Color(0xFF1E88E5), Color(0xFF3949AB), Color(0xFF8E24AA),
    Color(0xFFD81B60), Color(0xFF6D4C41), Color(0xFF00ACC1), Color(0xFF7CB342),
)

/** `ipywidgets.ColorPicker`: swatch button + concise mode + Material 3 palette & RGB dialog. */
@Composable
private fun ColorPickerWidget(model: WidgetModel, send: (String) -> Unit) {
    val state = model.state
    val rawValue = state.text("value").ifBlank { "#000000" }
    val parsedColor = remember(rawValue) { parseCssColor(rawValue) ?: Color.Black }
    val concise = state.flag("concise")
    val disabled = state.flag("disabled")
    var showDialog by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(parsedColor)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .clickable(enabled = !disabled) { showDialog = true }
                .semantics {
                    contentDescription = "Pick color, $rawValue"
                    role = Role.Button
                },
        )
        if (!concise) {
            Text(
                rawValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDialog) {
        var r by remember(rawValue) { mutableStateOf((parsedColor.red * 255f).roundToInt()) }
        var g by remember(rawValue) { mutableStateOf((parsedColor.green * 255f).roundToInt()) }
        var b by remember(rawValue) { mutableStateOf((parsedColor.blue * 255f).roundToInt()) }
        var hexText by remember(rawValue) { mutableStateOf(formatCssHex(parsedColor)) }
        fun setRgb(nr: Int, ng: Int, nb: Int) {
            r = nr.coerceIn(0, 255)
            g = ng.coerceIn(0, 255)
            b = nb.coerceIn(0, 255)
            hexText = formatCssHex(Color(red = r, green = g, blue = b))
        }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(state.text("description").ifBlank { "Pick color" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(red = r, green = g, blue = b))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                        )
                        OutlinedTextField(
                            value = hexText,
                            onValueChange = { next ->
                                hexText = next
                                parseCssColor(next)?.let { c ->
                                    r = (c.red * 255f).roundToInt()
                                    g = (c.green * 255f).roundToInt()
                                    b = (c.blue * 255f).roundToInt()
                                }
                            },
                            singleLine = true,
                            label = { Text("Hex") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        COLOR_PICKER_PRESETS.forEach { preset ->
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(preset)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable {
                                        setRgb(
                                            (preset.red * 255f).roundToInt(),
                                            (preset.green * 255f).roundToInt(),
                                            (preset.blue * 255f).roundToInt(),
                                        )
                                    },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("R", Modifier.width(18.dp), style = MaterialTheme.typography.labelMedium)
                        Slider(value = r.toFloat(), onValueChange = { setRgb(it.roundToInt(), g, b) }, valueRange = 0f..255f, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("G", Modifier.width(18.dp), style = MaterialTheme.typography.labelMedium)
                        Slider(value = g.toFloat(), onValueChange = { setRgb(r, it.roundToInt(), b) }, valueRange = 0f..255f, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("B", Modifier.width(18.dp), style = MaterialTheme.typography.labelMedium)
                        Slider(value = b.toFloat(), onValueChange = { setRgb(r, g, it.roundToInt()) }, valueRange = 0f..255f, modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = parseCssColor(hexText)?.let(::formatCssHex) ?: formatCssHex(Color(red = r, green = g, blue = b))
                    send(chosen)
                    showDialog = false
                }) { Text("Select") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
        )
    }
}

/** `ipywidgets.DatePicker`: reads/writes 0-indexed-month JSON dates and opens Material 3 [DatePickerDialog]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerWidget(model: WidgetModel, send: (Any?) -> Unit) {
    val state = model.state
    val currentDate = remember(state) { parseIpyWidgetDate(state.get("value")) }
    val disabled = state.flag("disabled")
    var open by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { open = true }, enabled = !disabled) {
            Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(currentDate?.toString() ?: "Pick date")
        }
        if (currentDate != null && !disabled) {
            IconButton(onClick = { send(null) }) {
                Icon(Icons.Outlined.Close, contentDescription = "Clear date", modifier = Modifier.size(18.dp))
            }
        }
    }

    if (open) {
        val initialMillis = remember(currentDate) {
            (currentDate ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        send(toIpyWidgetDateJson(picked))
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}

/** `ipywidgets.Image`: renders decoded binary PNG/JPEG/WebP/GIF or SVG buffer (`format="svg+xml"`). */
@Composable
private fun ImageWidget(model: WidgetModel, modifier: Modifier) {
    val state = model.state
    val format = state.text("format").ifBlank { "png" }
    val bytes = remember(model.id, model.revision, model.buffers) { model.bufferBytes("value") }
    val widthDp = remember(state) { parseCssPxOrDp(state.text("width")) }
    val heightDp = remember(state) { parseCssPxOrDp(state.text("height")) }

    val widthMod = if (widthDp != null) Modifier.widthIn(max = widthDp.dp) else Modifier.fillMaxWidth()
    val heightMod = if (heightDp != null) Modifier.heightIn(max = heightDp.dp) else Modifier.heightIn(max = 520.dp)

    if (bytes == null || bytes.isEmpty()) {
        Text(
            "[Image widget: empty image buffer]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val textBody = remember(bytes, format) {
        if (format.contains("svg", ignoreCase = true) || format.equals("url", ignoreCase = true)) {
            bytes.toString(Charsets.UTF_8).trim()
        } else null
    }
    if (textBody != null && (format.contains("svg", ignoreCase = true) || textBody.startsWith("<svg"))) {
        SvgOutput(textBody, modifier.then(widthMod)) {
            Text(
                "[SVG widget could not be parsed]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (textBody != null && format.equals("url", ignoreCase = true) &&
        (textBody.startsWith("https://") || textBody.startsWith("http://"))
    ) {
        val safeUrl = textBody.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        HtmlOutput("""<img src="$safeUrl" style="max-width:100%;height:auto;border-radius:6px;" />""", LocalDarkTheme.current, modifier.then(widthMod))
        return
    }
    val bitmap = remember(bytes) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        val ratio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else 1f
        Image(
            bitmap = bitmap,
            contentDescription = state.text("description").ifBlank { "Image widget" },
            contentScale = ContentScale.Fit,
            modifier = modifier
                .then(widthMod)
                .then(heightMod)
                .aspectRatio(ratio.coerceIn(0.2f, 5f))
                .clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Text(
            "[Image widget ($format): could not decode ${bytes.size} bytes]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/** `ipywidgets.Audio` and `ipywidgets.Video`: delegates binary or URL playback to [MediaWidgetView]. */
@Composable
private fun AudioVideoWidget(model: WidgetModel, isVideo: Boolean, modifier: Modifier) {
    val state = model.state
    val format = state.text("format").ifBlank { if (isVideo) "mp4" else "mp3" }
    val bytes = remember(model.id, model.revision, model.buffers) { model.bufferBytes("value") }
    val autoplay = state.flag("autoplay")
    val loop = state.flag("loop")
    val controls = state.get("controls")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
    val widthDp = parseCssPxOrDp(state.text("width"))
    val heightDp = parseCssPxOrDp(state.text("height"))
    MediaWidgetView(
        model = model,
        isVideo = isVideo,
        bytes = bytes,
        format = format,
        autoplay = autoplay,
        loop = loop,
        controls = controls,
        widthDp = widthDp,
        heightDp = heightDp,
        modifier = modifier,
    )
}

/** An Output widget's outputs, drawn as a cell's (interact() puts its function's results here). */
@Composable
private fun OutputWidget(controller: WorkbenchController, session: NotebookSession, model: WidgetModel, modifier: Modifier) {
    val outputs = model.state.getAsJsonArray("outputs")?.mapNotNull { it.takeIf { o -> o.isJsonObject }?.asJsonObject }.orEmpty()
    val cell = remember(model.id) { NotebookCell("widget-${model.id}", CellType.Code, "") }
    LaunchedEffect(outputs) {
        cell.outputs.clear()
        cell.outputs.addAll(outputs)
        cell.outputPresentation.clear()
    }
    Column(modifier.fillMaxWidth()) {
        outputs.indices.forEach { index ->
            key(index) { cell.outputs.getOrNull(index)?.let { CellOutput(controller, session, cell, index, it) } }
        }
    }
}

private fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
private fun JsonObject.flag(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
private fun JsonObject.number(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
private fun JsonObject.labels(): List<String> =
    getAsJsonArray("_options_labels")?.map { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty()

private fun json(value: Any?): com.google.gson.JsonElement = when (value) {
    null -> com.google.gson.JsonNull.INSTANCE
    is com.google.gson.JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is List<*> -> JsonArray().apply { value.forEach { add(json(it)) } }
    else -> JsonPrimitive(value.toString())
}
