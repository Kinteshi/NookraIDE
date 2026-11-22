package io.github.kinteshi.nookraide.notebook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Base64

private const val WIDGET_STATE_MIME = "application/vnd.jupyter.widget-state+json"
private const val WIDGET_VIEW_MIME = "application/vnd.jupyter.widget-view+json"
private const val MAX_CUSTOM_EVENTS_PER_MODEL = 1024
private const val MAX_COMM_LOG_EVENTS = 2048

/**
 * ipywidgets as the Jupyter bridge forwards them (guest/tools/avf-jupyter-bridge.py): the kernel
 * opens a comm per widget model (target "jupyter.widget") with its whole state and optional binary
 * buffers, then sends state updates or custom comm messages; Output widgets also capture outputs.
 */
sealed interface WidgetMessage {
    data class Open(
        val commId: String,
        val targetName: String,
        val state: JsonObject,
        val bufferPaths: List<List<String>> = emptyList(),
        val buffers: List<ByteArray> = emptyList(),
    ) : WidgetMessage

    data class Update(
        val commId: String,
        val state: JsonObject,
        val bufferPaths: List<List<String>> = emptyList(),
        val buffers: List<ByteArray> = emptyList(),
    ) : WidgetMessage

    data class Custom(
        val commId: String,
        val content: JsonObject,
        val buffers: List<ByteArray> = emptyList(),
    ) : WidgetMessage

    data class Close(val commId: String) : WidgetMessage
    data class Output(val commId: String, val output: JsonObject) : WidgetMessage
    data class ClearOutput(val commId: String, val wait: Boolean) : WidgetMessage
    /** The kernel restarted: its live widgets are gone. */
    data object Reset : WidgetMessage
}

/** A custom kernel->frontend comm event (used e.g. by ipycanvas drawing commands and bqplot). */
data class WidgetCustomEvent(
    val seq: Long,
    val content: JsonObject,
    val buffers: List<ByteArray>,
)

/** Sequenced live comm event for mounted WebView third-party widget hosts. */
data class WidgetCommLogEntry(
    val seq: Long,
    val commId: String,
    val method: String,
    val payload: JsonObject,
    val buffers: List<ByteArray>,
)

fun parseWidgetMessage(method: String, params: JsonObject): WidgetMessage? {
    val commId = params.get("comm_id")?.takeIf { it.isJsonPrimitive }?.asString
    val data = params.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    fun state() = data.get("state")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    fun bufferPaths(): List<List<String>> =
        data.get("buffer_paths")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { entry ->
            entry.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { seg ->
                seg.takeIf { it.isJsonPrimitive }?.asString
            }
        }.orEmpty()
    fun decodedBuffers(): List<ByteArray> =
        params.get("buffers")?.takeIf { it.isJsonArray }?.asJsonArray?.map { elem ->
            if (elem.isJsonPrimitive) {
                runCatching { Base64.getDecoder().decode(elem.asString) }.getOrElse { ByteArray(0) }
            } else ByteArray(0)
        }.orEmpty()
    return when (method) {
        "comm_open" -> commId?.let {
            WidgetMessage.Open(it, params.get("target_name")?.asString.orEmpty(), state(), bufferPaths(), decodedBuffers())
        }
        // echo_update repeats this client's own changes (protocol 2.1); applying it could undo a newer one.
        "comm_msg" -> commId?.let { id ->
            when (data.get("method")?.asString) {
                "update" -> WidgetMessage.Update(id, state(), bufferPaths(), decodedBuffers())
                "custom" -> {
                    val content = data.get("content")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                    WidgetMessage.Custom(id, content, decodedBuffers())
                }
                else -> null
            }
        }
        "comm_close" -> commId?.let { WidgetMessage.Close(it) }
        "widget_output" -> commId?.let { id ->
            params.get("output")?.takeIf { it.isJsonObject }?.asJsonObject?.let { WidgetMessage.Output(id, it) }
        }
        "widget_clear_output" -> commId?.let { WidgetMessage.ClearOutput(it, params.get("wait")?.asBoolean ?: false) }
        "comms_reset" -> WidgetMessage.Reset
        else -> null
    }
}

