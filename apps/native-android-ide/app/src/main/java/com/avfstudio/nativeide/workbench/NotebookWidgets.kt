package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import com.avfstudio.nativeide.notebook.CellType
import com.avfstudio.nativeide.notebook.NotebookCell
import com.avfstudio.nativeide.notebook.WidgetModel
import com.avfstudio.nativeide.notebook.widgetChildren
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
        "OutputModel" -> OutputWidget(controller, session, model, modifier)
        else -> Text(
            "[${model.name.removeSuffix("Model").ifEmpty { "Unknown" }} widget: not drawn here yet]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
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
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is List<*> -> JsonArray().apply { value.forEach { add(json(it)) } }
    else -> JsonPrimitive(value.toString())
}
