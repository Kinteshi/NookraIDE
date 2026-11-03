package com.avfstudio.nativeide.notebook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * ipywidgets as the Jupyter bridge forwards them (guest/tools/avf-jupyter-bridge.py): the kernel
 * opens a comm per widget model (target "jupyter.widget") with its whole state, then sends state
 * changes; Output widgets also get the outputs they capture.
 */
sealed interface WidgetMessage {
    data class Open(val commId: String, val targetName: String, val state: JsonObject) : WidgetMessage
    data class Update(val commId: String, val state: JsonObject) : WidgetMessage
    data class Close(val commId: String) : WidgetMessage
    data class Output(val commId: String, val output: JsonObject) : WidgetMessage
    data class ClearOutput(val commId: String, val wait: Boolean) : WidgetMessage
    /** The kernel restarted: its widgets are gone. */
    data object Reset : WidgetMessage
}

fun parseWidgetMessage(method: String, params: JsonObject): WidgetMessage? {
    val commId = params.get("comm_id")?.takeIf { it.isJsonPrimitive }?.asString
    val data = params.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    fun state() = data.get("state")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    return when (method) {
        "comm_open" -> commId?.let { WidgetMessage.Open(it, params.get("target_name")?.asString.orEmpty(), state()) }
        // echo_update repeats this client's own changes (protocol 2.1); applying it could undo a newer one.
        "comm_msg" -> commId?.takeIf { data.get("method")?.asString == "update" }?.let { WidgetMessage.Update(it, state()) }
        "comm_close" -> commId?.let { WidgetMessage.Close(it) }
        "widget_output" -> commId?.let { id -> params.get("output")?.takeIf { it.isJsonObject }?.asJsonObject?.let { WidgetMessage.Output(id, it) } }
        "widget_clear_output" -> commId?.let { WidgetMessage.ClearOutput(it, params.get("wait")?.asBoolean ?: false) }
        "comms_reset" -> WidgetMessage.Reset
        else -> null
    }
}

/** A widget model; [state] is replaced, never changed in place, so what reads it redraws. */
class WidgetModel(val id: String, state: JsonObject) {
    var state by mutableStateOf(state)
    val name: String get() = state.get("_model_name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    /** An Output widget's clear_output(wait=True): its outputs go at the next one. */
    var clearPending = false
}

/** A notebook kernel's widget models, by comm id. */
class NotebookWidgets {
    val models = mutableStateMapOf<String, WidgetModel>()

    /** A model by id or by the "IPY_MODEL_<id>" reference widgets use for their children and layout. */
    fun model(reference: String?): WidgetModel? = reference?.let { models[it.removePrefix(MODEL_PREFIX)] }

    fun apply(message: WidgetMessage) {
        when (message) {
            is WidgetMessage.Open -> if (message.targetName == WIDGET_TARGET) models[message.commId] = WidgetModel(message.commId, message.state)
            is WidgetMessage.Update -> models[message.commId]?.let { it.state = mergedState(it.state, message.state) }
            is WidgetMessage.Close -> models.remove(message.commId)
            is WidgetMessage.Output -> models[message.commId]?.let { model ->
                val outputs = if (model.clearPending) JsonArray() else model.state.getAsJsonArray("outputs")?.deepCopy() ?: JsonArray()
                model.clearPending = false
                appendOutput(outputs, message.output)
                model.state = mergedState(model.state, JsonObject().apply { add("outputs", outputs) })
            }
            is WidgetMessage.ClearOutput -> models[message.commId]?.let { model ->
                if (message.wait) {
                    model.clearPending = true
                } else {
                    model.state = mergedState(model.state, JsonObject().apply { add("outputs", JsonArray()) })
                }
            }
            WidgetMessage.Reset -> models.clear()
        }
    }

    /**
     * The user changed [model]: shown at once, and what to send the kernel (comm_msg data).
     * [change] holds the changed keys only, as ipywidgets sends them.
     */
    fun change(model: WidgetModel, change: JsonObject): JsonObject {
        model.state = mergedState(model.state, change)
        return JsonObject().apply {
            addProperty("method", "update")
            add("state", change.deepCopy())
            add("buffer_paths", JsonArray())
        }
    }

    companion object {
        const val WIDGET_TARGET = "jupyter.widget"
        const val MODEL_PREFIX = "IPY_MODEL_"

        /** A button press, or Enter in a text box ("submit"): ipywidgets' custom messages. */
        fun event(name: String): JsonObject = JsonObject().apply {
            addProperty("method", "custom")
            add("content", JsonObject().apply { addProperty("event", name) })
        }
    }
}

internal fun mergedState(state: JsonObject, change: JsonObject): JsonObject =
    state.deepCopy().apply { change.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) } }

/** Appends [output], joining a stream to the stream before it of the same name, as a notebook does. */
internal fun appendOutput(outputs: JsonArray, output: JsonObject) {
    val last = outputs.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
    val stream = output.get("output_type")?.asString == "stream"
    if (stream && last?.get("output_type")?.asString == "stream" && last.get("name")?.asString == output.get("name")?.asString) {
        val text = NotebookDocument.multiline(last.get("text")) + NotebookDocument.multiline(output.get("text"))
        outputs.set(outputs.size() - 1, last.deepCopy().apply { addProperty("text", text) })
    } else {
        outputs.add(output.deepCopy())
    }
}

/** A box's children, the models its "IPY_MODEL_…" references name. */
internal fun widgetChildren(state: JsonObject): List<String> =
    state.get("children")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { child: JsonElement ->
        child.takeIf { it.isJsonPrimitive }?.asString
    }.orEmpty()