/** A widget model; [state] and [buffers] are replaced cleanly so readers recompose deterministically. */
class WidgetModel(
    val id: String,
    state: JsonObject,
    buffers: Map<List<String>, ByteArray> = emptyMap(),
    savedOnly: Boolean = false,
) {
    var state by mutableStateOf(state)
    var buffers by mutableStateOf(buffers)
    var savedOnly by mutableStateOf(savedOnly)
    var customEvents by mutableStateOf<List<WidgetCustomEvent>>(emptyList())
    var revision by mutableLongStateOf(0L)

    val name: String get() = state.get("_model_name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    val modelModule: String get() = state.get("_model_module")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    val modelModuleVersion: String get() = state.get("_model_module_version")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    /** An Output widget's clear_output(wait=True): its outputs go at the next one. */
    var clearPending = false

    /** Returns raw binary buffer bytes stored at [path], or falls back to inline Base64 JSON if present. */
    fun bufferBytes(vararg path: String): ByteArray? {
        val key = path.toList()
        buffers[key]?.let { return it }
        var cur: JsonElement? = state
        for (segment in key) {
            cur = when {
                cur == null -> null
                cur.isJsonObject -> cur.asJsonObject.get(segment)
                cur.isJsonArray -> segment.toIntOrNull()?.let { idx ->
                    val arr = cur.asJsonArray
                    if (idx in 0 until arr.size()) arr[idx] else null
                }
                else -> null
            }
        }
        if (cur != null && cur.isJsonPrimitive && cur.asJsonPrimitive.isString) {
            return runCatching { Base64.getDecoder().decode(cur.asString) }.getOrNull()
        }
        return null
    }
}

/** A notebook's widget models (live kernel comms plus any saved `metadata.widgets`), indexed by comm id. */
class NotebookWidgets {
    val models = mutableStateMapOf<String, WidgetModel>()
    var eventSeq by mutableLongStateOf(0L)
        private set
    val commLog = mutableListOf<WidgetCommLogEntry>()

    /** A model by id or by the "IPY_MODEL_<id>" reference widgets use for their children and layout. */
    fun model(reference: String?): WidgetModel? = reference?.let { models[it.removePrefix(MODEL_PREFIX)] }

    /** Whether at least one live (kernel-backed) widget model is open. */
    val hasLiveModels: Boolean get() = models.values.any { !it.savedOnly }

    fun apply(message: WidgetMessage) {
        when (message) {
            is WidgetMessage.Open -> if (message.targetName == WIDGET_TARGET) {
                val initialBuffers = mergeBuffers(emptyMap(), message.bufferPaths, message.buffers)
                models[message.commId] = WidgetModel(
                    id = message.commId,
                    state = message.state,
                    buffers = initialBuffers,
                    savedOnly = false,
                )
                recordEvent(message.commId, "open", message.state, message.buffers)
            }
            is WidgetMessage.Update -> models[message.commId]?.let { model ->
                model.savedOnly = false
                model.state = mergedState(model.state, message.state)
                if (message.bufferPaths.isNotEmpty() || message.buffers.isNotEmpty()) {
                    model.buffers = mergeBuffers(model.buffers, message.bufferPaths, message.buffers)
                }
                model.revision++
                val payload = JsonObject().apply {
                    add("state", message.state.deepCopy())
                    add("buffer_paths", pathsArray(message.bufferPaths))
                }
                recordEvent(message.commId, "update", payload, message.buffers)
            }
            is WidgetMessage.Custom -> models[message.commId]?.let { model ->
                val seq = recordEvent(message.commId, "custom", message.content, message.buffers)
                val updated = (model.customEvents + WidgetCustomEvent(seq, message.content, message.buffers))
                    .let { list -> if (list.size > MAX_CUSTOM_EVENTS_PER_MODEL) list.takeLast(MAX_CUSTOM_EVENTS_PER_MODEL) else list }
                model.customEvents = updated
                model.revision++
            }
            is WidgetMessage.Close -> {
                models.remove(message.commId)
                recordEvent(message.commId, "close", JsonObject(), emptyList())
            }
            is WidgetMessage.Output -> models[message.commId]?.let { model ->
                val outputs = if (model.clearPending) JsonArray() else model.state.getAsJsonArray("outputs")?.deepCopy() ?: JsonArray()
                model.clearPending = false
                appendOutput(outputs, message.output)
                model.state = mergedState(model.state, JsonObject().apply { add("outputs", outputs) })
                model.revision++
            }
            is WidgetMessage.ClearOutput -> models[message.commId]?.let { model ->
                if (message.wait) {
                    model.clearPending = true
                } else {
                    model.state = mergedState(model.state, JsonObject().apply { add("outputs", JsonArray()) })
                    model.revision++
                }
            }
            WidgetMessage.Reset -> clearLive()
        }
    }

    /** Clears live kernel-backed widgets while keeping any static saved `metadata.widgets` snapshot. */
    fun clearLive() {
        val liveIds = models.entries.filter { !it.value.savedOnly }.map { it.key }
        liveIds.forEach(models::remove)
        commLog.clear()
        eventSeq++
    }

    /**
     * Loads saved Jupyter widget state (`metadata.widgets["application/vnd.jupyter.widget-state+json"]`)
     * into [models] as [WidgetModel.savedOnly] entries so offline/unexecuted notebooks draw right away.
     */
    fun loadSavedState(notebookMetadata: JsonObject) {
        val stateMap = notebookMetadata.getAsJsonObject("widgets")
            ?.getAsJsonObject(WIDGET_STATE_MIME)
            ?.getAsJsonObject("state") ?: return
        stateMap.entrySet().forEach { (id, rawEntry) ->
            if (!rawEntry.isJsonObject) return@forEach
            val entry = rawEntry.asJsonObject
            // Do not overwrite an already-open live kernel model with disk state.
            if (models[id]?.savedOnly == false) return@forEach
            val rawState = entry.getAsJsonObject("state")?.deepCopy() ?: JsonObject()
            fun ensureMeta(stateKey: String, entryKey: String) {
                if (!rawState.has(stateKey)) {
                    entry.get(entryKey)?.takeIf { it.isJsonPrimitive }?.let { rawState.add(stateKey, it.deepCopy()) }
                }
            }
            ensureMeta("_model_name", "model_name")
            ensureMeta("_model_module", "model_module")
            ensureMeta("_model_module_version", "model_module_version")
            ensureMeta("_view_name", "view_name")
            ensureMeta("_view_module", "view_module")
            ensureMeta("_view_module_version", "view_module_version")

            val savedBuffers = LinkedHashMap<List<String>, ByteArray>()
            entry.getAsJsonArray("buffers")?.forEach { rawBuf ->
                if (!rawBuf.isJsonObject) return@forEach
                val bufObj = rawBuf.asJsonObject
                val path = bufObj.getAsJsonArray("path")?.mapNotNull { seg ->
                    seg.takeIf { it.isJsonPrimitive }?.asString
                }.orEmpty()
                val dataStr = bufObj.get("data")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                val decoded = runCatching { Base64.getDecoder().decode(dataStr) }.getOrNull() ?: return@forEach
                if (path.isNotEmpty()) savedBuffers[path] = decoded
            }
            models[id] = WidgetModel(id = id, state = rawState, buffers = savedBuffers, savedOnly = true)
        }
    }

    /**
     * Persists all live widget models reachable from [notebook]'s outputs into
     * `notebook.metadata.widgets["application/vnd.jupyter.widget-state+json"]`.
     */
    fun writeSavedState(notebook: NotebookDocument) {
        if (!hasLiveModels) return
        val roots = collectCellViewModelIds(notebook)
        if (roots.isEmpty()) return
        val reachable = collectTransitiveModelIds(roots)
        if (reachable.isEmpty()) return
        val serialized = buildStatePayload(reachable)
        if (serialized.getAsJsonObject("state").size() == 0) return
        val widgetsMeta = notebook.metadata.getAsJsonObject("widgets") ?: JsonObject().also {
            notebook.metadata.add("widgets", it)
        }
        widgetsMeta.add(WIDGET_STATE_MIME, serialized)
    }

    /**
     * Builds a self-contained Jupyter widget-state JSON bundle (`version_major: 2`, `state: {...}`)
     * covering [rootModelId] and every transitively referenced dependency model + Base64 buffers.
     */
    fun exportStateJson(rootModelId: String): JsonObject {
        val ids = collectTransitiveModelIds(listOf(rootModelId))
        return buildStatePayload(ids)
    }

    /** Returns every model id reachable from [rootModelId] via `IPY_MODEL_*` references or links. */
    fun collectSubtreeIds(rootModelId: String): Set<String> = collectTransitiveModelIds(listOf(rootModelId))

    private fun buildStatePayload(modelIds: Collection<String>): JsonObject {
        val encoder = Base64.getEncoder()
        val stateMap = JsonObject()
        modelIds.forEach { id ->
            val model = models[id] ?: return@forEach
            val s = model.state
            val entry = JsonObject().apply {
                addProperty("model_name", model.name)
                addProperty("model_module", model.modelModule.ifEmpty { "@jupyter-widgets/controls" })
                addProperty("model_module_version", model.modelModuleVersion.ifEmpty { "2.0.0" })
                add("state", s.deepCopy())
                if (model.buffers.isNotEmpty()) {
                    add("buffers", JsonArray().apply {
                        model.buffers.forEach { (path, bytes) ->
                            add(JsonObject().apply {
                                addProperty("data", encoder.encodeToString(bytes))
                                addProperty("encoding", "base64")
                                add("path", JsonArray().apply { path.forEach(::add) })
                            })
                        }
                    })
                }
            }
            stateMap.add(id, entry)
        }
        return JsonObject().apply {
            addProperty("version_major", 2)
            addProperty("version_minor", 0)
            add("state", stateMap)
        }
    }

    private fun collectTransitiveModelIds(roots: Collection<String>): LinkedHashSet<String> {
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        roots.forEach { if (it in models && visited.add(it)) queue.add(it) }
        // Also pull in active LinkModel / DirectionalLinkModel instances referencing anything in visited.
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val model = models[id] ?: continue
            val refs = LinkedHashSet<String>()
            extractModelRefs(model.state, refs)
            models.values.forEach { candidate ->
                if (candidate.name == "LinkModel" || candidate.name == "DirectionalLinkModel") {
                    val linkRefs = LinkedHashSet<String>()
                    extractModelRefs(candidate.state, linkRefs)
                    if (linkRefs.any { it == id || it in visited }) {
                        refs.add(candidate.id)
                        refs.addAll(linkRefs)
                    }
                }
            }
            refs.forEach { refId ->
                if (refId in models && visited.add(refId)) queue.add(refId)
            }
        }
        return visited
    }

    /**
     * The user changed [model]: shown at once, and what to send the kernel (`comm_msg` data).
     * [change] holds the changed keys only, as ipywidgets sends them.
     */
    fun change(model: WidgetModel, change: JsonObject): JsonObject {
        model.state = mergedState(model.state, change)
        model.revision++
        return updatePayload(change)
    }

    /**
     * Applies [change] to [model] and propagates any active client-side `LinkModel` (`jslink`) or
     * `DirectionalLinkModel` (`jsdlink`), returning `(commId, comm_msg data)` pairs to sync.
     */
    fun changeAndPropagate(model: WidgetModel, change: JsonObject): List<Pair<String, JsonObject>> {
        val out = mutableListOf(model.id to change(model, change))
        val visited = hashSetOf<Pair<String, String>>()
        change.entrySet().forEach { (key, _) -> visited.add(model.id to key) }

        val pending = ArrayDeque<Pair<WidgetModel, String>>()
        change.entrySet().forEach { (key, _) -> pending.add(model to key) }

        while (pending.isNotEmpty()) {
            val (srcModel, srcTrait) = pending.removeFirst()
            val newVal = srcModel.state.get(srcTrait) ?: continue
            models.values.forEach { link ->
                if (link.name != "LinkModel" && link.name != "DirectionalLinkModel") return@forEach
                val bidirectional = link.name == "LinkModel"
                val srcPair = linkEndpoint(link.state.get("source")) ?: return@forEach
                val dstPair = linkEndpoint(link.state.get("target")) ?: return@forEach
                fun tryForward(from: Pair<String, String>, to: Pair<String, String>) {
                    if (from.first != srcModel.id || from.second != srcTrait) return
                    val targetModel = models[to.first] ?: return
                    if (!visited.add(to)) return
                    if (targetModel.state.get(to.second) == newVal) return
                    val diff = JsonObject().apply { add(to.second, newVal.deepCopy()) }
                    targetModel.state = mergedState(targetModel.state, diff)
                    targetModel.revision++
                    out.add(targetModel.id to updatePayload(diff))
                    pending.add(targetModel to to.second)
                }
                tryForward(srcPair, dstPair)
                if (bidirectional) tryForward(dstPair, srcPair)
            }
        }
        return out
    }

    private fun updatePayload(change: JsonObject): JsonObject = JsonObject().apply {
        addProperty("method", "update")
        add("state", change.deepCopy())
        add("buffer_paths", JsonArray())
    }

    private fun recordEvent(commId: String, method: String, payload: JsonObject, buffers: List<ByteArray>): Long {
        val seq = ++eventSeq
        commLog.add(WidgetCommLogEntry(seq, commId, method, payload.deepCopy(), buffers))
        if (commLog.size > MAX_COMM_LOG_EVENTS) {
            commLog.subList(0, commLog.size - MAX_COMM_LOG_EVENTS).clear()
        }
        return seq
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

private fun mergeBuffers(
    existing: Map<List<String>, ByteArray>,
    paths: List<List<String>>,
    buffers: List<ByteArray>,
): Map<List<String>, ByteArray> {
    if (paths.isEmpty()) return existing
    val copy = LinkedHashMap(existing)
    paths.forEachIndexed { index, path ->
        val bytes = buffers.getOrNull(index)
        if (path.isNotEmpty() && bytes != null) copy[path] = bytes
    }
    return copy
}

private fun pathsArray(paths: List<List<String>>): JsonArray = JsonArray().apply {
    paths.forEach { path -> add(JsonArray().apply { path.forEach(::add) }) }
}

private fun linkEndpoint(element: JsonElement?): Pair<String, String>? {
    val arr = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    if (arr.size() < 2) return null
    val ref = arr[0]?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    val trait = arr[1]?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    return ref.removePrefix(NotebookWidgets.MODEL_PREFIX) to trait
}

private fun collectCellViewModelIds(notebook: NotebookDocument): Set<String> {
    val ids = LinkedHashSet<String>()
    notebook.cells.forEach { cell ->
        cell.outputs.forEach { out ->
            out.getAsJsonObject("data")
                ?.getAsJsonObject(WIDGET_VIEW_MIME)
                ?.get("model_id")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.let(ids::add)
        }
    }
    return ids
}

private fun extractModelRefs(element: JsonElement, out: MutableSet<String>) {
    when {
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
            val s = element.asString
            if (s.startsWith(NotebookWidgets.MODEL_PREFIX)) {
                out.add(s.removePrefix(NotebookWidgets.MODEL_PREFIX))
            }
        }
        element.isJsonArray -> element.asJsonArray.forEach { extractModelRefs(it, out) }
        element.isJsonObject -> element.asJsonObject.entrySet().forEach { (_, v) -> extractModelRefs(v, out) }
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
